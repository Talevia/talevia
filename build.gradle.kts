plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.compose) apply false
}

allprojects {
    group = "io.talevia"
    version = "0.1.0-SNAPSHOT"
}
