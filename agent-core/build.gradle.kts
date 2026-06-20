plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }

    // Future targets (uncomment when ready):
    // iosArm64(); iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Koog stable core — tools, agents, graph strategies, memory, persistence
                api(libs.koog.agents)
                // Koog beta additions — MCP (SSE), A2A, long-term memory / RAG
                api(libs.koog.additions)

                // Networking (MCP SSE transport, Daraja API, Vonage, Deepgram)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content)
                implementation(libs.ktor.serialization.json)
                implementation(libs.ktor.client.websockets)

                // Serialization
                implementation(libs.kotlinx.serialization)

                // Coroutines
                implementation(libs.kotlinx.coroutines.android)
            }
        }

        val androidMain by getting {
            dependencies {
                // OkHttp engine on Android
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.client.logging)
                implementation(libs.timber)
                implementation("ai.koog:prompt-executor-google-client-android:1.0.0-beta")
                implementation("ai.koog:prompt-executor-model-android:1.0.0")
                implementation("ai.koog:prompt-executor-clients-android:1.0.0")
                implementation("ai.koog:prompt-model-android:1.0.0")
                implementation("ai.koog:prompt-llm-android:1.0.0")
                implementation("ai.koog:http-client-core-android:1.0.0")
                implementation("ai.koog:utils-android:1.0.0")
                compileOnly(mapOf("name" to "google-client-runtime"))
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "ai.androclaw.core"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}






