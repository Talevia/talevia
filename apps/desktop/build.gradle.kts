plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.javafx)
}

kotlin {
    jvmToolchain(21)
}

// JavaFX modules needed for the in-app video preview (`PreviewPanel`):
// - controls + swing for JFXPanel / Scene / Group
// - media for Media / MediaPlayer / MediaView
// The javafx plugin auto-selects the current-host native classifier.
javafx {
    version = libs.versions.openjfx.get()
    modules = listOf("javafx.controls", "javafx.media", "javafx.swing")
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

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "io.talevia.desktop.MainKt"
        nativeDistributions {
            packageName = "Talevia"
            packageVersion = "0.1.0"
            // macOS Launch Services: declare the `.talevia` directory-package UTI so
            // Finder launches Talevia on double-click instead of descending into the
            // bundle as a plain folder. Pairs with FileProjectStore.BUNDLE_EXTENSION.
            macOS {
                bundleID = "io.talevia.Talevia"
                infoPlist {
                    // Raw-XML plist fragment; parsed for well-formedness +
                    // required keys by `MacOsInfoPlistExtraXmlTest` so a typo
                    // fails unit tests instead of silently shipping a Finder
                    // double-click that does nothing.
                    extraKeysRawXml =
                        file("src/main/resources/macos-info-plist-extra.xml").readText()
                }
            }
        }
    }
}
