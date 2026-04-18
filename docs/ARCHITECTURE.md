# Talevia 技术方案 v0

> 本文档与 [`REQUIREMENTS.md`](./REQUIREMENTS.md) 配套阅读。需求与原则在前者，本文档解决"怎么做"。

## Context

Talevia 是从零起步的项目。要做一个跨平台（iOS / Android / Desktop / Server）的 AI 视频编辑 Agent，核心层用 **Kotlin Multiplatform**，UI 各端原生。OpenCode（`/Volumes/Code/CodingAgent/opencode`）是行为参照——只抄行为语义，不抄 Effect.js 风格的代码组织。

本方案的目标：定下**模块边界、关键抽象签名、领域模型骨架、最小可行路径**，让后续编码时每个决策都可追溯。

**已确认的关键决策：**
- 桌面端：Compose Desktop（不违反禁 Electron/WebView 原则；Kotlin/Skia 原生编译）
- 首发 provider：Anthropic + OpenAI 并行（强制抽象层经受双 provider 考验）
- Server v0：极简 headless（Ktor 暴露 Agent Core 的 HTTP/WS，不做认证/多租户）
- MVP 顺序：Desktop + iOS 并行（更早暴露平台抽象的设计缺陷）

---

## 1. 模块划分

```
talevia/
├── core/                                  # KMP shared module（Agent Core）
│   ├── src/commonMain/kotlin/io/talevia/core/
│   │   ├── agent/         # Agent loop、运行时
│   │   ├── provider/      # LLM provider 抽象 + Anthropic/OpenAI 实现
│   │   ├── tool/          # Tool 接口、Registry、Dispatcher
│   │   ├── session/       # Session/Message/Part 模型 + 持久化
│   │   ├── compaction/    # Compaction 策略
│   │   ├── permission/    # Permission 规则与流程
│   │   ├── bus/           # 事件总线（SharedFlow-based）
│   │   ├── domain/        # 视频编辑领域模型（Timeline/Clip/Track/MediaAsset）
│   │   └── platform/      # 各端必须注入的接口（VideoEngine/MediaStorage/SessionStorage/...）
│   ├── src/commonMain/sqldelight/  # SQLDelight schema
│   ├── src/iosMain/       # iOS 侧的 actual（Ktor engine、SQLDelight driver 等）
│   ├── src/androidMain/
│   └── src/jvmMain/       # Desktop + Server 共用
├── apps/
│   ├── desktop/           # Compose Desktop app（依赖 core/jvmMain）
│   ├── ios/               # SwiftUI app + AVFoundation 实现 + SKIE 桥
│   ├── android/           # Compose（Android）+ Media3 实现
│   └── server/            # Ktor server + FFmpeg 实现（依赖 core/jvmMain）
└── platform-impls/        # 平台原生能力实现（按需独立 module）
    ├── video-avfoundation/   # iOS Swift package
    ├── video-media3/          # Android library
    └── video-ffmpeg-jvm/      # JVM library（Desktop + Server 共用）
```

**硬约束：** `core/commonMain` 不依赖任何平台 API；视频编解码、文件系统、媒体选择器、原生通知等全部由 `core.platform` 中的接口声明、各 `apps/*` 在启动时注入实例。

---

## 2. 关键抽象（接口签名）

### 2.1 Agent Loop

参照 OpenCode `runLoop`（`packages/opencode/src/session/prompt.ts:1305-1533`）的行为：while 循环 → LLM 流式调用 → 处理事件流 → 终止条件检查。

```kotlin
class Agent(
    private val provider: LlmProvider,
    private val tools: ToolRegistry,
    private val sessionStore: SessionStore,
    private val permissions: PermissionService,
    private val compactor: Compactor,
    private val bus: EventBus,
    private val clock: Clock,
)
suspend fun Agent.run(sessionId: SessionId, input: UserMessageInput): AssistantMessage

private sealed class StepOutcome {
    object Stop : StepOutcome()        // finish ∈ {stop, end-turn, content-filter, max-tokens}
    object Continue : StepOutcome()    // 还有 tool calls 或主动续轮
    object Compact : StepOutcome()     // 触发上下文压缩后继续
}
```

