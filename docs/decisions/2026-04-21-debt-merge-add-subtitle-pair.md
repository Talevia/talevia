## 2026-04-21 — debt-merge-add-subtitle-pair：合并 `AddSubtitleTool` + `AddSubtitlesTool`（§R.5.4 / §3a.2）

Commit: `086ec23`

**Context.** `AddSubtitleTool` (单条) + `AddSubtitlesTool` (批量 segments) 是 §3a.2 "singular / batch" 分裂的第三例（本 cycle 之前已分别完成 `debt-merge-move-clip-pair`、`debt-merge-apply-filter-pair`）—— 两个工具都往 subtitle track 追加 `Clip.Text`，只是输入 shape 不同：
- `AddSubtitleTool`：`Input(text, timelineStartSeconds, durationSeconds, fontSize, color, backgroundColor)` — 单条，emit 一个 snapshot。
- `AddSubtitlesTool`：`Input(segments: List<Segment>, fontSize, color, backgroundColor)` — 批量 N 段，uniform style，emit **一个** snapshot（load-bearing：transcribe_asset → 30+ segments 若 loop single 会堆 30 个 snapshot）。

Rubric §3a.2：两个 tool spec × LLM turn 付 ~500 tokens；LLM 还要记 single vs batch 路由。Backlog bullet 原文："合并为 `add_subtitles(subtitles: List<SubtitleSpec>)`，单条 = 一元素 list。"

**Decision.** 把 `AddSubtitlesTool` 扩成统一入口（保留 plural id `add_subtitles` 符合 bullet 指定），删除 `AddSubtitleTool.kt` + 其 test：

**New Input**：
```kotlin
data class SubtitleSpec(
    val text: String,
    val timelineStartSeconds: Double,   // renamed from old `startSeconds` for cross-tool consistency
    val durationSeconds: Double,
)

data class Input(
    val projectId: String? = null,               // optional from cycle 17
    val subtitles: List<SubtitleSpec>,          // renamed from `segments`; required non-empty
    val fontSize: Float = 48f,
    val color: String = "#FFFFFF",
    val backgroundColor: String? = null,
)
```

**术语统一三处 rename**：
- `segments` → `subtitles`：bullet 指定的名称，也是 helpText 里面向用户的 noun。
- `Segment` → `SubtitleSpec`：嵌套 data class 名也跟着改，`apply_filter` 的 `clipIds` 命名更有区分度（"Segment" 太泛）。
- `startSeconds` → `timelineStartSeconds`：对齐 `add_clip` / `move_clip` / old `AddSubtitleTool.timelineStartSeconds` 的全域术语。

**Style 策略**：uniform（所有 subtitles 在一次 call 里共享 fontSize / color / backgroundColor），和 old `AddSubtitlesTool` 一致。异构 style 仍然支持 —— 用 `edit_text_clip` 对个别 subtitle 事后改。Per-segment style 留给未来明确 driver（当前无）。

**删除 + 5 端装配 + cross-ref 更新**：
- 删 `core/.../video/AddSubtitleTool.kt`（~116 行）+ `AddSubtitleToolTest.kt`（~66 行）。
- 5 端 AppContainer（desktop/android/cli/server/ios）各 -1 import + -1 register line（`AddSubtitleTool(projects)` 行）。
- `core/.../agent/prompt/` 3 个 prompt 文件 + 6 个 KDoc 里的 `add_subtitle` → `add_subtitles`（TimelineSnapshots / RevertTimelineTool / AddTrackTool / EditTextClipTool / TrimClipTool / TranscribeAssetTool，以及 PromptAigcLane / PromptBuildSystem / PromptEditingAndExternal）。
- 2 个回归 test 更新 dispatch shape：`M6FeaturesTest` 和 `FfmpegEndToEndTest` 都从 `{text, timelineStartSeconds, durationSeconds}` flat fields 切到 `{subtitles: [{text, timelineStartSeconds, durationSeconds}]}` nested list。

**Alternatives considered.**

