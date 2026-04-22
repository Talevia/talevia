# Android Integration

The Android module is **on the build graph** (`:apps:android`) and produces a
debug APK via `./gradlew :apps:android:assembleDebug`. The core KMP module has
an `androidTarget()` and a library-artifact build variant that the app depends
on.

---

## Prerequisites

1. **Android SDK** — typically provisioned by Android Studio (default path
   `~/Library/Android/sdk` on macOS). At minimum:
     - `platforms/android-36` (matches `compileSdk = 36`)
     - `build-tools/36.x` or newer
2. **Accepted licenses** — `~/Library/Android/sdk/licenses/android-sdk-license`
   must exist. Accept with `yes | sdkmanager --licenses` if missing.
3. **`local.properties`** pointing at the SDK (this repo's file is gitignored):
   ```
   sdk.dir=/Users/<you>/Library/Android/sdk
   ```

---

## Build

```bash
./gradlew :apps:android:assembleDebug
# APK: apps/android/build/outputs/apk/debug/android-debug.apk
```

Install to a connected device or emulator:

```bash
adb install apps/android/build/outputs/apk/debug/android-debug.apk
```

---

## Layout

- **`core/src/androidMain/`** — currently just a stub `AndroidManifest.xml`.
  Android-specific `actual` implementations for any `expect` declarations go
  here (none needed so far).
- **`apps/android/src/main/kotlin/io/talevia/android/`**:
  - `MainActivity.kt` — Compose UI with a path-input Import button, mirroring
    the desktop + iOS demos.
  - `AndroidAppContainer.kt` — composition root, instantiates the SQLDelight
    `AndroidSqliteDriver`, the media3-backed `VideoEngine`, and the full tool
    registry.
  - `Media3VideoEngine.kt` — `VideoEngine` over `androidx.media3`'s
    `Transformer` + `EditedMediaItemSequence`. Renders concat-style video,
    polls `Transformer.getProgress` at ~10 Hz for intermediate
    `RenderProgress.Frames` events.

---

## Versions (pinned in `gradle/libs.versions.toml`)

| Piece | Version |
|-------|---------|
| Android Gradle Plugin | 8.8.0 |
| `compileSdk` | 36 |
| `minSdk` | 26 |
| `targetSdk` | 36 |
| Media3 | 1.5.1 |
| Compose BOM | 2024.12.01 |
| activity-compose | 1.9.3 |

---

## Known limitations

- `Media3VideoEngine` currently handles single-track video concat via
  `EditedMediaItemSequence`. Filters / transitions / multi-track audio mixing
  are data-modelled on the timeline but not yet rendered by the engine.
- Project bundles live under `<context.filesDir>/projects/<id>/` (set by
  `AndroidAppContainer`); the recents registry sits at
  `<context.filesDir>/recents.json`. This is the app sandbox — the user can't
  pick an arbitrary external path until SAF (`Storage Access Framework`)
  integration lands (see `docs/BACKLOG.md::bundle-mobile-document-picker`).
  AIGC + `import_media(copy_into_bundle=true)` outputs land at
  `<bundleRoot>/media/<assetId>.<ext>` and travel with the bundle if it's
  pulled out via `adb pull` or shared via Android sharing.
- A few tools (`ImportMediaTool` reference path, `ApplyLutTool`,
  `ExtractFrameTool`) still consume the legacy `MediaStorage` surface for
  `MediaSource.Platform` (`content://` URIs from PhotoPicker) and proxy
  generation; the container keeps an `InMemoryMediaStorage` placeholder for
  them. Migration to `BundleMediaPathResolver` + `Project.assets` is queued
  in `docs/BACKLOG.md::delete-file-media-storage-interface`.
- The app has never been run through end-to-end testing on a physical device;
  `assembleDebug` is the furthest the CI path goes today.