终止条件（与 OpenCode 对齐）：
- 最近一条 assistant 消息的 `finish` 不是 `tool-calls`
- 该消息 newer than 最近一条 user 消息
- `step >= agent.maxSteps`

### 2.2 Provider 抽象

参照 OpenCode `provider.ts`（核心思想：**对外是 SDK-agnostic 的事件流**），但不引入 Vercel AI SDK——KMP 没有等价物，自己封一层。

```kotlin
interface LlmProvider {
    val id: String
    suspend fun listModels(): List<ModelInfo>
    fun stream(request: LlmRequest): Flow<LlmEvent>
}

data class LlmRequest(
    val model: ModelRef,
    val messages: List<Message>,
    val tools: List<ToolSpec>,
    val system: List<SystemPrompt>,
    val options: ProviderOptions,   // thinking、cache、temperature 等
)

sealed class LlmEvent {
    data class TextStart(val partId: PartId) : LlmEvent()
    data class TextDelta(val partId: PartId, val text: String) : LlmEvent()
    data class TextEnd(val partId: PartId) : LlmEvent()
    data class ReasoningStart(val partId: PartId) : LlmEvent()
    data class ReasoningDelta(val partId: PartId, val text: String) : LlmEvent()
    data class ReasoningEnd(val partId: PartId) : LlmEvent()
    data class ToolCallStart(val callId: String, val toolId: String) : LlmEvent()
    data class ToolCallInputDelta(val callId: String, val jsonDelta: String) : LlmEvent()
    data class ToolCallReady(val callId: String, val input: JsonElement) : LlmEvent()
    data class StepStart(val tokens: TokenUsage?) : LlmEvent()
    data class StepFinish(val finish: FinishReason, val usage: TokenUsage) : LlmEvent()
    data class Error(val cause: Throwable, val retriable: Boolean) : LlmEvent()
}
```

**首发实现：** `AnthropicProvider`、`OpenAIProvider`，都基于 Ktor Client（KMP 原生）。Provider 内部把各家 SSE/JSON 协议归一化到 `LlmEvent`。Anthropic 的 thinking、prompt cache header、OpenAI 的 `responses` 端点差异，全部内化在各自实现里。

### 2.3 Tool 系统

参照 OpenCode `tool.ts` `Def` 接口和 `processor.ts` 的事件路由。Tool 用泛型表达类型化输入/输出，但对 LLM 暴露的是 JSON Schema。

```kotlin
interface Tool<I : Any, O : Any> {
    val id: String
    val description: String
    val inputSchema: JsonSchema
    val inputSerializer: KSerializer<I>
    val outputSerializer: KSerializer<O>
    val permission: PermissionSpec        // 声明它需要哪些权限
    suspend fun execute(input: I, ctx: ToolContext): ToolResult<O>
}

class ToolContext(
    val sessionId: SessionId,
    val messageId: MessageId,
    val callId: String,
    val abort: AbortHandle,
    val ask: suspend (PermissionRequest) -> PermissionDecision,
    val emit: suspend (PartUpdate) -> Unit,   // 流式产出（如 export 进度）
    val messages: List<MessageWithParts>,     // 历史只读
)

data class ToolResult<O>(
    val title: String,                  // UI 显示的卡片标题
    val outputForLlm: String,           // 给 LLM 的字符串结果
    val data: O,                        // 给 UI 的结构化数据
    val attachments: List<MediaAttachment> = emptyList(),
    val metadata: JsonObject = JsonObject(emptyMap()),
)

class ToolRegistry {
    fun register(tool: Tool<*, *>)
    fun resolve(filter: ToolFilter): List<Tool<*, *>>   // 按 permission/agent 配置过滤
}
```

