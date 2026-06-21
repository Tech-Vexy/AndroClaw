package ai.androclaw.agent.skills

import ai.koog.agents.core.tools.Tool
import ai.androclaw.agent.OpenClawConfig
import ai.androclaw.tools.email.EmailTools
import ai.androclaw.tools.messaging.WhatsAppTools
import ai.androclaw.tools.calendar.CalendarTools
import ai.androclaw.tools.memory.MemoryTools

/**
 * Central registry mapping channel → Koog Tool list.
 * Device tools live in :app and are injected at build time via OpenClawAgent.build().
 */
object SkillRegistry {

    fun emailTools(config: OpenClawConfig): List<Tool<*, *>> =
        EmailTools(config).allTools()

    fun whatsappTools(config: OpenClawConfig): List<Tool<*, *>> =
        WhatsAppTools(config).allTools()
    fun calendarTools(config: OpenClawConfig): List<Tool<*, *>> =
        CalendarTools(config).allTools()

    fun memoryTools(config: OpenClawConfig): List<Tool<*, *>> =
        MemoryTools(config).allTools()
}

