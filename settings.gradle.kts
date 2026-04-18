pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "talevia"

include(":core")
include(":platform-impls:video-ffmpeg-jvm")
include(":apps:desktop")
include(":apps:server")

// Android module is on disk under apps/android but not on the build graph until
// an Android SDK is installed and AGP is wired in. See docs/ANDROID_INTEGRATION.md.
// include(":apps:android")
