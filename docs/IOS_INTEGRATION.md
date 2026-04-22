# iOS Integration

Talevia's `core` module exports a Kotlin/Native framework called **`TaleviaCore`**.
The Swift sources in `apps/ios/Talevia/` link the framework and render a SwiftUI
app. The iOS `VideoEngine` is implemented in Swift
(`apps/ios/Talevia/Platform/AVFoundationVideoEngine.swift`) against AVFoundation,
with a Kotlin composition root (`apps/ios/Talevia/Platform/AppContainer.swift`)
wiring up SQLDelight, stores, tools, and the engine.

The Xcode project is **not checked in** — it's generated from
`apps/ios/project.yml` with `xcodegen`.

---

## One-time setup

```bash
brew install xcodegen                # if not already installed
# (Xcode.app required; CommandLineTools alone is not enough for KMP linking.)
```

If `xcode-select -p` points at `CommandLineTools`, either:
- run `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` once, or
- export `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` per shell.

The second option is what the xcodegen pre-gen hook already uses.

---

## Build the iOS app

```bash
cd apps/ios
xcodegen generate
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild -project Talevia.xcodeproj -scheme Talevia \
             -sdk iphonesimulator \
             -destination 'generic/platform=iOS Simulator' build
```

Xcodegen's `preGenCommand` runs `./gradlew :core:linkDebugFrameworkIosSimulatorArm64
:core:linkDebugFrameworkIosX64` so the per-arch `TaleviaCore.framework` exists
before Xcode compiles Swift. The project sets per-arch
`FRAMEWORK_SEARCH_PATHS` pointing at the right Kotlin/Native output.

The build produces a ready-to-install `Talevia.app` in the derived-data directory.

---

## What the Swift scaffold covers

- `TaleviaApp` + `ContentView`: status-screen scaffold that exercises
  `AppContainer.shared` on launch so the Kotlin graph (driver → db → stores →
  tool registry → engine) lights up and the linker keeps those symbols live.
- `AppContainer`: composition root — file-backed SQLDelight driver
  (`Documents/talevia.db`, persists Sessions / Messages / Parts; switched
  from in-memory in `baad43f`), `RecentsRegistry` at
  `Documents/recents.json`, `FileProjectStore` rooted at
  `Documents/projects/`, `FileBundleBlobWriter` for AIGC outputs,
  `AVFoundationVideoEngine`, `DefaultPermissionService`, and the same
  tools the Android app registers (Import / AddClip / SplitClip / Export /
  RevertTimeline). Project bundles are visible to the user via the iOS
  Files app; AirDrop / iCloud sync of the bundle directory works for
  cross-device handoff.
- `AVFoundationVideoEngine`: native `VideoEngine` implementation.
  - `probe(source:)` → `AVURLAsset.load(.duration, .tracks)` + per-track video /
    audio metadata extraction. Portrait detection via preferredTransform so
    reported resolution matches Media3 conventions.
  - `thumbnail(asset:source:time:)` → `AVAssetImageGenerator.copyCGImage`
    (zero tolerance) + `CGImageDestination` → PNG bytes.
  - `render(timeline:output:)` → `AVMutableComposition` with one video track
    plus a lazily-added audio track, fed clips via `Timeline.toIosVideoPlan()`
    (flat DTO projected by `iosMain`). Export via
    `AVAssetExportSession.exportAsynchronously(...)` with preset picked by
    output height. Progress pushed through `SwiftRenderFlowAdapter`.

Filter / transition / subtitle passes are no-ops with TODO comments, matching
the Media3 engine's scope on Android.

---

## SKIE bridging notes

Kotlin-to-Swift translation via [SKIE](https://skie.touchlab.co) is mostly
automatic, but a few Kotlin features don't survive the ObjC boundary and need
hand-written shims in `core/src/iosMain/kotlin/io/talevia/core/IosBridges.kt`:

1. **`@JvmInline value class` IDs** (`AssetId`, `ClipId`, `ProjectId`, ...)
   are erased to `Any` at the Kotlin/Native → ObjC boundary. `IosBridges.kt`
   ships distinct factory + unwrapper pairs per ID type
   (`assetId(value:) / assetIdRaw(id:)` etc.) so SKIE generates unambiguous
   Swift signatures. In Swift: `IosBridgesKt.assetId(value: "xyz")`.

2. **`kotlin.time.Duration`** exports as `Int64` nanoseconds in ObjC. Use the
   helpers `IosBridgesKt.durationOfSeconds(seconds:)` and
   `durationToSeconds(d:) / durationToMillis(d:)` to round-trip.

3. **`fun interface MediaPathResolver`** exports cleanly as an ObjC protocol
   (no shim needed). SKIE auto-bridges its `suspend resolve(assetId:)` to
   Swift's `async throws`.

4. **Flat DTO for Swift render** — `Clip` is a Kotlin sealed hierarchy with
   embedded value-class IDs, both of which are painful to pattern-match from
   Swift. `Timeline.toIosVideoPlan()` (iosMain extension) projects video
   tracks + video clips into a flat `IosVideoClipPlan` list of primitives
   (asset id raw string + 4 seconds doubles). The Swift render path consumes
   this DTO, not the sealed `Clip` hierarchy — Timeline authority stays in
   Core (architecture rule #2), the projection is derived per render call.

5. **`Flow<RenderProgress>` returned from Swift** — `SkieSwiftFlow` has no
   public constructor, so Swift can't fabricate one directly.
   `SwiftRenderFlowAdapter` in iosMain owns a `MutableSharedFlow` and exposes
   `tryEmit` / `close` to Swift. Swift returns `adapter.asFlow()` and SKIE
   auto-bridges to `SkieSwiftFlow<any RenderProgress>`. `replay = 64` is
   critical: the Swift side emits `Started` synchronously before the Kotlin
   caller starts collecting, so without replay the opening event is dropped.

6. **`MediaStorage.import(source, probe)`** — legacy surface kept alive for
   tools that haven't migrated to `Project.assets` yet (see
   `docs/BACKLOG.md::delete-file-media-storage-interface`). The `probe`
   parameter is `KotlinSuspendFunction1` and is painful to construct from
   Swift. The iosMain helper `importWithKnownMetadata(storage:source:metadata:)`
   lets callers pre-probe via the engine and hand in metadata directly —
   used by the XCTest smoke target. New code should write through
   `BundleBlobWriter` + `ProjectStore.mutate { it.copy(assets = ...) }`
   instead and skip `MediaStorage` entirely.

7. **SKIE protocol conformance surfaces both an async shim and a
   callback-style requirement**, e.g. `VideoEngine.probe` appears as
   `func probe(source:) async throws -> MediaMetadata` (extension, for
   callers) and `func __probe(source:completionHandler:)` (protocol
   requirement, for implementers). Swift implementations conform via the
   underscored callback form; Swift callers use the async form.

---

## Testing

The `TaleviaTests` XCTest bundle covers the engine wiring end-to-end:

```bash
cd apps/ios
xcodegen generate
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild test -project Talevia.xcodeproj -scheme Talevia \
                  -destination 'platform=iOS Simulator,name=iPhone 17'
```

`AVFoundationVideoEngineSmokeTest` synthesizes two 1-second solid-colour MP4s
via `AVAssetWriter` (no checked-in binary fixtures), imports them through
`InMemoryMediaStorage` (still used by this legacy smoke; see
`docs/BACKLOG.md::delete-file-media-storage-interface` for the migration to
`Project.assets`), runs `engine.render(...)`, and asserts Started +
Completed events arrived plus the output file is non-empty. Swap the
simulator name to whatever `xcrun simctl list devices available` reports.

---

## Known linker quirks

- `-lsqlite3` must be on `OTHER_LDFLAGS` because SQLDelight's native driver
  cinterops against libsqlite3 and Xcode doesn't auto-link it. Already set in
  `project.yml` for both the app and the test bundle.
- Xcode 26.x warnings about `CoreAudioTypes` / `UIUtilities` are benign (SDK
  metadata artefacts).
