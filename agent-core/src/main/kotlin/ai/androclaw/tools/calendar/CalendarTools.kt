package ai.androclaw.tools.calendar

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.typeToken
import ai.koog.serialization.TypeToken
import ai.androclaw.agent.OpenClawConfig
import io.ktor.client.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable data class CalendarEvent(
    val id: String,
    val title: String,
    val start: String,   // ISO 8601
    val end: String,
    val location: String = "",
    val description: String = "",
    val attendees: List<String> = emptyList(),
)
@Serializable data class EventListResult(val events: List<CalendarEvent>)
@Serializable data class EventCreateResult(val eventId: String, val htmlLink: String)
@Serializable data class FreeSlotsResult(val slots: List<String>)  // ISO 8601 slot starts

class CalendarTools(private val config: OpenClawConfig) {

    private val client = HttpClient()
    private val gatewayBase get() = "${config.gatewayBaseUrl}/calendar"

    fun allTools(): List<Tool<*, *>> = listOf(
        ListEventsTool(), CreateEventTool(), GetFreeSlotsTool()
    )

    // ── Tool Argument Data Classes (Defined nested in CalendarTools to be valid Kotlin and avoid name collisions) ──

    @Serializable
    data class ListEventsArgs(
        val timeMin: String = "",   // ISO 8601 — defaults to now
        val timeMax: String = "",   // ISO 8601 — defaults to +7 days
        val maxResults: Int = 10,
    )

    @Serializable
    data class CreateEventArgs(
        val title: String,
        val start: String,          // ISO 8601
        val end: String,            // ISO 8601
        val description: String = "",
        val location: String = "",
        val attendeeEmails: List<String> = emptyList(),
    )

    @Serializable
    data class GetFreeSlotsArgs(
        val date: String,               // YYYY-MM-DD
        val durationMinutes: Int = 30,
    )

    // ── Tool 1: List events ───────────────────────────────────────────────

    inner class ListEventsTool : Tool<ListEventsArgs, EventListResult>(
        argsType = typeToken<ListEventsArgs>(),
        resultType = typeToken<EventListResult>(),
        name = "calendar_list_events",
        description = "List upcoming calendar events. Defaults to the next 7 days."
    ) {
        override suspend fun execute(args: ListEventsArgs): EventListResult {
            val resp = client.get("$gatewayBase/events") {
                if (args.timeMin.isNotBlank()) parameter("time_min", args.timeMin)
                if (args.timeMax.isNotBlank()) parameter("time_max", args.timeMax)
                parameter("max_results", args.maxResults)
            }
            return parseEventListResponse(resp.bodyAsText())
        }
    }

    // ── Tool 2: Create event ────────────────────────────────────────────────

    inner class CreateEventTool : Tool<CreateEventArgs, EventCreateResult>(
        argsType = typeToken<CreateEventArgs>(),
        resultType = typeToken<EventCreateResult>(),
        name = "calendar_create_event",
        description = "Create a new calendar event. Can invite attendees by email."
    ) {
        override suspend fun execute(args: CreateEventArgs): EventCreateResult {
            val attendees = args.attendeeEmails.joinToString(",")
            val resp = client.post("$gatewayBase/events") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                      "title":       "${args.title}",
                      "start":       "${args.start}",
                      "end":         "${args.end}",
                      "description": "${args.description}",
                      "location":    "${args.location}",
                      "attendees":   "$attendees"
                    }
                """.trimIndent())
            }
            val id   = """"id"\s*:\s*"([^"]+)"""".toRegex().find(resp.bodyAsText())?.groupValues?.get(1) ?: "unknown"
            val link = """"htmlLink"\s*:\s*"([^"]+)"""".toRegex().find(resp.bodyAsText())?.groupValues?.get(1) ?: ""
            return EventCreateResult(eventId = id, htmlLink = link)
        }
    }

    // ── Tool 3: Find free time slots ────────────────────────────────────────

    inner class GetFreeSlotsTool : Tool<GetFreeSlotsArgs, FreeSlotsResult>(
        argsType = typeToken<GetFreeSlotsArgs>(),
        resultType = typeToken<FreeSlotsResult>(),
        name = "calendar_get_free_slots",
        description = "Find free time slots on a given date for scheduling meetings"
    ) {
        override suspend fun execute(args: GetFreeSlotsArgs): FreeSlotsResult {
            val resp = client.get("$gatewayBase/free-slots") {
                parameter("date", args.date)
                parameter("duration", args.durationMinutes)
            }
            return try {
                val root  = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val slots = root["slots"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive?.content } ?: emptyList()
                FreeSlotsResult(slots = slots)
            } catch (e: Exception) {
                FreeSlotsResult(slots = emptyList())
            }
        }
    }
}

// ── Response parser ───────────────────────────────────────────────────────────

private fun parseEventListResponse(body: String): EventListResult {
    return try {
        val root = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(body).jsonObject
        val events = root["events"]?.jsonArray?.map { el ->
            val obj = el.jsonObject
            CalendarEvent(
                id          = obj["id"]?.jsonPrimitive?.content ?: "",
                title       = obj["summary"]?.jsonPrimitive?.content
                              ?: obj["title"]?.jsonPrimitive?.content ?: "(no title)",
                start       = obj["start"]?.jsonObject?.get("dateTime")?.jsonPrimitive?.content
                              ?: obj["start"]?.jsonPrimitive?.content ?: "",
                end         = obj["end"]?.jsonObject?.get("dateTime")?.jsonPrimitive?.content
                              ?: obj["end"]?.jsonPrimitive?.content ?: "",
                location    = obj["location"]?.jsonPrimitive?.content ?: "",
                description = obj["description"]?.jsonPrimitive?.content ?: "",
                attendees   = obj["attendees"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["email"]?.jsonPrimitive?.content }
                    ?: emptyList(),
            )
        } ?: emptyList()
        EventListResult(events = events)
    } catch (e: Exception) {
        EventListResult(events = emptyList())
    }
}