Tool 执行流（与 OpenCode 对齐）：
1. `LlmEvent.ToolCallStart` → 在当前 assistant message 上创建 `ToolPart(state=pending)`
2. `LlmEvent.ToolCallInputDelta` → 增量拼装输入 JSON，发布 `message.part.delta`
3. `LlmEvent.ToolCallReady` → 反序列化输入 → `permissions.check()` → 必要时 `ctx.ask()` 阻塞 → `tool.execute()`
4. `tool.execute()` 期间 `ctx.emit()` 流式更新 UI（导出进度等）
5. 完成后 `ToolPart` 状态转 `completed`，把 `outputForLlm` 写回作为下一轮 LLM 输入
6. 失败 → `ToolPart(state=error)`；doom-loop（连续 3 次相同调用）触发权限询问

### 2.4 Session / Message / Part 模型

参照 OpenCode `message-v2.ts:359-452`。**视频编辑场景需要新增的 Part 类型**：`MediaPart`（用户拖入素材）、`TimelineSnapshotPart`（每次工具修改前后存 timeline 快照，便于 revert）、`RenderProgressPart`（导出/预览渲染进度）。

```kotlin
data class Session(
    val id: SessionId,
    val projectId: ProjectId,
    val title: String,
    val parentId: SessionId?,          // 用于分支会话
    val permissionRules: List<PermissionRule>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val compactingFrom: MessageId?,
    val archived: Boolean,
)

sealed class Message { /* User | Assistant，字段参照 OpenCode */ }

sealed class Part {
    abstract val id: PartId
    data class Text(...) : Part()
    data class Reasoning(...) : Part()
    data class Tool(val callId: String, val toolId: String, val state: ToolState, ...) : Part()
    data class Media(val asset: MediaAssetRef, ...) : Part()             // 视频编辑特有
    data class TimelineSnapshot(val timeline: Timeline, ...) : Part()    // 视频编辑特有
    data class RenderProgress(val jobId: String, val ratio: Float, ...) : Part()
    data class StepStart(...) : Part()
    data class StepFinish(val usage: TokenUsage, ...) : Part()
    data class Compaction(val replacedRange: IntRange, val summary: String, ...) : Part()
}
```

**持久化：** SQLDelight。表结构对齐 OpenCode `session.sql.ts`（`sessions` / `messages` / `parts`），`parts` 表带 `(session_id, time_created, id)` 索引保证按时间有序检索。

### 2.5 视频编辑领域模型（差异化核心）

不照搬 OpenCode 的"文件 + 代码"隐喻。Timeline 是 Talevia 的 first-class 状态。

```kotlin
data class Project(
    val id: ProjectId,
    val timeline: Timeline,
    val assets: List<MediaAsset>,
    val outputProfile: OutputProfile,    // 默认导出参数
)

data class Timeline(
    val tracks: List<Track>,
    val duration: Duration,
    val frameRate: FrameRate,
    val resolution: Resolution,
)

sealed class Track {
    abstract val id: TrackId
    abstract val clips: List<Clip>      // 必须按 timeRange.start 有序
    data class Video(...) : Track()
    data class Audio(...) : Track()
    data class Subtitle(...) : Track()
    data class Effect(...) : Track()    // 全局/区段滤镜轨
}

sealed class Clip {
    abstract val id: ClipId
    abstract val timeRange: TimeRange   // 在 Timeline 上的位置
    abstract val sourceRange: TimeRange // 在源媒体内的位置
    abstract val transforms: List<Transform>  // 缩放/旋转/位移/不透明度等
    data class VideoClip(val asset: MediaAssetRef, val filters: List<Filter>) : Clip()
    data class AudioClip(val asset: MediaAssetRef, val volume: Float) : Clip()
    data class TextClip(val text: String, val style: TextStyle) : Clip()
}

data class MediaAsset(
    val id: AssetId,
    val source: MediaSource,            // PlatformFileRef、HttpUrl、AssetLibraryRef 等
    val metadata: MediaMetadata,        // duration/codec/resolution/sampleRate...
    val proxies: List<ProxyAsset>,      // 缩略图、低码率代理
)
```

