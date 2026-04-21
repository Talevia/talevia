## 2026-04-21 — debt-merge-apply-filter-pair：合并 `ApplyFilterTool` + `ApplyFilterToClipsTool`（§R.5.4 / §3a.2）

Commit: `a202de1`

**Context.** `ApplyFilterTool` (单 clip) + `ApplyFilterToClipsTool` (批量：clipIds list / trackId / allVideoClips) 是典型 §3a.2 "singular / batch" 分裂 —— 两个工具核心行为完全一致（把 `Filter(name, params)` 追加到 `Clip.Video.filters`），只是选择面不同：
- `ApplyFilterTool`：`Input(clipId: String, filterName, params)` — 只支持单个 clip。
- `ApplyFilterToClipsTool`：`Input(clipIds: List<String>, trackId?, allVideoClips?, filterName, params)` — 支持列表 / 整 track / 整项目。

Rubric §3a.2：LLM 每 turn 付两个 tool spec 的 token（~2×250 = 500 tokens）；还要记"单 clip 用哪个、多 clip 用哪个"的 routing rule。Backlog bullet 原文："合并为 `apply_filter(clipIds: List<String>, filter: ...)`，单 clip 传一元素 list 即可。"

此外意外发现的 pre-existing 五端装配 gap：Android + iOS 的 AppContainer **从未注册** `ApplyFilterToClipsTool` —— 两个移动平台默默缺失批量 apply_filter 能力。合并后的统一 tool 自然继承所有平台的既有 `ApplyFilterTool` 注册，批量语义在所有 5 端自动可用。

**Decision.** 重写 `ApplyFilterTool.kt` 吸收 `ApplyFilterToClipsTool` 的 full batch 语义，删除后者 + 其 test：

**New Input**：
```kotlin
data class Input(
    val projectId: String? = null,                      // optional from cycle 17
    val filterName: String,                              // required
    val params: Map<String, Float> = emptyMap(),
    val clipIds: List<String> = emptyList(),            // single-clip = 1-element list
    val trackId: String? = null,                        // whole track
    val allVideoClips: Boolean = false,                 // whole project
)
```

**Selector exclusivity**: exactly one of `clipIds` / `trackId` / `allVideoClips` must be set；零或多个 → fail loud (`require(selectorCount == 1)`).

**Output**：统一 `(projectId, filterName, appliedCount, appliedClipIds, skipped)` —— 前单 clip 工具旧的 `(clipId, filterCount)` 形状丢弃（Tool Output 不持久化；consumers 换到 list-shape 读 `appliedClipIds.single()`）。

**Skipped reasons**: unresolvable clip ids / 非 video clip。和 `ApplyFilterToClipsTool` 一致，errorexclusive to explicit `clipIds` selector（`trackId` / `allVideoClips` 下不匹配的 clip 被静默忽略）。

**删除 + cross-ref 更新**：
- 删 `core/.../video/ApplyFilterToClipsTool.kt`（~212 行）。
- 测试文件 `git mv` 重命名：`ApplyFilterToClipsToolTest.kt` → `ApplyFilterToolTest.kt`，内部引用批量 sed 改名。
- 新增 1 test `singleClipViaOneElementList` 专门验证"单 clip 传 1-元素 list"路径（覆盖 §3a.9 反直觉边界）。
- `apps/desktop/AppContainer.kt` / `apps/server/ServerContainer.kt` / `apps/cli/CliContainer.kt` 各 -1 import + -1 register line。Android / iOS 本来就没注册批量工具 → 无需改（意外收益：批量能力自动到齐）。
- `apps/desktop/TimelinePanel.kt` dispatch id `"apply_filter_to_clips"` → `"apply_filter"`（UI 批量路径复用同 tool）。
- 3 个 test 文件的 call site 更新：`M6FeaturesTest` / `RevertTimelineTest` / `SessionRevertTest` 的 `clipId = "c1"` → `clipIds = listOf("c1")`。

**Alternatives considered.**

