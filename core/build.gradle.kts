plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)

    jvm()

    iosArm64()
    iosSimulatorArm64()

    // Android target 在 Milestone 5 引入（需要 Android Gradle Plugin + SDK）

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kermit)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.driver.sqlite)
            implementation(libs.ktor.client.cio)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
            implementation(libs.ktor.client.darwin)
        }
    }
}

sqldelight {
    databases {
        create("TaleviaDb") {
            packageName.set("io.talevia.core.db")
        }
    }
}
