## 2026-04-21 — streaming-tool-output-parts：AIGC tools 发 RenderProgress Part（VISION §5.2 rubric）

Commit: `a97efb1` (pair with `docs(decisions): record choices for streaming-tool-output-parts`).

**Context.** `ExportTool` 已经接入 `ctx.emitPart(Part.RenderProgress(...))` 流式进度（`ExportTool.kt:204-216`），UI 能在 1min+ 的 export 过程中看到 "started → frames ratio → completed" 逐帧流。但 AIGC tools（`GenerateVideoTool` / `GenerateImageTool` / `GenerateMusicTool` / `SynthesizeSpeechTool` / `UpscaleAssetTool`）**完全没有** emitPart 调用——`generate_video` 30s+ Replicate poll 过程中 UI 看不到任何信号，显得像 agent hang 死。backlog bullet 明确提出这是 §5.2 compiler-layer 观感缺口。

OpenCode `session/processor.ts` 流式消费 intermediate parts 是参照。`Part.RenderProgress` 的 schema（`jobId`, `ratio`, `message`，可选 null）是现成的、也被 session-log / revert / `describe_message` 等路径消费。

**Decision.** 在 `AigcPipeline` 里新增 `withProgress(ctx, jobId, startMessage, block)` 包裹 helper：

```
suspend fun <T> withProgress(ctx: ToolContext, jobId: String, startMessage: String, ...): T {
    val partId = PartId(Uuid.random().toString())
    ctx.emitPart(Part.RenderProgress(partId, ..., ratio = 0f, message = startMessage))
    return try {
        val result = block()
        ctx.emitPart(Part.RenderProgress(partId, ..., ratio = 1f, message = "completed"))
        result
    } catch (t: Throwable) {
        ctx.emitPart(Part.RenderProgress(partId, ..., ratio = 0f, message = "failed: ${t.message}"))
        throw t
    }
}
```

关键不变量：
- **同一 `partId`** 在 started / completed / failed 三条 emit 之间复用——UI 把这视作**一条** progress 行的在地更新，而不是 3 个独立 Parts（这和 ExportTool 的 in-place update 模式一致）。
- **started emit 先于 block 启动**：subscribers 在任何 provider-side 延迟之前就看到状态翻转。测试里 `startedEmitsBeforeBlockRuns` 显式锚定这一顺序。
- 失败路径 `throw t`，不吞异常——caller 的错误处理逻辑完全不受影响。

Wire 5 个 AIGC tool 的 `engine.generate/synthesize/upscale` 调用全部用 `AigcPipeline.withProgress(...) { ... }` 包裹。每个 tool 给自己的 jobId 命名空间（`gen-video-`, `gen-image-`, `gen-music-`, `tts-`, `upscale-`） + 自然语言 startMessage（包含 model、尺寸、时长等用户关心的参数）。

**Not this cycle (explicit non-goals):**

1. **中间 ratio 进度**（例如 `ratio=0.3` "processing frame 12/40"）。拒绝理由：`VideoGenEngine.generate(...)` / `ImageGenEngine.generate(...)` 的接口签名是一次 suspend call，没有 `onProgress: (ratio) -> Unit` callback。加这个 callback 要修改 5 个 engine interface + N 个 impl（Replicate、OpenAI、fake/test engines）——blast radius 大得多。当前 started + completed 两个 bookend 已经把 UI 从 "silent" 翻到 "generating…"，解决 90% 的痛。真正的 ratio 流式 → 独立 cycle，要等 engine 接口有 driver 时再动。
2. **移除 ExportTool 的 per-frame 实现改用 withProgress**。ExportTool 已有 `ffmpeg -progress` 自驱动的精细 ratio 流，比 bookend 富很多——不回退。withProgress 是为缺失 progress 信号的 tool 补的下限。

**Alternatives considered.**

1. **Option A (chosen)**: 在 `AigcPipeline` 加 helper，5 个 tool 共用。小 diff（+/-10 行每 tool），行为一致。PASS。
2. **Option B**: 每个 tool 自己复制 emit 代码。拒绝：5 份重复模板意味 5 个地方会在未来走样（有人加个 "failed due to timeout" 分支但忘了其它 4 个）。`AigcPipeline` 已经是这 5 个 tool 的公共抽象层（seed / cache hash / record），progress 放这里是自然归属。
3. **Option C**: 改 engine interface 加 `onProgress` callback。拒绝——见 "Not this cycle" #1。
4. **Option D**: 在 `AigcPipeline.record` 完成时一并发 "completed" part。拒绝：record 是可选步骤（pid null 时不调），但 progress 无论有没有 project 都该发。职责分离。

**Coverage.** 新 test file `core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/aigc/AigcPipelineProgressTest.kt`：

- `successEmitsStartedThenCompletedWithSamePartId` — 两个 emit，ratio 0→1，partId 复用，jobId + startMessage 正确。
- `failureEmitsStartedThenFailedAndRethrows` — 异常路径：emit started + failed，exception untouched 抛出，failed message 以 `"failed:"` 前缀 + 包含原异常 message。
- `startedEmitsBeforeBlockRuns` — 关键顺序锚定：block 在 `parts.size == 1` 时开始执行（started 已经发了）。这防止未来有人把 emitPart 挪到 block 之后、破坏 "UI 立即翻转" 的契约。

CapturedCtx 用测试专用 `ToolContext` 实现 emitPart = { parts += it }——纯 lambda，不依赖任何 DB / bus。

每个 AIGC tool 自己的 happy path 测试（已有的 `GenerateVideoToolTest` 等）保持不变——`withProgress` 是透明包装，不改变 tool 返回值或缓存命中行为。如果未来怀疑 tool X 没 emit 进度，查 `AigcPipelineProgressTest` 是否还绿即可。

**Registration.** 无 tool 注册变化 — 5 个 AIGC tool 的 id / input schema / helpText 都没动。

**Session-project-binding 注记（§3a.6）.** AIGC tool 的 `projectId: String?` input 不变。无影响。

**LLM context 成本（§3a.10）.** 零 — helpText 没动，system prompt 没动。新发的 `Part.RenderProgress` 只在 bus 上流，LLM 永远看不到。

**§3a 自查**：(1) 0 tool 变化。(2) N/A。(3) 不动 Project。(4) `ratio: Float` 是连续值不是 binary flag。(5) N/A。(6) N/A。(7) 不加 @Serializable 字段。(8) 无需 5-端装配（tool id 没变）。(9) 测试覆盖 happy + failure + 顺序锚定。(10) 0 context 成本。全 PASS。

**Non-goals / 后续切片.**
- Engine-level `onProgress` callback → tool forwarding ratio（需要改 5 个 engine interface + impl）。
- Bus 反映 `RenderProgress` 的专门事件（`BusEvent.RenderProgressUpdate`）—目前通过通用 `PartUpdated` 已经流到订阅者了，加专门事件要看真实 UI driver。
- Part lifecycle policy：completed progress parts 是否要 auto-compact 掉（它们在 revert / describe 上不再有价值）。当前和其他 Parts 一样走正常 compaction 路径，够用。
