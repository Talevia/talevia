// Android module — kept off the build graph until Android SDK + AGP are wired in.
// See docs/ANDROID_INTEGRATION.md for activation steps.
//
// When activated, you'll want approximately:
//
// plugins {
//     id("com.android.application") version "8.7.3"
//     alias(libs.plugins.kotlin.android)
//     alias(libs.plugins.kotlin.compose)
// }
//
// android {
//     namespace = "io.talevia.android"
//     compileSdk = 35
//     defaultConfig {
//         applicationId = "io.talevia.android"
//         minSdk = 26
//         targetSdk = 35
//         versionCode = 1
//         versionName = "0.1.0"
//     }
//     buildFeatures { compose = true }
//     kotlinOptions { jvmTarget = "21" }
// }
//
// dependencies {
//     implementation(project(":core"))
//     // androidx.compose / activity-compose
//     // androidx.media3:media3-transformer / exoplayer
// }
