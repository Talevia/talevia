plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
}

allprojects {
    group = "io.talevia"
    version = "0.1.0-SNAPSHOT"
}
