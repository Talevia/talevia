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
| Server | `./gradlew :apps:server:run` (port 8080; reads `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` from env; `TALEVIA_MEDIA_DIR=/some/dir` to persist the MediaStorage catalog) |
| Server tests | `./gradlew :apps:server:test` |
| Android debug APK | `./gradlew :apps:android:assembleDebug` (requires Android SDK at `~/Library/Android/sdk` via `local.properties`) |
| iOS app | `cd apps/ios && xcodegen generate && open Talevia.xcodeproj` (⌘R). Pre-build phase runs gradle automatically. |
| Lint (all modules) | `./gradlew ktlintCheck` (auto-fix: `ktlintFormat`). Rule profile in `.editorconfig`: hygiene-only (unused imports, final newline, import order). |
| Every target + every test | `./gradlew :core:jvmTest :platform-impls:video-ffmpeg-jvm:test :apps:server:test :apps:desktop:assemble :core:compileKotlinIosSimulatorArm64 :apps:android:assembleDebug` |

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

Inside `core/src/commonMain/kotlin/io/talevia/core/`: `agent/`, `provider/` (Anthropic, OpenAI — hand-written SSE parsing), `tool/` (typed Tool + ToolRegistry + builtin video tools), `session/` (Session/Message/Part models + SqlDelightSessionStore), `domain/` (Timeline, Track, Clip, MediaAsset, Project, ProjectStore — the video-editor first-class state), `compaction/`, `permission/`, `platform/` (VideoEngine / MediaStorage / MediaPathResolver contracts), `bus/` (SharedFlow-based typed event bus).

## Architecture rules — enforce these

1. **`core/commonMain` depends on zero platform APIs.** No AVFoundation, Media3, FFmpeg, AWT, JavaFX, etc. Platform-specific work lives behind interfaces in `core.platform` and is injected at the composition root of each app.
2. **Timeline is owned by Core.** Tools mutate `Project.timeline` via `ProjectStore.mutate(...)` under a mutex. Platforms only *render* the timeline — they never hold a parallel "authoritative" copy. A UI that lets users drag clips must round-trip the drag through the Core, not mutate locally and notify later.
3. **Tools are typed (`Tool<I, O>`)** with a `KSerializer` for input/output plus a JSON Schema surface for the LLM. `RegisteredTool.dispatch(rawInput, ctx)` is the one cast-boundary; add new tools in `core/tool/builtin/` and register them in each `AppContainer`.
4. **Asset paths go through `MediaPathResolver`.** Do NOT treat `AssetId.value` as a filesystem path (there used to be a temporary hack doing this; it's gone). `MediaStorage` extends `MediaPathResolver`; engines take a resolver in their constructor.
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

当下优先级：**macOS 桌面端先做到"相对完善可用"，再补其他平台**。

- "相对完善可用"的判断：desktop 路径上 VISION §5 rubric 的每一节都至少达到"部分"及格线 —— source 层可用、工具集覆盖主要编辑意图、AIGC 产物可 pin、agent 能跑出可看初稿、专家能接管。
- 在此之前，iOS / Android 只维持**不退化**（能编译、已有 E2E 测试继续通过），不主动扩新特性；Server 作为 desktop 的无头孪生同步演进。
- 跨平台抽象（`core/platform` 接口、`ToolRegistry` 注册等）**不得**因为 desktop 赶进度而省略 —— 红线仍是 CommonMain 零平台依赖。
- 任务梳理 / Gap-finding 时先按 VISION §5 打分，再按此平台优先级过滤候选项。

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

- `kotlinx.serialization` everywhere; single canonical `Json` via `JsonConfig.default` (class discriminator = `"type"`, `ignoreUnknownKeys = true`).
- `kotlin.time.Duration` uses the built-in 1.7+ serializer — do not add a custom one (there was a name-collision incident; see git log).
- SQLDelight rows store the canonical Kotlin model as JSON blobs in a `data` column. Top-level columns duplicate only what indices need.

## Observability

- **Structured logging**: `io.talevia.core.logging.Logger` — platform-agnostic leveled logger; wire a JVM/iOS sink at the composition root.
- **Metrics**: the EventBus feeds a `CounterRegistry` in `core.metrics`. Server exposes a Prometheus-style scrape at `GET /metrics`.
- **Media persistence**: JVM apps (server, desktop) honour `TALEVIA_MEDIA_DIR` — when set, `FileMediaStorage` persists the asset catalog to `<dir>/index.json` via atomic move. Unset → in-memory storage.
- **DB persistence**: JVM apps open SQLite through `core.db.TaleviaDbFactory`, which honours `TALEVIA_DB_PATH` (absolute path, or `":memory:"` / `"memory"` to force ephemeral). Unset + `TALEVIA_MEDIA_DIR` set → `<TALEVIA_MEDIA_DIR>/talevia.db`. Unset + no media dir → in-memory. The desktop `Main.kt` additionally seeds `TALEVIA_DB_PATH=~/.talevia/talevia.db` and `TALEVIA_MEDIA_DIR=~/.talevia/media` when unset, so projects / sessions / snapshots survive restarts without configuration. Schema versioning uses `PRAGMA user_version`; downgrades are refused.

## Known incomplete

These are visible in code but not yet wired end-to-end (expected follow-ups, not bugs):
- **Android `vignette` filter** — `Media3VideoEngine.mapFilterToEffect` skips `vignette` (no built-in Media3 primitive; needs a custom `GlShaderProgram`). FFmpeg and iOS render it. Every other filter has cross-engine parity. Transitions are now rendered as a dip-to-black fade on all three engines (FFmpeg `fade=t=in/out:c=black`, AVFoundation `CIColorMatrix` dim in the filter handler, Media3 full-frame black `BitmapOverlay` with ramped `alphaScale`). Subtitles are baked on all three engines (FFmpeg `drawtext`, Media3 `TextOverlay` + `OverlayEffect`, AVFoundation `CATextLayer` + `AVVideoCompositionCoreAnimationTool`). LUT grading flows through the shared `core.platform.lut` `.cube` v1.0 parser (`SingleColorLut.createFromCube` on Android; `CIColorCube` on iOS; `lut3d` on FFmpeg).
- **FFmpeg `drawtext` libfreetype** — FFmpeg's `drawtext` filter requires `libfreetype`, so ffmpeg builds compiled without it (e.g. some minimal homebrew bottles) silently drop subtitles — the E2E subtitle test auto-skips in that case.

If a task touches one of these, expect to wire it up rather than work around it.

## Platform-specific docs

- `docs/IOS_INTEGRATION.md` — Xcode wiring, xcodegen, SKIE caveats.
- `docs/ANDROID_INTEGRATION.md` — SDK prerequisites, Media3 limitations.
