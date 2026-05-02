# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Talevia** is an AI-driven cross-platform video editor. Users describe edits in natural language (cut/stitch/filter/subtitle/transition), the agent dispatches tools against a canonical Timeline, and each platform renders with its native engine.

Targets: **iOS · Android · Desktop (macOS/Windows/Linux) · Server**. Agent logic runs primarily on-device; the server is an optional headless deployment.

Read first: `docs/VISION.md` (north star — where we're going and why). This file covers current shape + operational rules (module layout, architecture rules, anti-requirements, build/run). For autonomous "find-gap → fill-gap" loops, use the rubric in VISION §5.

## Build & run

JDK 21 must be reachable. `brew install openjdk@21`, then either `sudo ln -sfn /usr/local/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk` or set `JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.

| Task | Command |
|---|---|
| Compile core (JVM) | `./gradlew :core:compileKotlinJvm` |
| Compile core (iOS sim) | `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :core:compileKotlinIosSimulatorArm64` |
| Link iOS framework | `DEVELOPER_DIR=... ./gradlew :core:linkDebugFrameworkIosX64` (or `IosSimulatorArm64`, `IosArm64`) |
| Run jvmTest | `./gradlew :core:jvmTest` |
| Run one test class | `./gradlew :core:jvmTest --tests 'io.talevia.core.agent.AgentLoopTest'` |
| FFmpeg E2E (real ffmpeg on PATH required) | `./gradlew :platform-impls:video-ffmpeg-jvm:test` |
| Desktop app | `./gradlew :apps:desktop:run` |
| Server | `./gradlew :apps:server:run` (port 8080; reads `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` from env; `TALEVIA_PROJECTS_HOME=/some/dir` for new-project default home, `TALEVIA_RECENTS_PATH=/some/recents.json` for the per-machine project registry) |
| Server tests | `./gradlew :apps:server:test` |
| Android debug APK | `./gradlew :apps:android:assembleDebug` (requires Android SDK at `~/Library/Android/sdk` via `local.properties`) |
| iOS app | `cd apps/ios && xcodegen generate && open Talevia.xcodeproj` (⌘R). Pre-build phase runs gradle automatically. |
| iOS Swift compile (headless) | `cd apps/ios && xcodegen generate && xcodebuild build -project Talevia.xcodeproj -scheme Talevia -destination 'platform=iOS Simulator,name=iPhone 16e' CODE_SIGNING_ALLOWED=NO` (use `xcrun simctl list devicetypes` to find a valid `name=` if iPhone 16e is gone). Catches Swift drift the framework-only `compileKotlinIosSimulatorArm64` misses; runs the gradle link as a build-phase script so it covers both. |
| Lint (all modules) | `./gradlew ktlintCheck` (auto-fix: `ktlintFormat`). Rule profile in `.editorconfig`: hygiene-only (unused imports, final newline, import order). |
| Every target + every test | `./gradlew :core:jvmTest :platform-impls:video-ffmpeg-jvm:test :apps:server:test :apps:desktop:assemble :core:compileKotlinIosSimulatorArm64 :apps:android:assembleDebug` followed by the iOS Swift compile row above (xcodebuild). The gradle invocation alone validates only the KMP iOS framework — Swift consumer drift (`apps/ios/Talevia/**/*.swift`) is invisible without the xcodebuild step (cycles 136–149 accumulated 35+ silent Swift errors before the gap was caught). |

FFmpeg + ffprobe must be on PATH for `platform-impls/video-ffmpeg-jvm` tests (`brew install ffmpeg`).

## Module layout

```
core/                               KMP shared module (commonMain + jvmMain + iosMain + androidMain)
platform-impls/video-ffmpeg-jvm/    JVM VideoEngine via system ffmpeg shell-out
apps/
  desktop/                          Compose Desktop UI
  server/                           Ktor HTTP/SSE server
  ios/                              SwiftUI app, Xcode project generated from project.yml via xcodegen
  android/                          Compose Android app + Media3VideoEngine
```

Inside `core/src/commonMain/kotlin/io/talevia/core/`: `agent/`, `provider/` (Anthropic, OpenAI — hand-written SSE parsing), `tool/` (typed Tool + ToolRegistry + builtin video tools), `session/` (Session/Message/Part models + SqlDelightSessionStore), `domain/` (Timeline, Track, Clip, MediaAsset, Project, **`FileProjectStore`** + `RecentsRegistry` — the video-editor first-class state, persisted as on-disk project bundles via Okio), `compaction/`, `permission/`, `platform/` (VideoEngine / MediaPathResolver / `BundleMediaPathResolver` / `BundleBlobWriter` contracts), `bus/` (SharedFlow-based typed event bus).

## Architecture rules — enforce these

1. **`core/commonMain` depends on zero platform APIs.** No AVFoundation, Media3, FFmpeg, AWT, JavaFX, etc. Platform-specific work lives behind interfaces in `core.platform` and is injected at the composition root of each app.
2. **Timeline is owned by Core.** Tools mutate `Project.timeline` via `ProjectStore.mutate(...)` under a mutex. Platforms only *render* the timeline — they never hold a parallel "authoritative" copy. A UI that lets users drag clips must round-trip the drag through the Core, not mutate locally and notify later.
3. **Tools are typed (`Tool<I, O>`)** with a `KSerializer` for input/output plus a JSON Schema surface for the LLM. `RegisteredTool.dispatch(rawInput, ctx)` is the one cast-boundary; add new tools in `core/tool/builtin/` and register them in each `AppContainer`.
4. **Asset paths go through `MediaPathResolver`.** Do NOT treat `AssetId.value` as a filesystem path (there used to be a temporary hack doing this; it's gone). `ExportTool` constructs a per-project `BundleMediaPathResolver(project, bundleRoot)` and passes it as the optional `resolver` parameter on `engine.render(...)` / `renderClip(...)` so the engine resolves `MediaSource.BundleFile` relative to the loaded bundle and `MediaSource.File` to its absolute path. AIGC tools write generated bytes via `BundleBlobWriter` (lands at `<bundle>/media/<assetId>.<ext>`).
5. **Provider abstraction is SDK-agnostic.** Both Anthropic and OpenAI impls emit the same normalised `LlmEvent` stream. If you add a provider, translate its native events into `LlmEvent` — don't leak provider-specific types into Agent / Compactor / Tool code.
6. **No Effect.js patterns.** Even though OpenCode uses Service/Layer/Context, Kotlin has its own idioms. See Anti-requirements below.

## Anti-requirements — don't do these

Operational red lines. If a task seems to require any of these, stop and challenge per VISION §"发现不符":

- ❌ Effect.js-style Service/Layer/Context dependency management — Kotlin has its own idioms
- ❌ UI code inside the KMP shared module
- ❌ `core/commonMain` depending on any platform API (AVFoundation, Media3, FFmpeg, AWT, etc.)
- ❌ Video encoding/decoding inside Agent Core
- ❌ Electron / WebView for the desktop app (Compose Desktop is the choice)
- ❌ Forking OpenCode and translating it to Kotlin — treat OpenCode as behavioral spec only
- ❌ Designing for hypothetical future needs (multi-agent coordinator, IDE bridge, plugin marketplace, etc.) without a concrete driver
- ❌ Optimising for a single LLM provider at the cost of the provider abstraction

## Platform priority — 当前阶段

当下优先级（从高到低）：**Core (`core/`) > Mac CLI (`apps/cli`) > Mac desktop app (`apps/desktop`) > iOS app > Android app > Others (server 等)**。

- **Core 放在所有平台之前**：agent loop、provider 抽象、tool registry、session/compaction、domain model、permission、bus 等 KMP 共享逻辑的迭代高于任何单一平台的 UI/集成工作。上层平台缺的能力，多半来自 Core 缺口 —— 先补 Core，平台再消费。
- **Core gap-finding 的首选参照是 OpenCode**：`/Volumes/Code/CodingAgent/opencode`（见下方 "OpenCode as a 'runnable spec'" 章节的模块索引）是当前最成熟的行为参考。梳理 Core 任务时，把 OpenCode 对应模块当作行为 spec 对照，找出我们缺的机制（例：流式 tool 输出、compaction 细节、permission 粒度、bus 事件覆盖等）。只抄行为，不抄 Effect.js 结构。
- 在 Core 之后，平台顺序：Mac CLI 已经达到"相对完善可用"（VISION §5 rubric 各节均至少"部分"），**desktop GUI 现可主动扩**（user authorization 2026-05-02：M7 §4 GUI 工作 unblocked，desktop chat panel agent-step subscriber / `RenderableTimeline` UI 消费 / two-paths shared-source UI e2e 都从"opportunistic" 升为 "active"）。iOS / Android 仍只维持**不退化**（能编译、已有 E2E 测试继续通过），不主动扩新特性 —— 这两端的窗口由用户单独决定打开.
- **iOS / Android 的"不退化底线"已经到位**（2026-04 现状）：`Media3VideoEngine`（Android）、AVFoundation engine（iOS）、FFmpeg engine（JVM）在 **vignette / transitions / subtitles / LUT** 四类能力上已三平台对齐（见下方 "Known incomplete" 首条）。`Every target` gradle 命令显式把 `:apps:android:assembleDebug` 纳入，iOS 也有 xcodegen + framework link 路径。**底线定义**：Core 新增的渲染能力（新 filter 种类 / 新 transition / 新 overlay 基元）如果已经在 FFmpeg 引擎落地，默认要求 Android + iOS 同步对齐；只在 FFmpeg 上做单平台特性视同破坏底线。反向：**超出底线的主动扩展**（iOS 专属手势、Android 专属 UI 组件、Android 端独立的 AIGC 管线等）仍然不做 —— 有具体 driver 再动。
- "相对完善可用"的判断：当前优先级平台上 VISION §5 rubric 的每一节都至少达到"部分"及格线 —— source 层可用、工具集覆盖主要编辑意图、AIGC 产物可 pin、agent 能跑出可看初稿、专家能接管。
- Server 作为 CLI / desktop 的无头孪生同步演进，属于 "Others" —— 只在 CLI / desktop 需要其作为后端或测试目标时才推进。
- 跨平台抽象（`core/platform` 接口、`ToolRegistry` 注册等）**不得**因为 CLI / desktop 赶进度而省略 —— 红线仍是 CommonMain 零平台依赖。
- 任务梳理 / Gap-finding 顺序：① 对照 OpenCode 找 Core 缺口；② 再按 VISION §5 打分；③ 最后按上述平台顺序过滤候选项。

## OpenCode as a "runnable spec"

OpenCode lives at `/Volumes/Code/CodingAgent/opencode`. Treat it as **behavioral reference**, not code to port:
- Agent loop shape → `packages/opencode/src/session/prompt.ts:1305-1533`
- Provider protocol semantics → `packages/opencode/src/provider/provider.ts`, `session/llm.ts`
- Tool dispatch + streaming → `tool/tool.ts` + `session/processor.ts`
- Session/Message/Part → `session/message-v2.ts`
- Compaction → `session/compaction.ts`
- Permission → `permission/index.ts`
- Bus → `bus/index.ts`

Ignore OpenCode's Effect.js Service/Layer/Context organisation, its TUI, Web UI, and SaaS backend. Extract **behavior**, not structure.

## Serialisation conventions

- `kotlinx.serialization` everywhere; single canonical `Json` via `JsonConfig.default` (class discriminator = `"type"`, `ignoreUnknownKeys = true`, no pretty-print).
- For git-tracked artefacts (notably `talevia.json` inside a project bundle), use `JsonConfig.prettyPrint` instead — same shape but `prettyPrint = true` with 2-space indent, so PRs show readable line-level diffs instead of one-giant-line blobs.
- `kotlin.time.Duration` uses the built-in 1.7+ serializer — do not add a custom one (there was a name-collision incident; see git log).
- SQLDelight rows (Sessions / Messages / Parts) store the canonical Kotlin model as JSON blobs in a `data` column. Top-level columns duplicate only what indices need. Project state no longer rides on SQLDelight — it persists as `talevia.json` inside a per-project directory bundle (see "Project bundle format" above).

## Project bundle format

Projects are **directories on disk** (not SQL rows), opened/created via `FileProjectStore.openAt(path)` / `createAt(path, title, ...)`. The bundle is git-friendly — AIGC products + explicitly-imported assets travel inside it so a `git push` gives a collaborator everything needed to reproduce an export (modulo the user's source footage, which stays external by default).

```
<bundleRoot>/
  talevia.json              # StoredProject envelope, pretty-printed JSON; schemaVersion + title + createdAtEpochMs + Project body
  .gitignore                # auto-written: ".talevia-cache/"
  media/                    # bundle-local assets — AIGC products + import_media(copy_into_bundle=true) targets
    <assetId>.<ext>
  .talevia-cache/           # machine-local — gitignored
    clip-render-cache.json  # ClipRenderCache (mezzanine paths are local)
```

`MediaSource` distinguishes `File(absolutePath)` (external, machine-local — typical for raw footage) from `BundleFile(relativePath)` (resolved per-render via `BundleMediaPathResolver`). AIGC writes always use `BundleFile`; `import_media` defaults to `File` and switches to `BundleFile` when `copy_into_bundle=true`.

Per-machine **recents registry** at `<userDataDir>/recents.json` maps `projectId → bundlePath`. `ProjectStore.list*()` and `NodesAllProjectsQuery` enumerate from there; opening the same bundle on a different machine registers it locally (project IDs stay stable across machines because they live inside `talevia.json`).

CLI top-level entry: `talevia open <path>` / `talevia new <path> [--title]` / `talevia <path>` (open if bundle, else new).

## Observability

- **Structured logging**: `io.talevia.core.logging.Logger` — platform-agnostic leveled logger; wire a JVM/iOS sink at the composition root.
- **Metrics**: the EventBus feeds a `CounterRegistry` in `core.metrics`. Server exposes a Prometheus-style scrape at `GET /metrics`.
- **Project persistence (JVM apps)**: `TALEVIA_PROJECTS_HOME` sets the default home for new projects (`createAt` without an explicit path lands at `<TALEVIA_PROJECTS_HOME>/<projectId>/`). `TALEVIA_RECENTS_PATH` points at the per-machine `recents.json`. Both default to `~/.talevia/projects` and `~/.talevia/recents.json` when unset on Desktop / CLI / Server. Android mounts `<filesDir>/projects/`; iOS mounts `Documents/projects/`.
- **DB persistence (sessions only)**: SQLite via `core.db.TaleviaDbFactory` hosts Sessions / Messages / Parts only — project state lives in on-disk file bundles (see "Project bundle format"). Honours `TALEVIA_DB_PATH` (absolute path, `":memory:"` / `"memory"` to force ephemeral). Unset → `~/.talevia/talevia.db` on Desktop / CLI; in-memory on Server unless explicit. Schema versioning uses `PRAGMA user_version`; downgrades refused. Schema v4 (migration `3.sqm`) drops the 4 project-related tables the post-`baad43f` SQL-backed store no longer reads.

## Known incomplete

These are visible in code but not yet wired end-to-end (expected follow-ups, not bugs):
- **Cross-engine filter / transition / subtitle / LUT parity is complete.** Vignette now renders on all three engines (FFmpeg `vignette`, AVFoundation `CIVignette`, Media3 radial-gradient `BitmapOverlay`). Transitions are a dip-to-black fade everywhere (FFmpeg `fade=t=in/out:c=black`, AVFoundation `CIColorMatrix` dim in the filter handler, Media3 full-frame black `BitmapOverlay` with ramped `alphaScale`). Subtitles are baked on all three engines (FFmpeg `drawtext`, Media3 `TextOverlay` + `OverlayEffect`, AVFoundation `CATextLayer` + `AVVideoCompositionCoreAnimationTool`). LUT grading flows through the shared `core.platform.lut` `.cube` v1.0 parser (`SingleColorLut.createFromCube` on Android; `CIColorCube` on iOS; `lut3d` on FFmpeg).
- **FFmpeg `drawtext` libfreetype** — FFmpeg's `drawtext` filter requires `libfreetype`, so ffmpeg builds compiled without it (e.g. some minimal homebrew bottles) silently drop subtitles — the E2E subtitle test auto-skips in that case.
- **`MusicGenEngine` ships a Replicate-backed provider (`ReplicateMusicGenEngine`).** Wired in both desktop + server containers when `REPLICATE_API_TOKEN` is set (`REPLICATE_MUSICGEN_MODEL` overrides the default `meta/musicgen` slug). `generate_music` stays unregistered when the env var is missing. Jobs poll at 3s intervals with a 10-minute hard deadline.
- **`UpscaleEngine` ships a Replicate-backed provider (`ReplicateUpscaleEngine`).** Wired in both desktop + server containers when `REPLICATE_API_TOKEN` is set (`REPLICATE_UPSCALE_MODEL` overrides the default `nightmareai/real-esrgan` slug). `upscale_asset` stays unregistered when the env var is missing. Source image uploaded as a base64 `data:` URI in the prediction payload — fine for stills up to a few MB; pre-signed uploads would be the follow-up for 4K+ inputs.
- **Per-clip incremental render is FFmpeg-only.** `ExportTool` dispatches to `runPerClipRender` (in `core/tool/builtin/video/export/`) when `engine.supportsPerClipCache = true` (FFmpeg JVM engine) AND the timeline fits the per-clip shape (exactly one Video track, optional Subtitle tracks). Mezzanines live under `<outputDir>/.talevia-render-cache/<projectId>/<fingerprint>.mp4`, keyed by clip JSON + transition fades + bound-source deep hashes + output-profile essentials + engineId. Every export's `Output` reports `perClipCacheHits` / `perClipCacheMisses`; a failed render cleans up orphan mezzanines (`1dd56521`). Media3 (Android) + AVFoundation (iOS) engines still fall back to `runWholeTimelineRender` — follow-up when their incremental paths land.

If a task touches one of these, expect to wire it up rather than work around it.

## Platform-specific docs

- `docs/IOS_INTEGRATION.md` — Xcode wiring, xcodegen, SKIE caveats.
- `docs/ANDROID_INTEGRATION.md` — SDK prerequisites, Media3 limitations.

## CLI changes require end-to-end validation

Any change under `apps/cli/` (Renderer, EventRouter, StdinPermissionPrompt, Repl, CliContainer, slash commands) or any tool whose JSON schema the CLI loads must be end-to-end validated before it's declared done. `:apps:cli:test` green is not sufficient — the user-visible surface depends on `isTty`, ANSI cursor math, CJK wrapping, and JLine quirks that unit tests skip. Invoke the `cli-e2e-testing` skill for the full three-layer recipe and checklist.
