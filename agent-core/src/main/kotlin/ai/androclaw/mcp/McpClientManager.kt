package ai.androclaw.mcp

import ai.androclaw.agent.OpenClawConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.DefaultMcpToolDescriptorParser
import ai.koog.agents.mcp.metadata.McpServerInfo
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.header
import kotlinx.coroutines.runBlocking

/**
 * Central registry of all MCP server connections.
 *
 * Transport choice per server:
 *   SSE              — Google Workspace (Gmail, Calendar, Drive), Slack
 *   StreamableHTTP   — GitHub, Linear, Notion  (preferred; more reliable)
 *   SSE (self-hosted)— Vonage MCP, M-Pesa MCP  (our Render gateway)
 *
 * All connections are lazy — the Koog MCP client only opens the SSE stream
 * or HTTP session when the first tool call is made. Connections that require
 * OAuth tokens the user hasn't yet configured are safely skipped in
 * OpenClawAgent.buildToolRegistry() via the isConfigured() guards.
 */
object McpClientManager {

    // ── Google Workspace ──────────────────────────────────────────────────────

    fun gmailMcp(config: OpenClawConfig): ToolRegistry = runBlocking {
        val transport = McpToolRegistryProvider.defaultSseTransport(
            "https://gmailmcp.googleapis.com/mcp/v1",
            HttpClient {
                install(DefaultRequest) {
                    header("Authorization", "Bearer ${config.googleOAuthToken}")
                    header("Accept", "text/event-stream")
                }
            }
        )
        val registry = McpToolRegistryProvider.fromTransport(
            transport,
            McpServerInfo("https://gmailmcp.googleapis.com/mcp/v1", ""),
            DefaultMcpToolDescriptorParser,
            "gmail",
            "1.0"
        ) as ToolRegistry
        registry.filter(setOf(
            "gmail_list_messages",
            "gmail_get_message",
            "gmail_send_email",
            "gmail_reply_email",
            "gmail_search_messages",
            "gmail_mark_read",
            "gmail_create_draft",
            "gmail_list_labels",
            "gmail_modify_labels",
        ))
    }

    fun calendarMcp(config: OpenClawConfig): ToolRegistry = runBlocking {
        val transport = McpToolRegistryProvider.defaultSseTransport(
            "https://calendarmcp.googleapis.com/mcp/v1",
            HttpClient {
                install(DefaultRequest) {
                    header("Authorization", "Bearer ${config.googleOAuthToken}")
                    header("Accept", "text/event-stream")
                }
            }
        )
        val registry = McpToolRegistryProvider.fromTransport(
            transport,
            McpServerInfo("https://calendarmcp.googleapis.com/mcp/v1", ""),
            DefaultMcpToolDescriptorParser,
            "calendar",
            "1.0"
        ) as ToolRegistry
        registry.filter(setOf(
            "calendar_list_events",
            "calendar_get_event",
            "calendar_create_event",
            "calendar_update_event",
            "calendar_delete_event",
            "calendar_list_calendars",
            "calendar_find_free_time",
        ))
    }

    fun driveMcp(config: OpenClawConfig): ToolRegistry = runBlocking {
        val transport = McpToolRegistryProvider.defaultSseTransport(
            "https://drivemcp.googleapis.com/mcp/v1",
            HttpClient {
                install(DefaultRequest) {
                    header("Authorization", "Bearer ${config.googleOAuthToken}")
                    header("Accept", "text/event-stream")
                }
            }
        )
        val registry = McpToolRegistryProvider.fromTransport(
            transport,
            McpServerInfo("https://drivemcp.googleapis.com/mcp/v1", ""),
            DefaultMcpToolDescriptorParser,
            "drive",
            "1.0"
        ) as ToolRegistry
        registry.filter(setOf(
            "drive_list_files",
            "drive_get_file",
            "drive_search_files",
            "drive_create_file",
            "drive_update_file",
            "drive_delete_file",
            "drive_share_file",
            "drive_get_file_content",
        ))
    }

    // ── GitHub ────────────────────────────────────────────────────────────────

    fun githubMcp(config: OpenClawConfig): ToolRegistry = runBlocking {
        val registry = McpToolRegistryProvider.streamableHttp {
            url = "https://api.githubcopilot.com/mcp/"
            name = "github"
            version = "1.0"
            httpClient = HttpClient {
                install(DefaultRequest) {
                    header("Authorization", "Bearer ${config.githubPat}")
                    header("User-Agent", "OpenClaw-Android/1.0")
                }
            }
        } as ToolRegistry
        registry.filter(setOf(
            "search_repositories",
            "get_repository",
            "list_issues",
            "get_issue",
            "create_issue",
            "update_issue",
            "add_issue_comment",
            "list_pull_requests",
            "get_pull_request",
            "create_pull_request",
            "list_commits",
            "get_file_contents",
            "search_code",
            "list_notifications",
        ))
    }

    // ── Slack ─────────────────────────────────────────────────────────────────

