## 2026-04-23 — .talevia bundle extension + macOS Launch Services registration (VISION §5.4 / packaging)

**Context.** The project bundle format (see CLAUDE.md "Project bundle format") is a directory on disk, but macOS Finder only treats a directory as an opaque document bundle (package) when three things line up: an extension Launch Services recognises, an exported UTI with `com.apple.package` conformance, and a `CFBundleDocumentTypes` entry claiming that UTI as `LSTypeIsPackage`. Without those, `open foo.talevia` opens the folder in Finder instead of routing the path to Talevia. Rubric delta §5.4 / packaging: bundle portability "部分 → 部分+" (the Core storage is portable, but desktop UX to double-click a bundle was missing).

Implementation constraints:
- `FileProjectStore.openAt(path)` currently required the path to *be* the bundle directory (the one holding `talevia.json`). Users typing `open ~/Movies/my-edit` (no extension) when the bundle lives at `~/Movies/my-edit.talevia` failed with `IllegalStateException: talevia.json not found`. This is brittle on macOS where Launch Services hands the tool a path **with** the `.talevia` suffix — but also hurts CLI users typing bare names. Needed bidirectional tolerance.

**Decision.**
1. **Convention: `.talevia` is the canonical bundle directory extension.** Landed as `FileProjectStore.Companion.BUNDLE_EXTENSION = "talevia"`. Existing bundles without the extension still load (see point 2 below); new bundles created via Finder "Save As" on macOS inherit the extension from the document-type registration.
2. **`FileProjectStore.openAt` auto-promotes bare paths when a `.talevia` sibling exists.** `resolveBundlePath(path)`: if `path/talevia.json` exists → use `path` as-is (bare-bundle callers and internal machinery keep working). Otherwise, if `path` doesn't end with `.talevia`, probe `<path>.talevia/talevia.json` — present → use the suffixed sibling. Neither exists → fall through to the current "bundle has no `talevia.json`" failure path. This is a strict add (every pre-existing call site keeps its semantics); no caller needs to know about the extension convention.
3. **Desktop app declares `io.talevia.project` UTI in its Info.plist.** Added `macOS { bundleID = "io.talevia.Talevia"; infoPlist { extraKeysRawXml = <…> } }` to `apps/desktop/build.gradle.kts`'s `nativeDistributions {}` block. The XML declares one `UTExportedTypeDeclarations` entry conforming to `com.apple.package` + `public.composite-content`, keyed on `.talevia` filename extension, plus one `CFBundleDocumentTypes` entry with `LSTypeIsPackage=true`, `LSHandlerRank=Owner`, `CFBundleTypeRole=Editor`, `CFBundleTypeName="Talevia Project"`.

On `./gradlew :apps:desktop:packageDmg`, Compose Desktop's jpackage wrapper merges `extraKeysRawXml` into the generated `Info.plist`; the installed app then registers with Launch Services at first launch, and double-clicking a `.talevia` folder in Finder routes to Talevia's `open` pipeline (existing CLI-style arg parsing in `apps/desktop/src/.../Main.kt` already takes a path argument).

**Alternatives considered.**
- *Require all bundle paths to end in `.talevia`.* Rejected because it breaks every existing test fixture and CLI call (`talevia open /tmp/foo` would fail even when `/tmp/foo/talevia.json` exists). The auto-promote rule means legacy and new bundles both work without a migration step.
- *Auto-rename bare directories to `<name>.talevia` on first open.* Rejected because renaming a directory behind the user's back has git / backup consequences we can't predict. We only *accept* both forms; we never mutate the user's path.
- *Generate the whole `Info.plist` ourselves instead of using `extraKeysRawXml`.* Compose Desktop's jpackage pipeline already generates the base plist (CFBundleIdentifier, executables, etc.). Overriding the entire plist would require maintaining parity with upstream whenever the packager's defaults change. `extraKeysRawXml` is the documented Compose Desktop injection point for exactly this use case.
- *UTI conformance on `public.folder`.* Rejected — `public.folder` means "just a folder you browsed into"; `com.apple.package` means "opaque document that looks like a folder under the hood". The latter is what Launch Services uses to decide whether to route on double-click.

**Coverage.**
- 5 new test cases in `core/src/commonTest/kotlin/io/talevia/core/domain/FileProjectStoreTest.kt`:
  - `openAtAcceptsPathWithTaleviaExtension` — bundle created with `.talevia` suffix resolves when opened with the full suffixed path.
  - `openAtAcceptsBarePathWhenBundleDirIsBare` — legacy bare-bundle path (no extension) still works.
  - `openAtAutoPromotesBarePathWhenDotTaleviaVariantExists` — bundle lives at `<name>.talevia`, user opens `<name>`, promotion kicks in.
  - `openAtPrefersBareWhenBothVariantsExist` — if both `<name>` and `<name>.talevia` are valid bundles, bare wins (safer default: never guess a sibling when the exact path resolves).
  - `openAtDoesNotStripExtensionWhenBarePathExists` — `<name>.talevia` as the *only* bundle must resolve via promotion, not via an extension-stripping reverse rule (the tool only ever *adds* `.talevia` when probing; never *removes* it).
- `:core:jvmTest` green. `:core:compileKotlinIosSimulatorArm64` + `:apps:android:assembleDebug` + `:apps:desktop:assemble` green (the XML lives only in `apps/desktop/build.gradle.kts`, so mobile + iOS compile is a regression check that this didn't leak a platform dependency into `core`).
- `ktlintCheck` green.

**Registration.** No new `Tool<I, O>` — this cycle changes `FileProjectStore` behaviour (consumed by every AppContainer via the existing `ProjectStore` binding) + one build file. Therefore no per-container registration sweep required. The 5-container registration tax does not apply.