**Timeline 是 Core 拥有的纯数据结构**，所有修改通过 Tool 完成（`add_clip` / `split_clip` / `apply_filter` / `add_subtitle` / `add_transition` / `set_transform` / `export` 等）。Tool 调用前后存 `TimelineSnapshotPart`，天然支持 undo/分支。**平台只负责把 Timeline 渲染/导出**——不持有 Timeline 的"另一份真实状态"。

### 2.6 平台必须注入的接口

```kotlin
interface VideoEngine {
    suspend fun probe(source: MediaSource): MediaMetadata
    suspend fun extractFrames(asset: AssetId, sampling: Sampling): Flow<Frame>
    suspend fun render(timeline: Timeline, output: OutputSpec): Flow<RenderProgress>
    suspend fun preview(timeline: Timeline, time: Duration): Frame
}

interface MediaStorage {
    suspend fun import(ref: PlatformMediaRef): MediaAsset
    suspend fun thumbnail(asset: AssetId, time: Duration): ByteArray
    fun assetPath(asset: AssetId): MediaSource
}

interface SessionStore { /* SQLDelight 实现，jvmMain/iosMain/androidMain 各自 driver */ }

interface SecretStore {                  // API key 等
    suspend fun get(name: String): String?
    suspend fun set(name: String, value: String)
}

interface FilePicker { suspend fun pick(types: List<MediaType>): List<PlatformMediaRef> }
```