1. **Option A (chosen)**: 统一到 `apply_filter`，保留 `clipIds` / `trackId` / `allVideoClips` 三个互斥 selector。优点：既有批量功能全部保留；Android + iOS 顺势获得批量能力；和 `move_clip` 合并模式对齐（同 cycle 的 debt-merge-move-clip-pair）；net **-1 tool**。缺点：Input surface 比单 clip 旧版复杂 —— 但 helpText + schema description 已明确说明 selector 互斥。
2. **Option B**: 只保留 `clipIds`（删掉 trackId / allVideoClips selector）。拒绝：这两个 selector 在实际使用中有价值（"给 scene 2 整条 track 加 vignette"），删除会让用户回到"N 次 chat round-trip"的旧场景。bullet 本身方向没要求删掉 selector。
3. **Option C**: 保留两 tool，让 `ApplyFilterTool` 内部 delegate 到 `ApplyFilterToClipsTool`。拒绝：tool count 不降；LLM spec 成本不变；§3a.2 红信号保留。
4. **Option D**: 单 clip 用 `clipId: String?`，批量用 `clipIds: List<String>`，允许两者并存于 Input。拒绝：多入口 = 多互斥规则（`clipId XOR clipIds XOR trackId XOR allVideoClips`），schema 更复杂；用户写 "clipId+clipIds" 组合会进入 undefined 行为。统一到 `clipIds` 让 schema 干净。
5. **Option E**: 保留 Output 旧形状 `(clipId: String, filterCount: Int)` 单 clip 时，新形状只用于批量。拒绝：Output shape 分叉是 consumer side 负担（每次读要先 switch）；统一形状 + `.single()` 访问更直接；Tool Output 不持久化，切换 shape 无迁移成本。
6. **Option F**: 顺便也合 `DuplicateClipTool` / `RemoveFilterTool` 类似的"单/批"pattern。拒绝：本 bullet 只点 apply_filter；DuplicateClipTool / RemoveFilterTool 不在 debt scan 里属于这一 pair（当前只单 clip 版本）。越界做会让 diff 失控。

**Coverage.**

- 从 `ApplyFilterToClipsToolTest` 迁移的 6 tests 保持全部绿：`allVideoClipsAppliesUniformly` / `trackIdScopesToTrack` / `clipIdsAppliesToListWithSkipped` / `selectorExclusivityRejectsMultiple` / `ignoresNonVideoClips` / `missingProjectFailsLoud`。
- **新增**：`singleClipViaOneElementList` —— 验证 `clipIds=["c-1"]` 正确 target 单个 clip，`appliedClipIds=["c-1"]`，其他 clip 无影响（§3a.9 边界 case：merge 核心合法性证据）。
- 3 个回归 test (`M6FeaturesTest` / `RevertTimelineTest` / `SessionRevertTest`) 的 `clipId → clipIds` 迁移，确认现有 revert / snapshot / agent flow 路径不受影响。
- `./gradlew :core:jvmTest` 全绿 + `:apps:server:test` 全绿 + `:core:ktlintCheck` 全绿 + 4 端构建全绿。

**Registration.** 3 个 container (`desktop` / `cli` / `server`) 各 -1 import + -1 register line。Android + iOS 不变（它们本来就只注册了 `ApplyFilterTool`，现在自动获得批量能力，顺带修复 pre-existing 五端缺口）。

**§3a 自查.**

1. 工具数量: **-1**。PASS（§3a.1 显式收益）。
2. Define/Update: N/A。
3. Project blob: 不动 `Project`。PASS。
4. 状态字段: 3 个 selector 的 XOR 逻辑由 `require(selectorCount == 1)` 强制；非 Boolean flag chain。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: projectId 已 optional via `ctx.resolveProjectId`。PASS。
7. 序列化向前兼容: 旧单 clip `{"clipId": "c1"}` JSON 不再解码（字段名 change）—— tool input 不持久化，仅 per-turn LLM spec，无迁移成本（和 debt-merge-move-clip-pair 同 precedent）。PASS。
8. 五端装配: 前 3 端减注册；Android / iOS 本来没注册批量 tool，merge 后它们自然获得批量能力（顺带填补 pre-existing 五端 gap）。PASS。
9. 测试语义覆盖: 7 tests 覆盖 allVideoClips / trackId / clipIds-with-skipped / selector-exclusivity / non-video-ignored / missing-project / single-clip-via-1-element-list。PASS。
10. LLM context 成本: -1 tool spec ~300 tokens；新 helpText +~180 tokens（要描述 3 个 selector + skip 语义）；净 **~120 tokens/turn 节省**。PASS（收益方向）。

**Non-goals / 后续切片.**

- **Follow-up: `debt-merge-add-subtitle-pair`**（P1 下一条）— `AddSubtitleTool` + `AddSubtitlesTool` 同样 singular/batch 分裂，完全同法合并。
- **Follow-up: `debt-merge-import-source-node-pair`**（P1）— 同 §R.5.4 家族，merge pattern 类似。
- **不改 `RemoveFilterTool` / `DuplicateClipTool`** —— 当前只有单 clip 版本，不在本 bullet 范围。
- **不扩 selector 支持**（比如 `sourceNodeIds` / `assetIds` 的反向选择）—— 过度设计；当前三个 selector 已覆盖所有实际 workflow。
