package ai.androclaw.agent

import ai.androclaw.agent.memory.OpenClawMemoryStore
import ai.androclaw.mcp.McpClientManager
import ai.androclaw.tools.memory.MemoryTools
import ai.androclaw.tools.messaging.WhatsAppTools
import ai.androclaw.tools.telephony.TelephonyTools
import ai.androclaw.tools.telephony.SendSmsTool
import ai.androclaw.tools.telephony.MakeCallTool
import ai.androclaw.tools.telephony.ReadSmsTool
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.feature.handler.agent.AgentStartingContext
import ai.koog.agents.core.feature.handler.agent.AgentCompletedContext
import ai.koog.agents.core.feature.handler.tool.ToolCallStartingContext
import ai.koog.agents.core.feature.handler.tool.ToolCallCompletedContext
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.http.client.ktor.KtorKoogHttpClient
import timber.log.Timber

object OpenClawAgent {
    fun build(
        config: OpenClawConfig,
        memoryStore: OpenClawMemoryStore? = null,
        waProvider: Any? = null,
        context: android.content.Context? = null,
        extraTools: List<Any> = emptyList(),
    ): AIAgent<String, String> {
        val googleClient = GoogleLLMClient(
            config.googleGenAiApiKey,
            GoogleClientSettings(),
            KtorKoogHttpClient.Factory()
        )
        val googleExecutor = MultiLLMPromptExecutor(googleClient)
        
        // Build local and remote tools
        val localTools = mutableListOf<Any>()
        
        // WhatsApp Tools (Vonage Messages API)
        if (config.hasWhatsApp) {
            val waTools = WhatsAppTools(config)
            // wire provider
            if (waProvider != null) {
                try {
                    val method = waProvider::class.java.getMethod("asProviderLambda")
                    @Suppress("UNCHECKED_CAST")
                    val lambda = method.invoke(waProvider) as? (suspend (Int, String) -> List<ai.androclaw.tools.messaging.WaMessage>)
                    if (lambda != null) {
                        val readTool = waTools.ReadMessagesTool()
                        readTool.messageProvider = lambda
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to wire WhatsApp provider via reflection")
                }
            }
            localTools.addAll(waTools.allTools())
        }
        
        // Telephony Tools (AgentPhone)
        val telephonyTools = TelephonyTools(config)
        localTools.addAll(telephonyTools.allTools())
        
        // Memory Tools
        if (memoryStore != null) {
            val memTools = MemoryTools(config)
            memTools.store = memoryStore
            localTools.addAll(memTools.allTools())
        }
        
        
        // Add extra device tools passed from :app
        localTools.addAll(extraTools)
        
        // Flatten list of tools
        val flattenedTools = mutableListOf<ai.koog.agents.core.tools.Tool<*, *>>()
        localTools.forEach { t ->
            if (t is ai.koog.agents.core.tools.Tool<*, *>) {
                flattenedTools.add(t)
            } else if (t is List<*>) {
                t.forEach { item ->
                    if (item is ai.koog.agents.core.tools.Tool<*, *>) {
                        flattenedTools.add(item)
                    }
                }
            }
        }

        val toolsRegistry = ToolRegistry {
            // Register local tools
            tools(flattenedTools)
            
            // Register remote MCP tools if configured
            if (config.hasGoogle) {
                tools(McpClientManager.gmailMcp(config).tools)
                tools(McpClientManager.calendarMcp(config).tools)
                tools(McpClientManager.driveMcp(config).tools)
            }
            if (config.hasGitHub) {
                tools(McpClientManager.githubMcp(config).tools)
            }

            if (config.hasAgentPhone) {
                tools(McpClientManager.agentPhoneMcp(config).tools)
            }

        }
        
        val builder = AIAgent.builder()
            .promptExecutor(googleExecutor)
            .llmModel(GoogleModels.Gemini2_5Flash)
            .systemPrompt(systemPrompt(config))
            .toolRegistry(toolsRegistry)
            .maxIterations(30)
            
        // Install Persistence feature
        builder.install(Persistence.Feature) { cfg ->
            cfg.storage = config.persistenceStorage
        }

        // Install LongTermMemory RAG feature
        if (memoryStore != null) {
            builder.install(LongTermMemory.Feature) { cfg ->
                cfg.ingestion {
                    storage = memoryStore
                }
                cfg.retrieval {
                    storage = memoryStore
                }
            }
        }
        
        // Install Event Handler feature
        builder.install(EventHandler.Feature) { cfg ->
            cfg.onAgentStarting { ctx: AgentStartingContext ->
                Timber.d("Agent starting...")
            }
            cfg.onAgentCompleted { ctx: AgentCompletedContext ->
                Timber.d("Agent completed: ${ctx.result}")
            }
            cfg.onToolCallStarting { ctx: ToolCallStartingContext ->
                Timber.d("Tool call starting: ${ctx.toolName} with args ${ctx.toolArgs}")
            }
            cfg.onToolCallCompleted { ctx: ToolCallCompletedContext ->
                Timber.d("Tool call completed: ${ctx.toolName} result: ${ctx.toolResult}")
            }
        }
        
        return builder.build()
    }
    
    private fun systemPrompt(config: OpenClawConfig): String {
        return """
            Wewe ni AndroClaw, msaidizi wa kibinafsi mwenye akili bandia (AI personal assistant) anayeendeshwa kwenye kifaa cha Android cha ${config.userName}.
            Lugha yako kuu ya mawasiliano ni ${if (config.language == "sw") "Kiswahili" else "Kiingereza"}.
            
            Uhusika na Maadili:
            1. Kuwa msaidizi mwaminifu na mwenye adabu.
            2. Fanya kazi kikamilifu chinichini (in the background) bila kusumbua skrini ya mtumiaji isipokuwa lazima.
            3. Tumia huduma za Cloud APIs (AgentPhone kwa calls/SMS, Vonage kwa WhatsApp) badala ya kufungua apps za native.
            
            Maagizo ya Kazi:
            - Telephony: Piga simu kwa kutumia telephony_make_call na tuma SMS kwa kutumia telephony_send_sms.
            - WhatsApp: Tuma ujumbe kupitia whatsapp_send_text au template.
            - Memory: Hifadhi habari muhimu ukitumia memory_save ili uzikumbuke baadaye.
        """.trimIndent()
    }
}
