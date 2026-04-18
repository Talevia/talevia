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

// Apps & platform-impls 模块在后续 Milestone 加入：
// include(":apps:desktop", ":apps:server", ":apps:android", ":platform-impls:video-ffmpeg-jvm")