各平台实现：
- iOS：`AVFoundationVideoEngine`（Swift 写，SKIE 暴露 Kotlin 接口实现）+ `PHPickerFilePicker` + Keychain
- Android：`Media3VideoEngine` + `PhotoPicker` + EncryptedSharedPreferences
- Desktop / Server：`FfmpegVideoEngine`（JNI binding，建议用 [JavaCV](https://github.com/bytedeco/javacv) 或自己包 `ffmpeg-cli`） + AWT/Swing FilePicker + 文件系统 + JKS

### 2.7 Compaction

参照 OpenCode `compaction.ts:80-364`。两阶段：
1. **Prune**：从尾向前扫，保护最后 2 个 user turn 之外的所有 `completed` ToolPart（视频领域：保护 `TimelineSnapshotPart`，丢弃 `RenderProgressPart` 中间帧），`time.compacted` 标记不再读取
2. **Summarize**：调 LLM 用固定模板（Goal / Discoveries / Accomplished / Current Timeline State / Open Questions）生成摘要 → 写入 `CompactionPart`

触发：估算 token 数 > 模型上下文 × 阈值（默认 0.85）。

### 2.8 Permission

参照 OpenCode `permission/index.ts:20-272`。`PermissionRule(permission, pattern, action)`，action ∈ `allow | ask | deny`。视频领域典型权限：

- `media.import` — 引入新素材
- `media.export.write` — 导出到文件系统
- `media.network.fetch` — 下载在线素材
- `media.network.upload` — 上传到云
- `timeline.destructive` — 删除超过 N 个 clip

### 2.9 EventBus

`SharedFlow<BusEvent>` + 类型化 publish/subscribe 包装。事件类型对齐 OpenCode：`session.created/updated/deleted` / `message.updated` / `message.part.updated` / `message.part.delta` / `permission.asked/replied`。UI 各端订阅这条流增量渲染。

---

## 3. 跨端集成方式

### 3.1 KMP ↔ iOS（Swift）

- **SKIE** 暴露 suspend / Flow / sealed class 给 Swift（不必手写 callback wrapper）
- 平台接口（`VideoEngine` 等）在 Swift 侧实现 → SwiftUI App 启动时构造 `Agent` 时注入
- Timeline 在 Swift 侧用 `@Observable` 桥接，订阅 `EventBus` 流增量更新

### 3.2 KMP ↔ Android

- 直接 Kotlin 互调，Compose 组件订阅 `EventBus` 的 Flow
- Media3 实现 `VideoEngine`

### 3.3 KMP ↔ Desktop（Compose Desktop）

- `apps/desktop` 依赖 `core/jvmMain`
- FFmpeg 通过 `platform-impls/video-ffmpeg-jvm` 注入

### 3.4 KMP ↔ Server

- `apps/server` Ktor，复用 `core/jvmMain` 与 FFmpeg 实现
- 暴露：`POST /sessions`、`POST /sessions/{id}/messages`、`GET /sessions/{id}/events`（SSE）
- v0 单租户、无认证、配置文件指定工作目录

### 3.5 配置与组合根

`Agent` 由各端 `App` 入口构造（手动 DI，不引入 Koin/Hilt——Core 不知道它的存在）。配置文件（YAML/TOML，用 `kaml`）驱动：API key 来源、默认模型、permission 规则、工作目录。

---

## 4. 选型总表

| 关注点 | 选择 | 理由 |
|---|---|---|
| 持久化 | SQLDelight | KMP 唯一可工业级使用的 SQLite 抽象，schema-first |
| HTTP | Ktor Client | 官方 KMP，SSE 良好支持 |
| 序列化 | kotlinx.serialization | 官方 KMP，编译期生成 |
| 并发 | kotlinx.coroutines + Flow | 标准 |
| 配置 | kaml（YAML） | KMP，schema 友好 |
| iOS 互操作 | SKIE | 已硬约束 |
| 桌面 UI | Compose Desktop | 用户已选 |
| 视频处理 | iOS=AVFoundation / Android=Media3 / JVM=FFmpeg via JavaCV | 已硬约束 |
| 服务器 | Ktor Server | 与客户端 HTTP 同栈 |
| 测试 | kotlin.test + Turbine（Flow 测试） | 标准 |
| 日志 | kermit | KMP 标准 |
| DI | 手动组合根 | 避免 Core 依赖框架 |

---

## 5. 最小可行路径

### Milestone 0 — 项目骨架（1 周）
- KMP 项目初始化，模块划分落地（按本方案第 1 节）
- SQLDelight schema：`sessions/messages/parts`
- `Session/Message/Part/Timeline/MediaAsset` 数据类与序列化
- `EventBus` 实现
- 一个 trivial test：建 session → 写入 message → 订阅 part flow 看到事件

### Milestone 1 — Agent Core 跑通（2 周）
- `LlmProvider` 接口 + **AnthropicProvider** + **OpenAIProvider**（最小 streaming + tool calling）
- `Tool` 接口 + `ToolRegistry`
- 实现 1 个 trivial tool：`echo`（验证 tool dispatch + permission 流程）
- `Agent.run()` while-loop
- 单元测试：固定 mock provider 流，断言事件序列与 message tree

### Milestone 2 — 视频领域第一刀（2 周，Desktop 主导）
- `platform-impls/video-ffmpeg-jvm`：实现 `probe` / `extractFrames` / `render`
- 4 个核心 tool：`import_media` / `add_clip` / `split_clip` / `export`
- `Compose Desktop` app：素材库 / 时间轴可视化 / 聊天面板 / 渲染进度
- E2E：用户输入"把这两段视频拼起来导出 1080p" → Agent 调用 4 个 tool → 落盘 mp4

### Milestone 3 — iOS 端打通（2 周）
- SKIE 配置 + `apps/ios` SwiftUI 骨架
- `AVFoundationVideoEngine` Swift 实现 + `Photos` 框架接入
- 在 iPhone 上跑通 Milestone 2 的同一个 demo
- **此阶段会暴露平台抽象的真实缺陷**——预留 buffer 修接口

### Milestone 4 — Compaction + Permission UI（1.5 周）
- Compaction 算法（含 token 估算）
- Permission UI 流：弹窗 / 记忆 always 选择
- `media.export.write` / `media.network.*` 权限

### Milestone 5 — Android + Server（2 周）
- `apps/android` + Media3 适配
- `apps/server` Ktor + SSE
- 第二次（更激烈的）平台抽象审视

### Milestone 6 — 高级特性（按需）
- 滤镜 / 转场 / 字幕 tool
- 多 provider 配置 UI
- 会话分支 / 项目导出

---

## 6. 风险与权衡

1. **KMP iOS interop 边界**：Flow / 泛型 / sealed class 通过 SKIE 大幅缓解，但仍可能踩坑。Milestone 3 风险最高，预留 1 周 buffer。
2. **FFmpeg 依赖体积与 license**：JavaCV 自带原生二进制（macOS+Win+Linux），LGPL 安全。Server 镜像会变大（~200MB）。
3. **Timeline 状态权威性**：核心规则——**Core 是唯一真相**，平台只渲染。如果 iOS/Android 给用户实时拖拽 clip 的能力，必须把拖拽事件回流到 Core，而不是平台本地修改 Timeline 后再"通知" Core。这条容易破，要在 PR review 中盯紧。
4. **Tool schema 双向同步**：`Tool.inputSchema`（JSON Schema）和 `KSerializer<I>` 必须保持一致，错位会让 LLM 调用反序列化失败。建议用 kotlinx-serialization 的 schema generator 工具或自写，避免人手维护。
5. **未做（明确反需求）**：multi-agent coordinator、插件市场、IDE bridge、Effect.js 风格 Service/Layer——按 `REQUIREMENTS.md` 第 6 节执行。

---

## 7. 关键文件落点（首批要写的）

```
core/src/commonMain/kotlin/io/talevia/core/
├── agent/Agent.kt
├── agent/AgentLoop.kt
├── provider/LlmProvider.kt
├── provider/LlmEvent.kt
├── provider/anthropic/AnthropicProvider.kt
├── provider/openai/OpenAiProvider.kt
├── tool/Tool.kt
├── tool/ToolRegistry.kt
├── tool/ToolContext.kt
├── session/Session.kt
├── session/Message.kt
├── session/Part.kt
├── session/SessionStore.kt
├── compaction/Compactor.kt
├── permission/Permission.kt
├── permission/PermissionService.kt
├── bus/EventBus.kt
├── bus/BusEvent.kt
├── domain/Timeline.kt
├── domain/Clip.kt
├── domain/MediaAsset.kt
└── platform/{VideoEngine,MediaStorage,SecretStore,FilePicker}.kt

core/src/commonMain/sqldelight/io/talevia/core/db/
├── Sessions.sq
├── Messages.sq
└── Parts.sq
```

OpenCode 对应可读：
- Agent loop：`packages/opencode/src/session/prompt.ts:1305-1533`
- Provider：`packages/opencode/src/provider/provider.ts:88-1511`
- Tool：`packages/opencode/src/tool/tool.ts` + `session/llm.ts` + `session/processor.ts:216-461`
- Session/Message/Part：`packages/opencode/src/session/message-v2.ts:66-457`
- Compaction：`packages/opencode/src/session/compaction.ts:31-398`
- Permission：`packages/opencode/src/permission/index.ts:20-282`
- Bus：`packages/opencode/src/bus/index.ts:19-41`

---

## 8. 验证方式

每个 Milestone 结束时跑：
- **单元测试**：mock LLM 流，断言事件序列、message tree、Timeline 变更
- **集成测试**（M2 起）：FFmpeg 实跑 → 校验产出 mp4 帧数 / 时长 / 编码
- **E2E 烟测**（M2/M3）：手动在 Desktop / iOS 跑"拼接两段视频导出"流程
- **跨端一致性**（M3 起）：同一 Tool 调用序列，iOS/Desktop 产出的 Timeline 序列化结果应一致（diff 比对）

---

## 9. 待后续讨论（不阻塞 M0）

- 项目持久化格式（独立项目文件 vs 全部放 SQLite）
- 多窗口 / 多项目并发的会话管理
- 协作（Server 模式启用后）的冲突解决策略
- 模型成本展示与预算控制 UI
