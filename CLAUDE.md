# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Talevia** is an AI-driven cross-platform video editor. Users describe edits in natural language (cut/stitch/filter/subtitle/transition), the agent dispatches tools against a canonical Timeline, and each platform renders with its native engine.

Targets: **iOS · Android · Desktop (macOS/Windows/Linux) · Server**. Agent logic runs primarily on-device; the server is an optional headless deployment.

Read first: `docs/REQUIREMENTS.md` (principles, hard rules), `docs/ARCHITECTURE.md` (module design + rationale). The requirements doc is the source of truth — do not override it without the user's direction.

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
| Server | `./gradlew :apps:server:run` (port 8080; reads `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` from env) |
| Server tests | `./gradlew :apps:server:test` |
| Android debug APK | `./gradlew :apps:android:assembleDebug` (requires Android SDK at `~/Library/Android/sdk` via `local.properties`) |
| iOS app | `cd apps/ios && xcodegen generate && open Talevia.xcodeproj` (⌘R). Pre-build phase runs gradle automatically. |
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
6. **No Effect.js patterns.** Even though OpenCode uses Service/Layer/Context, Kotlin has its own idioms. See `docs/REQUIREMENTS.md` §6.

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

## Known incomplete

These are visible in code but not yet wired end-to-end (expected follow-ups, not bugs):
- **iOS Swift side** — `AVFoundationVideoEngine.swift` is a compile-only stub. SKIE bridging for value classes / `fun interface` / `Duration` needs a small iosMain helper before the real implementation can land. See `docs/IOS_INTEGRATION.md`.
- **Server permissions** — uses `AllowAllPermissionService`; no auth model.

If a task touches one of these, expect to wire it up rather than work around it.

## Platform-specific docs

- `docs/IOS_INTEGRATION.md` — Xcode wiring, xcodegen, SKIE caveats.
- `docs/ANDROID_INTEGRATION.md` — SDK prerequisites, Media3 limitations.
- `docs/ARCHITECTURE.md` — full architecture and milestone roadmap (M0 through M6).