    fun slackMcp(config: OpenClawConfig): ToolRegistry = runBlocking {
        val transport = McpToolRegistryProvider.defaultSseTransport(
            "https://mcp.slack.com/sse",
            HttpClient {
                install(DefaultRequest) {
                    header("Authorization", "Bearer ${config.slackBotToken}")
                }
            }
        )
        val registry = McpToolRegistryProvider.fromTransport(
            transport,
            McpServerInfo("https://mcp.slack.com/sse", ""),
            DefaultMcpToolDescriptorParser,
            "slack",
            "1.0"
        ) as ToolRegistry
        registry.filter(setOf(
            "slack_list_channels",
            "slack_get_channel_history",
            "slack_post_message",
            "slack_reply_to_thread",
            "slack_add_reaction",
            "slack_get_users",
            "slack_search_messages",
            "slack_list_dms",
            "slack_send_dm",
            "slack_upload_file",
            "slack_get_user_profile",
        ))
    }

    // ── Linear ────────────────────────────────────────────────────────────────

    fun linearMcp(config: OpenClawConfig): ToolRegistry = runBlocking {
        val registry = McpToolRegistryProvider.streamableHttp {
            url = "https://mcp.linear.app/mcp"
            name = "linear"
            version = "1.0"
            httpClient = HttpClient {
                install(DefaultRequest) {
                    header("Authorization", "Bearer ${config.linearApiKey}")
                }
            }
        } as ToolRegistry
        registry.filter(setOf(
            "linear_list_issues",
            "linear_get_issue",
            "linear_create_issue",
            "linear_update_issue",
            "linear_list_projects",
            "linear_get_project",
            "linear_list_teams",
            "linear_list_cycles",
            "linear_search_issues",
            "linear_list_my_issues",
            "linear_add_comment",
            "linear_list_labels",
        ))
    }

    // ── Notion ────────────────────────────────────────────────────────────────

    fun notionMcp(config: OpenClawConfig): ToolRegistry = runBlocking {
        val registry = McpToolRegistryProvider.streamableHttp {
            url = "https://mcp.notion.com/mcp"
            name = "notion"
            version = "1.0"
            httpClient = HttpClient {
                install(DefaultRequest) {
                    header("Authorization", "Bearer ${config.notionToken}")
                    header("Notion-Version", "2025-09-03")
                }
            }
        } as ToolRegistry
        registry.filter(setOf(
            "notion-search",
            "notion-fetch",
            "notion-create-pages",
            "notion-update-page",
            "notion-move-pages",
            "notion-duplicate-page",
            "notion-create-database",
            "notion-update-database",
            "notion-create-comment",
            "notion-get-comments",
            "notion-get-users",
        ))
    }

    // ── AgentPhone ────────────────────────────────────────────────────────────

    fun agentPhoneMcp(config: OpenClawConfig): ToolRegistry = runBlocking {
        val registry = McpToolRegistryProvider.streamableHttp {
            url = "https://mcp.agentphone.ai"
            name = "agentphone"
            version = "1.0"
            httpClient = HttpClient {
                install(DefaultRequest) {
                    header("Authorization", "Bearer ${config.agentPhoneApiKey}")
                }
            }
        } as ToolRegistry
        registry.filter(setOf(
            "account_overview",
            "get_usage",
            "list_numbers",
            "buy_number",
            "release_number",
            "get_messages",
            "list_conversations",
            "get_conversation",
            "list_calls",
            "list_calls_for_number",
            "get_call",
            "make_call",
            "make_conversation_call",
            "list_agents",
            "create_agent",
            "update_agent",
            "delete_agent",
            "get_agent",
            "attach_number",
            "list_voices",
            "get_webhook",
            "set_webhook",
            "delete_webhook",
            "get_agent_webhook",
            "set_agent_webhook",
            "delete_agent_webhook",
        ))
    }

    // ── M-Pesa (self-hosted MCP on Render) ────────────────────────────────────

    fun mpesaMcp(config: OpenClawConfig): ToolRegistry = runBlocking {
        val transport = McpToolRegistryProvider.defaultSseTransport(
            "${config.gatewayBaseUrl}/mcp/mpesa/sse",
            HttpClient {
                install(DefaultRequest) {
                    header("X-Bridge-Secret", config.bridgeSecret)
                }
            }
        )
        val registry = McpToolRegistryProvider.fromTransport(
            transport,
            McpServerInfo("${config.gatewayBaseUrl}/mcp/mpesa/sse", ""),
            DefaultMcpToolDescriptorParser,
            "mpesa",
            "1.0"
        ) as ToolRegistry
        registry.filter(setOf(
            "mpesa_stk_push",
            "mpesa_b2c_send",
            "mpesa_check_balance",
            "mpesa_transaction_status",
            "mpesa_reverse_transaction",
        ))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ToolRegistry.filter(allowedNames: Set<String>): ToolRegistry {
        val filteredTools = this.tools.filter { it.name in allowedNames }
        return ToolRegistry {
            tools(filteredTools)
        }
    }
}
