plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// ── Read local.properties (never committed to git) ────────────────────────────
import java.util.Properties
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}
fun localProp(key: String, fallback: String = "") =
    (localProps.getProperty(key) ?: System.getenv(key) ?: fallback)

android {
    namespace   = "ai.androclaw"
    compileSdk  = 35

    defaultConfig {
        applicationId = "ai.androclaw"
        minSdk        = 35
        targetSdk     = 35
        versionCode   = 1
        versionName   = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp { arg("room.schemaLocation", "$projectDir/schemas") }

        // ── BuildConfig fields (read from local.properties or env vars) ───────
        // LLM
        buildConfigField("String", "GOOGLE_GENAI_API_KEY",
            "\"${localProp("GOOGLE_GENAI_API_KEY")}\"")

        // AgentPhone
        buildConfigField("String", "AGENTPHONE_API_KEY",
            "\"${localProp("AGENTPHONE_API_KEY")}\"")

        // Voice
        buildConfigField("String", "DEEPGRAM_API_KEY",
            "\"${localProp("DEEPGRAM_API_KEY")}\"")
        buildConfigField("String", "CARTESIA_API_KEY",
            "\"${localProp("CARTESIA_API_KEY")}\"")

        // WhatsApp (Vonage Messages API)
        buildConfigField("String", "VONAGE_MSG_API_KEY",
            "\"${localProp("VONAGE_MSG_API_KEY")}\"")
        buildConfigField("String", "VONAGE_MSG_API_SECRET",
            "\"${localProp("VONAGE_MSG_API_SECRET")}\"")
        buildConfigField("String", "VONAGE_MSG_FROM_NUMBER",
            "\"${localProp("VONAGE_MSG_FROM_NUMBER", "14157386102")}\"")

        // Gateway authorization secret
        buildConfigField("String", "BRIDGE_SECRET",
            "\"${localProp("BRIDGE_SECRET")}\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            buildConfigField("String", "GATEWAY_BASE_URL",
                "\"${localProp("GATEWAY_BASE_URL_DEBUG", "https://androclaw-wa-webhook.onrender.com")}\"")
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "GATEWAY_BASE_URL",
                "\"${localProp("GATEWAY_BASE_URL_RELEASE", "https://androclaw-wa-webhook.onrender.com")}\"")        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose    = true
        buildConfig = true
    }


}

dependencies {
    // ── agent-core module ───────────────────────────────────────────────
    implementation(project(":agent-core"))

    // ── Compose ─────────────────────────────────────────────────────────
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.prev)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // ── Lifecycle ────────────────────────────────────────────────────────
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)

    // ── Room ─────────────────────────────────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── DataStore (config / API keys) ─────────────────────────────────
    implementation(libs.datastore.prefs)
    implementation(libs.security.crypto)

    // ── WorkManager (background agent tasks) ─────────────────────────
    implementation(libs.work.runtime)

    // ── DI ────────────────────────────────────────────────────────────
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // ── Serialization ─────────────────────────────────────────────────
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines.android)

    // ── Firebase ──────────────────────────────────────────────────────
    val firebaseBom = platform(libs.firebase.bom)
    implementation(firebaseBom)
    implementation(libs.firebase.fcm)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // ── Logging ───────────────────────────────────────────────────────
    implementation(libs.timber)
    implementation(libs.appcompat)
    // ── Google Auth (Sign-In + token refresh) ─────────────────────────────
    implementation(libs.google.auth)
    implementation(libs.google.auth.util)

    // ── Ktor Client (for VoiceManager WebSockets & HTTP) ──────────────────
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)

    // ── Testing ──────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

