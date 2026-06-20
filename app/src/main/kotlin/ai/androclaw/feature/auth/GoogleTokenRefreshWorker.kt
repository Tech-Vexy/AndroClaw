package ai.androclaw.feature.auth

import android.content.Context
import androidx.work.*
import ai.androclaw.agent.OpenClawConfig
import ai.androclaw.data.prefs.ConfigStore
import ai.androclaw.core.AgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Refreshes the Google OAuth access token before it expires (typically ~1h).
 *
 * Scheduled as a periodic task every 45 minutes.
 * On success, updates ConfigStore and hot-swaps the token into the live
 * OpenClawConfig via AgentService.updateGoogleToken().
 *
 * This avoids silent mid-session failures on Gmail/Calendar/Drive MCP calls.
 */
class GoogleTokenRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val agentService: AgentService by inject()
    private val configStore: ConfigStore by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val googleAuth = GoogleOAuthManager(applicationContext, configStore)
            val freshToken = googleAuth.getFreshToken()

            if (freshToken == null) {
                Timber.w("GoogleTokenRefreshWorker: not signed in, skipping refresh")
                return@withContext Result.success()
            }

            // Token already saved to ConfigStore by getFreshToken()
            // Notify the agent service to use the new token immediately
            agentService.updateGoogleToken(freshToken)

            Timber.d("GoogleTokenRefreshWorker: token refreshed successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "GoogleTokenRefreshWorker: refresh failed")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "google_token_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GoogleTokenRefreshWorker>(
                45, TimeUnit.MINUTES,
                10, TimeUnit.MINUTES,   // flex window
            )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,  // don't reset timer if already running
                request,
            )
            Timber.d("GoogleTokenRefreshWorker: scheduled every 45 min")
        }

        fun cancelAndReschedule(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            schedule(context)
        }
    }
}

