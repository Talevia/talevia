plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "io.talevia.cli.MainKt"
    applicationName = "talevia"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":platform-impls:video-ffmpeg-jvm"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.okio)
    implementation(libs.sqldelight.driver.sqlite)
    implementation(libs.kermit)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("com.github.ajalt.mordant:mordant:2.7.2")
    implementation("org.jline:jline:3.26.3")
    // Bind SLF4J to a no-op provider so transitive Ktor / kotlinx-coroutines
    // pulls of slf4j-api don't dump "No SLF4J providers were found" on every
    // CLI invocation. The CLI uses `core.logging.Logger` for its own logging;
    // library-internal logs are intentionally silenced — switch to
    // `logback-classic` (as the server does) if you need them.
    implementation(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
