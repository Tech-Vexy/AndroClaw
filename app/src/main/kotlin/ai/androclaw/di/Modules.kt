package ai.androclaw.di

import ai.androclaw.agent.OpenClawAgent
import ai.androclaw.agent.memory.OpenClawMemoryStore
import ai.androclaw.data.db.OpenClawDatabase
import ai.androclaw.data.prefs.ConfigBridge
import ai.androclaw.data.prefs.ConfigStore
import ai.androclaw.core.AgentService
import ai.androclaw.feature.notifications.WaMessageSyncer
import ai.androclaw.feature.notifications.WhatsAppMessageProvider
import ai.androclaw.feature.chat.ChatViewModel
import ai.androclaw.feature.onboarding.OnboardingViewModel
import ai.androclaw.feature.settings.SettingsViewModel
import ai.androclaw.feature.telephony.VoiceViewModel
import ai.androclaw.feature.telephony.VoiceManager
import androidx.room.Room
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import ai.androclaw.feature.auth.GoogleOAuthManager
import ai.androclaw.feature.auth.FirebaseAuthManager
import ai.androclaw.feature.auth.FirebaseFirestoreManager
import ai.androclaw.feature.auth.AuthViewModel
import ai.androclaw.feature.device.DeviceTools
import ai.androclaw.feature.telephony.AndroidSmsProvider
import ai.androclaw.feature.telephony.CallEventPoller
import ai.androclaw.feature.telephony.CallStateManager
import ai.androclaw.feature.telephony.SmsAutoReplyPipeline

// ── Telephony ─────────────────────────────────────────────────────────────────

val telephonyModule = module {
    single { CallStateManager() }
    single { AndroidSmsProvider(androidContext()) }
    single {
        val config = get<ai.androclaw.agent.OpenClawConfig>()
        ai.androclaw.tools.telephony.TelephonyTools(config).also { tools ->
            val provider = get<AndroidSmsProvider>()
            tools.smsProvider = { limit, from -> provider.readInbox(limit, from) }
        }
    }
    single { CallEventPoller(get(), get()) }
    single { SmsAutoReplyPipeline(get(), get(), get()) }
}

// ── HTTP client (shared) ──────────────────────────────────────────────────────

val networkModule = module {
    single {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(Logging) { level = LogLevel.INFO }
        }
    }
}

// ── Database ──────────────────────────────────────────────────────────────────

val databaseModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            OpenClawDatabase::class.java,
            "openclaw.db",
        ).fallbackToDestructiveMigration().build()
    }
    single { get<OpenClawDatabase>().memoryDao() }
    single { get<OpenClawDatabase>().messageDao() }
    single { get<OpenClawDatabase>().waMessageDao() }
    single { get<OpenClawDatabase>().outboxDao() }
}

// ── Config ────────────────────────────────────────────────────────────────────

val configModule = module {
    single { ConfigStore(androidContext()) }
    single {
        ConfigBridge.fromStore(
            store  = get(),
            dbPath = androidContext().getDatabasePath("openclaw.db").absolutePath,
        )
    }
    single { GoogleOAuthManager(androidContext(), get()) }
    single { FirebaseAuthManager(androidContext()) }
    single { FirebaseFirestoreManager() }
}

// ── Sync ──────────────────────────────────────────────────────────────────────

val syncModule = module {
    single { WaMessageSyncer(get(), get(), get()) }
    single {
        WhatsAppMessageProvider(get()).also { provider ->
            // Wire provider into the WhatsApp ReadMessagesTool at startup
            // The tool instance lives inside the agent's ToolRegistry
            // We expose the lambda here; it's set on the tool in agentModule
        }
    }
}

// ── Agent ─────────────────────────────────────────────────────────────────────

val agentModule = module {
    single {
        OpenClawMemoryStore(
            dbPath = androidContext().getDatabasePath("openclaw.db").absolutePath,
        ).also { store ->
            store.dao = get()
        }
    }
    single {
        val config     = get<ai.androclaw.agent.OpenClawConfig>()
        val memStore   = get<OpenClawMemoryStore>()
        val waProvider = WhatsAppMessageProvider(get())
        val deviceTools = DeviceTools(config, androidContext()).allTools()
        OpenClawAgent.build(config, memStore, waProvider, extraTools = deviceTools)
    }
    single { AgentService(get<ai.koog.agents.core.agent.AIAgent<String, String>>()) }
}

// ── Voice ─────────────────────────────────────────────────────────────────────

val voiceModule = module {
    single {
        VoiceManager(
            context = androidContext(),
            store   = get(),
        )
    }
}

// ── ViewModels ────────────────────────────────────────────────────────────────

val viewModelModule = module {
    viewModel { ChatViewModel(get()) }
    viewModel { VoiceViewModel(androidContext() as android.app.Application, get()) }
    viewModel { OnboardingViewModel(androidContext() as android.app.Application, get(), get()) }
    viewModel { SettingsViewModel(androidContext() as android.app.Application, get(), get()) }
    viewModel { AuthViewModel(androidContext() as android.app.Application, get(), get(), get()) }
}

