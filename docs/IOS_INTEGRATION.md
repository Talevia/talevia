# iOS Integration

Talevia's `core` module exports a Kotlin/Native framework called **`TaleviaCore`**.
The Swift sources in `apps/ios/Talevia/` are a minimal scaffold that links the
framework and renders a one-screen SwiftUI app proving the bridge works. Full
AVFoundation-backed `VideoEngine` lands when we iterate on a real device.

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

- `TaleviaApp` + `ContentView`: one screen that touches `DefaultPermissionRuleset`
  from the Kotlin core so the symbol reachability is visible at runtime.
- `AVFoundationVideoEngine` (placeholder): an enum with a `isReachable()` stub.

## What it does not yet do

The Swift side doesn't yet build the full agent. Three things to unblock:

1. **Kotlin `fun interface` bridging** — `MediaPathResolver` exports as an ObjC
   protocol, but Swift doesn't see it under the short name. Likely needs a
   small SKIE config hint (`SuppressSkieWarning.NameCollision` is unrelated)
   or a typealias in an `iosMain` Kotlin file.
2. **Value-class ID types** (`AssetId`, `SessionId`, `ProjectId`, `ClipId`, ...)
   don't export to ObjC because `@JvmInline value class` is erased by
   Kotlin/Native. They surface as `Any` in Swift signatures. We need a tiny
   Kotlin factory (`fun newAssetId(value: String): AssetId`) on `iosMain` and
   downcasts on the Swift side — straightforward, just not written yet.
3. **`kotlin.time.Duration`** comes through as `Int64` nanoseconds (via
   `kotlinx.datetime` export). Ergonomic wrappers go in the same iosMain
   bridge file.

Once those three shims exist, the AVFoundation engine can be implemented as
originally drafted (AVMutableComposition + `AVAssetExportSession` + progress
polling).

---

## Known linker quirks

- `-lsqlite3` must be on `OTHER_LDFLAGS` because SQLDelight's native driver
  cinterops against libsqlite3 and Xcode doesn't auto-link it. Already set in
  `project.yml`.
- Xcode 26.x warnings about `CoreAudioTypes` / `UIUtilities` are benign (SDK
  metadata artefacts).
