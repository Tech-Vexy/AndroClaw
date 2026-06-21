package ai.androclaw.agent.skills

import ai.koog.agents.core.tools.Tool
import ai.androclaw.agent.OpenClawConfig
import ai.androclaw.tools.messaging.WhatsAppTools
import ai.androclaw.tools.memory.MemoryTools

/**
 * Central registry mapping channel → Koog Tool list.
 * Device tools live in :app and are injected at build time via OpenClawAgent.build().
 */
object SkillRegistry {

    fun whatsappTools(config: OpenClawConfig): List<Tool<*, *>> =
        WhatsAppTools(config).allTools()

    fun memoryTools(config: OpenClawConfig): List<Tool<*, *>> =
        MemoryTools(config).allTools()
}

