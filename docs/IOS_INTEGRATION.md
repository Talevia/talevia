# iOS Integration

Talevia's `core` module exports a Kotlin/Native framework called **`TaleviaCore`**.
The Swift sources in `apps/ios/Talevia/` are designed to be assembled into an Xcode
project that links against that framework.

This doc covers the manual steps to wire Xcode to the framework. (No `.xcodeproj`
is checked in yet — generate one with `xcodegen`/Tuist or create one in Xcode.)

---

## 1. Build the framework

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew :core:linkDebugFrameworkIosSimulatorArm64
# or for device:
DEVELOPER_DIR=… ./gradlew :core:linkDebugFrameworkIosArm64
```

Output:
- Simulator: `core/build/bin/iosSimulatorArm64/debugFramework/TaleviaCore.framework`
- Device:    `core/build/bin/iosArm64/debugFramework/TaleviaCore.framework`

The framework is **static** (`isStatic = true`) and re-exports
`kotlinx-coroutines-core` + `kotlinx-datetime` so SKIE's Swift bridges work without
Swift code having to depend on Kotlin packages explicitly.

> **Workaround:** the system uses `xcode-select -p = /Library/Developer/CommandLineTools`
> by default. The KMP linker needs the full Xcode app, so set `DEVELOPER_DIR` per-build
> (or run `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` once).

## 2. Generate or create the Xcode project

Easiest path is `xcodegen`:

```yaml
# apps/ios/project.yml
name: Talevia
options:
  bundleIdPrefix: io.talevia
targets:
  Talevia:
    type: application
    platform: iOS
    deploymentTarget: 17.0
    sources:
      - Talevia
    info:
      path: Talevia/Resources/Info.plist
    settings:
      base:
        FRAMEWORK_SEARCH_PATHS: $(SRCROOT)/../../core/build/bin/iosSimulatorArm64/debugFramework
        OTHER_LDFLAGS: -framework TaleviaCore
```

Then `cd apps/ios && xcodegen generate && open Talevia.xcodeproj`.

## 3. Build phase: re-link Talevia framework on every Xcode build

Add a "Run Script" phase before "Compile Sources":

```bash
cd "$SRCROOT/../.."
./gradlew :core:linkDebugFrameworkIosSimulatorArm64
```

(or use the Run Script phase produced by the `embedAndSignAppleFrameworkForXcode`
KMP task — recommended once you switch to a checked-in xcconfig pipeline.)

## 4. Things that need attention before first run

- **`AVFoundationVideoEngine.swift`** uses SKIE-bridged types
  (`SkieKotlinFlow`, `onEnum(of:)`, `KotlinByteArray`). Swift compile errors in
  this file usually mean a SKIE version mismatch — the helper names changed
  between 0.9.x and 0.10.x. Adjust to whatever symbols `import TaleviaCore`
  exposes in Xcode's autocomplete.
- **`Tool.description` collision** — Kotlin's `Tool.description: String` clashes
  with `NSObject.description`. SKIE renames it to `description_` in Swift; we
  may want to rename the Kotlin property to `summary` later to avoid the
  warning.
- **In-memory SQLite only** — `TaleviaDatabaseFactory.createInMemoryDriver()`
  is the one path wired so far. Persistent on-device storage (proper file
  driver + Documents directory) is a later milestone.
- **Photos library access** — `TaleviaApp` currently expects file paths;
  PHPicker integration replaces the `TextField` import path in a follow-up.

## 5. Known workarounds (M3)

- Swift sources are **not validated by CI** — they only compile when added to an
  Xcode project. Type signatures here track SKIE 0.10.4 conventions but may
  drift if SKIE upgrades.
- The desktop demo's `AssetId == file path` shortcut is preserved on iOS so
  `AVFoundationVideoEngine` can dereference clips. A proper `MediaPathResolver`
  abstraction is what M5 / cross-platform uniformity will force.
