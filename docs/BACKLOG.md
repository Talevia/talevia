# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

## P1 — 中优，做完 P0 再排

- **per-clip-incremental-render** — CLAUDE.md `Known incomplete` 首条：`ExportTool` 只 memoize 整时间线 export，没有"只重渲 stale 的一段 + 剩下从 cache 拼回"的增量路径。长项目一次小修改依旧全量 re-render。**方向：** 扩展 `RenderCache` 支持 per-clip-segment 级 memo（key 含 clip contentHash + source binding hash + profile）；`ExportTool` 发现 stale clip 集后只 re-ffmpeg 那几段 + concat 从 cache 拼接未变化段。参考 `docs/decisions/2026-04-19-per-clip-incremental-render-deferred-rationale-recorded.md` 里记录的方向。Rubric §5.3。

- **plan-execution-follow-through** — `draft_plan` 把"我打算这么做"的 steps 列出来，approvalStatus flip 为 approved 后，**agent 仍然要一条条人工 emit tool call**。没有自动化"按 plan 走"的闸门。VISION §5.4 的专家路径 batch-approval UX 少一半。**方向：** 新 tool `execute_plan(planId, dryRun?)` 按 plan 的 steps 顺序 dispatch 每个 step；遇到 `FAILED` 或用户新消息就暂停。单 tool 合并老 direction 里的 `execute_plan(planId)`。Rubric §5.4。

- **batch-asset-import** — `import_media` 一次只收一个 path，Vlog 用户从手机 rsync 过来 40 个片段要 40 次 tool call。VISION §5.4 小白路径摩擦项。**方向：** 扩展 `import_media.paths: List<String>` （`path` 仍保留为单文件便利），并发走 `engine.probe`，返回 `(successful: List<assetId>, failed: List<(path, error)>)`。Rubric §5.4。

- **per-tool-cost-hints-in-context** — `aigc.cost.<tool>.cents` metrics 存在但 agent 看不见：它没法回答"这次用 generate_image 还是 dall-e-3 省钱"。**方向：** 在 `list_tools` / tool 的 helpText 里注入最近 N 次调用的平均 cents（从 MetricsRegistry 读），agent 能在一次意图里做 cost tradeoff。Rubric §5.2。

- **debt-split-session-query-tool** — `SessionQueryTool.kt` 518 行，R.5 扫描 500-800 区间 → 默认 P1。10 个 select 分支 + 多个 row 数据类，和 ProjectQueryTool 同形。**方向：** 同 `debt-split-project-query-tool`，把 Input / rows / rejectIncompatibleFilters 拆出去，dispatcher 压到 <250 行。Rubric 外 / debt。

- **debt-consolidate-video-apply-variants** — `ApplyFilterTool` + `ApplyLutTool` 两个 tools，都是"给 clip 加一个 effect 属性"，Input 都是 `(clipId, <kind-specific>)`。**方向：** 评估合为 `apply_to_clip(target="filter"|"lut", ...)` 或按 add/remove variants 的先例（divergent Input 保留四件套）在 decision 里说明。Rubric 外 / debt。

- **debt-consolidate-project-snapshot-ops** — `SaveProjectSnapshotTool` + `RestoreProjectSnapshotTool` + `DeleteProjectSnapshotTool` 三件套。**方向：** 评估合为 `snapshot_op(action="save"|"restore"|"delete", ...)`；或按 add/remove-variants 的先例保留三件套并在 decision 里说明。Rubric 外 / debt。

## P2 — 记债 / 观望

- **audio-waveform-proxy-generator** — `ProxyPurpose.AUDIO_WAVEFORM` enum 存在，`FfmpegProxyGenerator` 只产 thumbnail；audio-only asset 一直没有 proxy，UI 仍然要 decode 原片才能渲染 waveform。跟进 `2026-04-22-asset-proxy-generation.md`。**方向：** `FfmpegProxyGenerator` 检测 audio-only asset，用 `ffmpeg -filter_complex "showwavespic"` 产一张 waveform PNG，填 `ProxyPurpose.AUDIO_WAVEFORM`。Rubric §5.3。

- **auto-generate-proxies-on-import-ios** — iOS `AppContainer.swift` 仍然用 `NoopProxyGenerator`。4K import 在 iOS 上看不到 thumbnail。**方向：** 实现 `AVFoundationProxyGenerator` (AVAssetImageGenerator) 并在 iOS AppContainer 里 wire 进 `ImportMediaTool`。Rubric §5.3 parity。

- **auto-generate-proxies-on-import-android** — 同上，Android `AndroidAppContainer.kt` 仍然 NoopProxyGenerator。**方向：** 用 Media3 `MediaMetadataRetriever.getFrameAtTime` 实现 `Media3ProxyGenerator`；wire 进 `ImportMediaTool`。Rubric §5.3 parity。

- **progressive-export-preview** — `ExportTool.executeRender` 只发 start / complete 两个 `Part.RenderProgress`，长 export 中间没预览。VISION §5.4 "专家路径能中途接管"需要中途 artifact 可看。**方向：** `VideoEngine.render` flow 增加 `ProgressPreview(frameBytes, ratio)` 事件（FFmpeg 用 `-filter_complex showinfo`+每 N 秒 pipe 一张 JPG）；`ExportTool` 转成 `Part.RenderProgress` 的 `thumbnail` 字段更新。Rubric §5.4。

- **session-prompt-cache-metrics** — OpenAI 的 Prompt Cache hit ratio / Anthropic 的 cache_read tokens 已经在 `MessageTokens` / `TokenUsage` 里，但 agent 没法查"这个 session 的 cache 利用率"。**方向：** `session_query(select=cache_stats)` 返回 `(sessionId, totalInputTokens, cachedReadTokens, cacheWrittenTokens, hitRatio)`。Rubric §5.4 debug。

- **agent-run-state-via-session-query** — `BusEvent.AgentRunStateChanged` 已经 publish 所有状态（Generating/AwaitingTool/Compacting/Cancelled/Failed），但 `session_query(select=status)` 只返回"最近一次 state snapshot"。想看"过去 5 分钟进入了几次 Compacting"需要 grep bus log。**方向：** `session_query(select=run_state_history, sessionId, sinceEpochMs?)` 返回时间线（从 agent-states tracker 的环形 buffer 读）。Rubric §5.4 debug。

- **debt-clean-todos** — `grep -rnE 'TODO|FIXME|HACK|XXX' core/src/commonMain/kotlin | wc -l` = 32。没有历史 snapshot 对比，这次 repopulate 把 32 作为 baseline 记录，下一轮 repopulate 比对增量。**方向：** 走读 32 条 TODO，凡能 5 分钟内修的就修，剩余的把未来的 backlog bullet 补上；目标 ≤ 25。Rubric 外 / debt。

- **debt-consolidate-video-duplicate-variants** — `DuplicateClipTool` + `DuplicateTrackTool` 两件套（和 Add/Remove 同形）。**方向：** 评估合为 `duplicate_from_timeline(target="clip"|"track")`；或按先例（divergent Input）保留两件套并在 decision 说明。Rubric 外 / debt。
