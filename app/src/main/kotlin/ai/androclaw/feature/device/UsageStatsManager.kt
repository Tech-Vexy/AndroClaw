package ai.androclaw.feature.device

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * App Usage Stats — gives the agent context about device usage patterns.
 *
 * Requires: android.permission.PACKAGE_USAGE_STATS
 * This is a special permission granted via:
 *   Settings → Apps → Special app access → Usage access → OpenClaw
 *
 * Use cases:
 *   - "What apps have I used most today?" → agent query
 *   - "How much time did I spend on Instagram this week?"
 *   - Proactive focus mode: detect social media usage spiral and suggest a break
 *   - Context for AI: agent knows you've been in a meeting (calendar) and
 *     haven't touched your phone (usage stats) → skip non-urgent notifications
 *
 * Privacy: usage stats are sensitive. The agent should only query them
 * when the user explicitly asks, and never transmit them to external services.
 */
class AppUsageManager(private val context: Context) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    val hasPermission: Boolean
        get() = try {
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1),
                System.currentTimeMillis(),
            )
            stats != null && stats.isNotEmpty()
        } catch (e: Exception) { false }

    // ── Query API ─────────────────────────────────────────────────────────────

    /**
     * Get top apps by foreground time for a given period.
     * @param periodHours look-back window in hours (default 24)
     * @param limit max number of apps to return
     */
    suspend fun getTopApps(
        periodHours: Long = 24,
        limit: Int = 10,
    ): List<AppUsageStat> = withContext(Dispatchers.IO) {
        if (!hasPermission) return@withContext listOf(
            AppUsageStat(
                packageName = "system",
                appName = "Permission required",
                foregroundMs = 0L,
                note = "Grant Usage Access in Settings → Apps → Special app access"
            )
        )
        try {
            val end   = System.currentTimeMillis()
            val start = end - TimeUnit.HOURS.toMillis(periodHours)
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                ?.filter { it.totalTimeInForeground > 0 }
                ?.sortedByDescending { it.totalTimeInForeground }
                ?.take(limit)
                ?.map { stat ->
                    AppUsageStat(
                        packageName      = stat.packageName,
                        appName          = getAppName(stat.packageName),
                        foregroundMs     = stat.totalTimeInForeground,
                        lastUsed         = stat.lastTimeUsed,
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "AppUsageManager: query failed")
            emptyList()
        }
    }

    /**
     * Get screen time for a specific app over a period.
     */
    suspend fun getAppScreenTime(
        packageName: String,
        periodHours: Long = 24,
    ): Long = withContext(Dispatchers.IO) {
        if (!hasPermission) return@withContext -1L
        val end   = System.currentTimeMillis()
        val start = end - TimeUnit.HOURS.toMillis(periodHours)
        usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            ?.firstOrNull { it.packageName == packageName }
            ?.totalTimeInForeground ?: 0L
    }

    /**
     * Get a timeline of app launches in the last [periodMinutes] minutes.
     * Useful for the agent to understand recent activity context.
     */
    suspend fun getRecentLaunches(periodMinutes: Long = 60): List<AppLaunchEvent> =
        withContext(Dispatchers.IO) {
            if (!hasPermission) return@withContext emptyList()
            val end   = System.currentTimeMillis()
            val start = end - TimeUnit.MINUTES.toMillis(periodMinutes)
            val events = mutableListOf<AppLaunchEvent>()
            try {
                val usageEvents = usm.queryEvents(start, end)
                val event       = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        events.add(AppLaunchEvent(
                            packageName = event.packageName,
                            appName     = getAppName(event.packageName),
                            timestamp   = event.timeStamp,
                        ))
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "AppUsageManager: event query failed")
            }
            events.sortedByDescending { it.timestamp }
        }

    /**
     * Format screen time in a human-readable way.
     * e.g. 3661000ms → "1h 1m"
     */
    fun formatDuration(ms: Long): String {
        val hours   = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return when {
            hours > 0   -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else        -> "${seconds}s"
        }
    }

    private fun getAppName(packageName: String): String = try {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    } catch (e: Exception) {
        packageName.substringAfterLast('.')
    }
}

// ── Data models ───────────────────────────────────────────────────────────────

data class AppUsageStat(
    val packageName: String,
    val appName: String,
    val foregroundMs: Long,
    val lastUsed: Long = 0L,
    val note: String = "",
) {
    val formattedTime: String get() {
        val h = foregroundMs / 3_600_000
        val m = (foregroundMs % 3_600_000) / 60_000
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}

data class AppLaunchEvent(
    val packageName: String,
    val appName: String,
    val timestamp: Long,
)

