# Talevia

AI 驱动的跨平台视频编辑器。用户用自然语言描述想要的编辑（剪辑、拼接、滤镜、字幕、转场等），Agent 理解意图并调度原生视频处理能力完成。Agent 逻辑主要在设备本地运行，server 作为可选的无头部署。

目标平台：**iOS · Android · Desktop (macOS/Windows/Linux) · Server**。

## 架构一览

```
core/                               KMP 共享模块（commonMain + jvmMain + iosMain + androidMain）
platform-impls/video-ffmpeg-jvm/    JVM VideoEngine（系统 ffmpeg shell-out）
apps/
  desktop/                          Compose Desktop
  server/                           Ktor HTTP/SSE 服务端
  ios/                              SwiftUI，xcodegen 从 project.yml 生成
  android/                          Compose + Media3VideoEngine
```

三层分层是硬约束：UI（各端原生，不共享）· Agent Core（跨平台，不依赖任何平台 API）· 领域能力（各端原生实现，经 `core.platform` 接口注入）。

详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) 与 [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)。

## 前置

- **JDK 21**：`brew install openjdk@21`，然后 `export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- **FFmpeg + ffprobe** 在 PATH 上（`platform-impls/video-ffmpeg-jvm` 的测试需要）：`brew install ffmpeg`
- **Android SDK**（构建 Android app 时）：默认从 `~/Library/Android/sdk` 读取，在 `local.properties` 里配置
- **Xcode + xcodegen**（构建 iOS app 时）

LLM provider 通过环境变量读取 key：`ANTHROPIC_API_KEY` / `OPENAI_API_KEY`。

## 常用命令

| 任务 | 命令 |
|---|---|
| 编译 core (JVM) | `./gradlew :core:compileKotlinJvm` |
| 编译 core (iOS sim) | `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :core:compileKotlinIosSimulatorArm64` |
| core 单测 | `./gradlew :core:jvmTest` |
| 跑单个测试类 | `./gradlew :core:jvmTest --tests 'io.talevia.core.agent.AgentLoopTest'` |
| FFmpeg E2E | `./gradlew :platform-impls:video-ffmpeg-jvm:test` |
| Desktop app | `./gradlew :apps:desktop:run` |
| Server (:8080) | `./gradlew :apps:server:run` |
| Android debug APK | `./gradlew :apps:android:assembleDebug` |
| iOS app | `cd apps/ios && xcodegen generate && open Talevia.xcodeproj`（⌘R） |
| 全平台 + 全测试 | `./gradlew :core:jvmTest :platform-impls:video-ffmpeg-jvm:test :apps:server:test :apps:desktop:assemble :core:compileKotlinIosSimulatorArm64 :apps:android:assembleDebug` |

## 架构规则

1. `core/commonMain` 零平台依赖。AVFoundation / Media3 / FFmpeg / AWT 一律不得出现，平台能力走 `core.platform` 的接口，在各 app 的 composition root 注入。
2. **Timeline 归 Core 所有。** 工具通过 `ProjectStore.mutate(...)` 在 mutex 下修改 `Project.timeline`，平台只负责渲染，不得持有并行的"权威"副本。UI 拖拽必须往返 Core。
3. **工具是类型化的** `Tool<I, O>`，带 `KSerializer` 和给 LLM 的 JSON Schema；`RegisteredTool.dispatch` 是唯一的 cast 边界。新工具加到 `core/tool/builtin/` 并在各 `AppContainer` 注册。
4. **资源路径走 `MediaPathResolver`。** 不要把 `AssetId.value` 当文件路径用。
5. **Provider SDK-agnostic。** Anthropic / OpenAI 都把原生事件翻译成同一份 `LlmEvent` 流，provider-specific 的类型不得泄漏进 Agent / Compactor / Tool。
6. **不引入 Effect.js 风格** 的 Service/Layer/Context，用 Kotlin 自己的惯例。

## OpenCode 作为"可运行的规范"

`/Volumes/Code/CodingAgent/opencode` 是行为参考，不是要 port 的代码。提取行为，不提取结构。详见 `CLAUDE.md` 中的文件指引。

## 尚未接入

以下能力在代码中可见但未端到端打通，属于预期中的后续工作：

- iOS `AVFoundationVideoEngine.swift` 仍是编译用桩

## 文档

- [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) — 原则与硬规则（源权威）
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — 模块设计与里程碑（M0–M6）
- [`docs/IOS_INTEGRATION.md`](docs/IOS_INTEGRATION.md) — Xcode / xcodegen / SKIE 注意事项
- [`docs/ANDROID_INTEGRATION.md`](docs/ANDROID_INTEGRATION.md) — Android SDK 与 Media3 限制
