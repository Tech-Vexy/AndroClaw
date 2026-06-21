# OpenClaw ProGuard rules

# ── Kotlin serialization ──────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *** INSTANCE; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Koog agent tool descriptors ───────────────────────────────────────────────
-keep class ai.koog.agents.** { *; }
-keep class ai.openclaw.tools.** { *; }
-keep class ai.openclaw.agent.** { *; }
-keepclassmembers class * extends ai.koog.agents.core.tools.Tool {
    public *;
}

# ── Ktor ──────────────────────────────────────────────────────────────────────
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }

# ── Koin ──────────────────────────────────────────────────────────────────────
-keep class org.koin.** { *; }
-keepclassmembers class * {
    @org.koin.core.annotation.* <methods>;
}

# ── Firebase ──────────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# ── OkHttp (used by Ktor on Android) ─────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Timber ────────────────────────────────────────────────────────────────────
-dontwarn org.slf4j.**

# ── General ───────────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions

# ── WorkManager workers (instantiated by reflection) ─────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── Suppress missing OpenTelemetry / AutoValue warnings ─────────────────────
-dontwarn com.google.auto.value.AutoValue$CopyAnnotations
-dontwarn io.opentelemetry.api.incubator.metrics.ExtendedDoubleHistogram
-dontwarn io.opentelemetry.api.incubator.metrics.ExtendedDoubleHistogramBuilder
-dontwarn io.opentelemetry.api.incubator.metrics.ExtendedLongCounter
-dontwarn io.opentelemetry.api.incubator.metrics.ExtendedLongCounterBuilder