1. **Option A (chosen)**: 扩 `AddSubtitlesTool` 为统一入口（id=`add_subtitles`），单条通过 1-element list 表达。优点：id / 字段名和 bullet 指定匹配；helpText 直接教 LLM 把 transcribe_asset segments 批量传进来的 idiom；net **-1 tool**；和已合并的 `apply_filter` / `move_clip` merge 模式对齐。
2. **Option B**: 反向保留单条 id `add_subtitle`，内部 Input 扩 `segments: List`。拒绝：bullet 明确指定 `add_subtitles` 命名；单条 id + List input 读起来割裂（"add_subTITLE 怎么加 20 条？"）。
3. **Option C**: 保留两 tool，`AddSubtitleTool` 内部 delegate 到 `AddSubtitlesTool`。拒绝：tool count 不降；LLM spec 成本不变；§3a.2 红信号保留。
4. **Option D**: 允许 `subtitles` 里每条带 optional per-segment style overrides。拒绝：当前没有 driver（transcribe_asset 输出统一 style；手动单条也只要 top-level style 就够）；加 overrides 让 LLM schema 复杂度 +30% 换近零实际收益。需要时独立 cycle 加 `SubtitleSpec.fontSize: Float? = null` 等 nullable override 字段，forward-compat 改动。
5. **Option E**: 保留 `AddSubtitleTool` 作为 "style 更灵活的单条版本" 的 specialised 路径。拒绝：LLM 不会选择 specialised 路径（它会优先选看起来名字匹配的工具 → 单条时选 add_subtitle 让 session 丢失 batch 语义；transcribe-caption 时选 add_subtitles 也 OK）。多入口只增加 routing 混乱。
6. **Option F**: 把 `timelineStartSeconds` 缩成 `startSeconds`（更短 + SubtitleSpec 嵌套里已经在 "timeline" context 下）。拒绝：`AddClipTool` / `MoveClipTool` / old `AddSubtitleTool` 都用 `timelineStartSeconds`，跨工具一致让 LLM 模型更稳；rename 不是 bullet 目标。

**Coverage.**

- `AddSubtitlesToolTest.kt` 6 个既有 test（`dropsAllSegmentsInOneMutationAndOneSnapshot` / `sortsOutOfOrderSegmentsByStart` / `createsSubtitleTrackWhenProjectHasNone` / `extendsTimelineDurationToCoverTailSegment` / `rejectsEmptySegments` / `preservesExistingTrackOrder`）迁移到新字段名后全绿。
- **新增 `singleSubtitleViaOneElementList`**（§3a.9 反直觉边界）：验证 `subtitles = listOf(SubtitleSpec(text, 1.0, 2.5))` 正确落到 subtitle track，fontSize/color 生效 —— 合并核心合法性证据（和 `apply_filter` merge 同 pattern）。
- `AddSubtitleToolTest.kt` 的 `preservesExistingTrackOrderWhenUpdatingSubtitleTrack` 覆盖面已被新 `preservesExistingTrackOrder` test 涵盖。
- 2 个集成 test 更新：`M6FeaturesTest.addSubtitleCreatesSubtitleTrackAndClip` + `FfmpegEndToEndTest` 的 drawtext escaping case 都适配新 nested-list dispatch shape 并保持全绿。
- `./gradlew :core:jvmTest` + `:apps:server:test` + `:core:ktlintCheck` 全绿；4 端构建全绿。

**Registration.** 5 端各 -1 import + -1 register line（`AddSubtitleTool(projects)` / Swift 语法同理）。`AddSubtitlesTool` 注册保留。

**§3a 自查.**

1. 工具数量: **-1**。PASS（§3a.1 显式收益）。
2. Define/Update: N/A。
3. Project blob: 不动 `Project`。PASS。
4. 状态字段: 无新 flag。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: projectId 已经 optional via `ctx.resolveProjectId`。PASS。
7. 序列化向前兼容: 旧 `{segments, startSeconds}` JSON 不再解码 —— tool input 不持久化（仅 per-turn LLM spec），和 `move_clip` / `apply_filter` merge 同 precedent。PASS。
8. 五端装配: 全部 5 端同步减注册。PASS。
9. 测试语义覆盖: 7 tests 覆盖 multi-batch / sort / auto-track-creation / duration-extend / empty-reject / track-order-preservation / single-subtitle-via-1-element-list。PASS。
10. LLM context 成本: -1 tool spec ~230 tokens；helpText +60 tokens（描述 single-via-1-element-list idiom）；净 **~170 tokens/turn 节省**。PASS（收益方向）。

**Non-goals / 后续切片.**

- **Follow-up: `debt-merge-import-source-node-pair`**（P1 下一条，本 cycle 连续的第 4 个 merge）— `ImportSourceNodeTool` + `ImportSourceNodeFromJsonTool`（path vs jsonBody 分裂），完全同法合并。
- **不加 per-segment style overrides** —— 等真实 driver 出现。
- **不改 `auto_subtitle_clip`** —— 已经是 batch-aware 入口（一键 transcribe + add_subtitles），和本 merge 关联不强。
