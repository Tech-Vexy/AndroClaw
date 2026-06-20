pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Koog nightly builds (use stable once 1.0.0 hits Maven Central for Android target)
        maven("https://packages.jetbrains.team/maven/p/grazi/grazie-platform-public")
        flatDir {
            dirs("agent-core/libs")
        }
    }
}

rootProject.name = "OpenClaw"
include(":app")
include(":agent-core")

