package ai.androclaw

import android.app.Application
import ai.androclaw.di.agentModule
import ai.androclaw.di.configModule
import ai.androclaw.di.databaseModule
import ai.androclaw.di.networkModule
import ai.androclaw.di.syncModule
import ai.androclaw.di.telephonyModule
import ai.androclaw.di.viewModelModule
import ai.androclaw.di.voiceModule
import ai.androclaw.feature.auth.GoogleTokenRefreshWorker
import ai.androclaw.feature.notifications.WaMessageSyncer
import ai.androclaw.feature.outbox.OutboxWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

class OpenClawApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        startKoin {
            androidContext(this@OpenClawApplication)
            modules(
                networkModule,
                databaseModule,
                configModule,
                syncModule,
                telephonyModule,
                agentModule,
                voiceModule,
                viewModelModule,
            )
        }

        // Register FCM token with gateway (idempotent)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                get<WaMessageSyncer>().registerDevice()
            } catch (e: Exception) {
                Timber.w(e, "Device registration skipped — gateway not yet configured")
            }
        }

        // Periodic Google OAuth token refresh (every 45 min)
        GoogleTokenRefreshWorker.schedule(this)

        // Recurring outbox drain on connectivity restore
        OutboxWorker.scheduleRecurring(this)

        Timber.d("OpenClaw started")
    }
}

