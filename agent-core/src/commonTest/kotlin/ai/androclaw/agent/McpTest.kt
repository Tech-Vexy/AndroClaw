package ai.androclaw.agent

import kotlin.test.Test
import java.lang.reflect.Modifier

class McpTest {
    @Test
    fun testInspectMcp() {
        val classesToInspect = listOf(
            "ai.koog.agents.core.feature.handler.agent.AgentStartingContext",
            "ai.koog.agents.core.feature.handler.agent.AgentCompletedContext",
            "ai.koog.agents.core.feature.handler.tool.ToolCallStartingContext",
            "ai.koog.agents.core.feature.handler.tool.ToolCallCompletedContext"
        )

        for (clazzName in classesToInspect) {
            try {
                val clazz = Class.forName(clazzName)
                println("=== CLASS: ${clazz.name} ===")
                
                // Superclass & Interfaces
                println("Superclass: ${clazz.superclass?.name}")
                println("Interfaces: ${clazz.interfaces.joinToString { it.name }}")

                // Public Fields
                for (field in clazz.fields) {
                    if (Modifier.isPublic(field.modifiers)) {
                        println("Public Field: ${field.name} (${field.type.name})")
                    }
                }

                // All Public Methods (including inherited ones)
                for (method in clazz.methods) {
                    if (Modifier.isPublic(method.modifiers)) {
                        val params = method.parameterTypes.joinToString(", ") { it.name }
                        println("Public Method: ${method.name}($params) -> ${method.returnType.name}")
                    }
                }
            } catch (e: Exception) {
                println("Failed to load class $clazzName: ${e.message}")
            }
            println()
        }
    }
}
