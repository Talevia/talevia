# Android Integration

The Android app sources live under `apps/android/`, but the module is **not on
the Gradle build graph** because no Android SDK was installed during the M5
scaffold. Activating it requires three things that aren't part of the core
build today:

1. Android SDK + platform-tools (Android Studio installs both)
2. Android Gradle Plugin (AGP) configured for the project
3. The `core` KMP module exporting an `androidTarget()`

This doc covers the activation steps and what's already written.

---

## 1. Install the SDK

Easiest path is **Android Studio** (Hedgehog or newer). It installs the SDK
and platform-tools automatically; first launch will offer to set
`$ANDROID_HOME` (typically `~/Library/Android/sdk` on macOS).

Headless alternative:

```bash
brew install --cask android-commandlinetools
echo "export ANDROID_HOME=$HOME/Library/Android/sdk" >> ~/.zshrc
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

## 2. Add the Android target to `core`

In `core/build.gradle.kts`, alongside `iosArm64()` etc:

```kotlin
plugins {
    // ...
    id("com.android.library") version "8.7.3"
}

kotlin {
    androidTarget {
        compilations.all { kotlinOptions { jvmTarget = "21" } }
    }
    // ...
    sourceSets {
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
            implementation("androidx.media3:media3-transformer:1.4.1")
            implementation("androidx.media3:media3-effect:1.4.1")
        }
    }
}

android {
    namespace = "io.talevia.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
```

Add to `gradle/libs.versions.toml`:

```toml
sqldelight-driver-android = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
[plugins]
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
android-application = { id = "com.android.application", version = "8.7.3" }
android-library = { id = "com.android.library", version = "8.7.3" }
```

## 3. Activate the app module

Uncomment the `apps/android` line in `settings.gradle.kts`:

```kotlin
include(":apps:android")
```

Fill in `apps/android/build.gradle.kts` (it currently holds the recipe in
comments) and add Compose dependencies. Then:

```bash
./gradlew :apps:android:installDebug
```

## 4. What's already written

- **`MainActivity.kt`** — Compose `setContent` with a minimal import-by-path
  flow (mirror of the desktop app).
- **`AndroidAppContainer.kt`** — composition root: `AndroidSqliteDriver`,
  `Media3VideoEngine`, the same `ToolRegistry` wiring as desktop/iOS.
- **`Media3VideoEngine.kt`** — `VideoEngine` over `Transformer` +
  `EditedMediaItemSequence`. Probes via `MediaMetadataRetriever`, exports
  via Media3 `Transformer.start()`.
- **`AndroidManifest.xml`**, basic theme.

## 5. Workarounds in this scaffold

- `Media3VideoEngine.render` reports only Started / Completed / Failed —
  Media3 doesn't expose a frame-level progress callback like ffmpeg's
  `-progress pipe:2`. Mid-export percentage requires polling
  `Transformer.getProgress()` from a side coroutine; that's an obvious
  follow-up once we run on a real device.
- Photo Picker (`ActivityResultContracts.PickVisualMedia`) is the
  user-facing import path on Android, but the scaffold still asks for a raw
  file path because we don't have a `MediaPathResolver` abstraction yet.
  Same workaround as desktop/iOS.
- Sources have **never been compiled** — they need at minimum AGP setup +
  AndroidX Compose dependencies. Type signatures track Media3 1.4.x and
  AndroidX Compose 1.7.x conventions.
