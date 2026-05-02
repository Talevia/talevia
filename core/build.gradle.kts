plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.skie)
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.talevia.core"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)

    jvm()
    androidTarget()

    // iosX64 covers Intel Mac simulators; iosSimulatorArm64 covers Apple Silicon
    // Mac simulators; iosArm64 is the real-device slice.
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "TaleviaCore"
            isStatic = true
            export(libs.kotlinx.coroutines.core)
            export(libs.kotlinx.datetime)
        }
    }

    // Android target 在 Milestone 5 引入（需要 Android Gradle Plugin + SDK）

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kermit)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
            implementation(libs.okio.fakefilesystem)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.driver.sqlite)
            implementation(libs.ktor.client.cio)
            // OAuth loopback callback server for `openai-codex` provider login
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
            implementation(libs.ktor.client.darwin)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
            implementation(libs.ktor.client.cio)
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

// M6 §5.7 #3 — `./gradlew :core:bench` unified entry for the wall-time +
// memory regression suite under `core/src/jvmTest/.../bench/` (plus the
// outlier `BusEventPublishBenchmark` that lives next to its bus package).
//
// Why a separate task rather than letting `:core:jvmTest` cover them: the
// benchmarks print `[bench] <name> elapsed=<...> softBudget=<...>` lines
// that the perf-baseline lane in `docs/perf/baseline.txt` consumes
// directly. A scoped task lets CI run *only* the bench suite (faster
// feedback, distinct artifact), keeps the printlns out of the main test
// log, and gives `m6-benchmark-ci-gate` a stable grep target.
//
// Soft-policy: the task runs every benchmark and prints; current
// benchmarks intentionally don't `assertTrue` against absolute wall-time
// (cycle-2 budget — see e.g. `AgentLoopBenchmark` kdoc). Regressions
// surface as printed `over` markers a reviewer eyeballs against the
// committed baseline. A future cycle may tighten this to "values within
// 2× baseline" strict assertions if perf instability proves to be the
// dominant signal.
tasks.register<Test>("bench") {
    group = "verification"
    description = "Runs core's *Benchmark.kt wall-time + memory regression suite — subset of " +
        ":core:jvmTest scoped to the bench/ package + BusEventPublishBenchmark. Prints " +
        "`[bench]` lines a reviewer compares against docs/perf/baseline.txt."

    // Reuse jvmTest's compiled classes + classpath. Avoids re-running the
    // full KMP test compilation; the task picks up whatever jvmTest just
    // produced (or compiles on demand via dependsOn).
    val jvmTest = tasks.named<Test>("jvmTest").get()
    dependsOn(tasks.named("compileTestKotlinJvm"))
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath

    filter {
        includeTestsMatching("io.talevia.core.bench.*")
        includeTestsMatching("io.talevia.core.bus.BusEventPublishBenchmark")
    }

    testLogging {
        showStandardStreams = true
    }
}
