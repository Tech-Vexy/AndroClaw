package ai.androclaw.core

import android.content.Context
import androidx.work.*
import ai.androclaw.agent.OpenClawAgent
import ai.androclaw.agent.memory.OpenClawMemoryStore
import ai.androclaw.data.prefs.ConfigBridge
import ai.androclaw.data.prefs.ConfigStore
import ai.androclaw.feature.notifications.WhatsAppMessageProvider
import ai.androclaw.data.db.OpenClawDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import ai.androclaw.feature.device.DeviceTools

/**
 * Rebuilds the Koog agent with a fresh config snapshot after settings change.
 *
 * Triggered by SettingsViewModel.save() via:
 *   AgentRestartWorker.schedule(context)
 *
 * Uses a 1-second initial delay so the DataStore write completes before we read.
 * Replaces any previously enqueued restart (ExistingWorkPolicy.REPLACE) so
 * rapid consecutive saves only trigger one restart.
 */
class AgentRestartWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val agentService: AgentService by inject()
    private val db: OpenClawDatabase by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Timber.d("AgentRestartWorker: rebuilding agent with fresh config")

            val store  = ConfigStore(applicationContext)
            val dbPath = applicationContext.getDatabasePath("openclaw.db").absolutePath
            val config = ConfigBridge.fromStore(store, dbPath)

            val memStore = OpenClawMemoryStore(dbPath).also { it.dao = db.memoryDao() }
            val waProvider = WhatsAppMessageProvider(db)

            val deviceTools = DeviceTools(config, applicationContext).allTools()
            val freshAgent  = OpenClawAgent.build(config, memStore, waProvider, extraTools = deviceTools)
            agentService.replaceAgent(freshAgent)

            Timber.d("AgentRestartWorker: agent rebuilt successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "AgentRestartWorker: rebuild failed")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "agent_restart"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<AgentRestartWorker>()
                .setInitialDelay(1, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build())
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)

            Timber.d("AgentRestartWorker: scheduled")
        }
    }
}

