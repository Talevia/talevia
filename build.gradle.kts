plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    group = "io.talevia"
    version = "0.1.0-SNAPSHOT"
}

// ktlint runs on every Kotlin subproject; .editorconfig holds the rule config.
// `./gradlew ktlintCheck` gates CI, `./gradlew ktlintFormat` auto-fixes locally.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        // 1.5.0 is the first ktlint release with Kotlin 2.1 parser support.
        version.set("1.5.0")
        android.set(false)
        // Generated SQLDelight and KMP iOS stubs occasionally drift past
        // ktlint's eye; excluding build dirs keeps the check focused on
        // hand-written sources.
        filter {
            exclude { element -> element.file.path.contains("/build/") }
        }
    }
}
