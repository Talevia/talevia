## 2026-04-21 — debt-merge-move-clip-pair：合并 `MoveClipTool` + `MoveClipToTrackTool`（§R.5.4 / §3a.2）

Commit: `f4c477a`

**Context.** `MoveClipTool` (same-track) + `MoveClipToTrackTool` (cross-track) 是典型 §3a.2 "singular / variant" 分裂 —— 两个工具只是 `toTrackId` 是否非空的差别：
- `MoveClipTool`：`Input(projectId, clipId, newStartSeconds)` — 只改 timeRange.start，track 固定。
- `MoveClipToTrackTool`：`Input(projectId, clipId, targetTrackId, newStartSeconds?)` — 改 track，可选改时间。

Rubric §3a.2：两个 tool spec × LLM turn 每次都要付 token；两者逻辑 70%+ 重叠；LLM 还要记"same-track 用哪个、cross-track 用哪个"的 routing rule。Backlog bullet 原文："合并为 `move_clip(clipId, toTrackId?: String, timelineStartSeconds?: Double)`：`toTrackId` null = 原 track 内移动，非 null = 跨 track；`timelineStartSeconds` null = 保持相对位置。"

**Decision.** 重写 `MoveClipTool.kt` 统一两种路径，删除 `MoveClipToTrackTool.kt` + 其 test：

**New Input**:
```kotlin
data class Input(
    val projectId: String? = null,       // 已经是 optional from cycle 17
    val clipId: String,                   // required
    val timelineStartSeconds: Double? = null,  // null = 保持当前 start
    val toTrackId: String? = null,        // null = 同 track
)
```

**行为矩阵**（三种合法组合）：
| `timelineStartSeconds` | `toTrackId` | 语义 |
|---|---|---|
| 非 null | null | 同 track 时间移动（旧 `move_clip` 语义） |
| null | 非 null | 跨 track 保持时间（旧 `move_clip_to_track` 无 newStartSeconds） |
| 非 null | 非 null | 跨 track + 移动时间（旧 `move_clip_to_track` 带 newStartSeconds） |
| null | null | 拒绝（空请求几乎必定 typo） |

**关键 leniency**：`toTrackId == 当前 track.id` **不再** fail loud（旧 `MoveClipToTrackTool` 会抛 "Use move_clip for same-track"）。合并后的工具自动走 same-track 路径 —— LLM 不用先 query "这个 clip 现在在哪个 track" 再决定传不传 `toTrackId`。

**Output**：统一 `(clipId, fromTrackId, toTrackId, oldStartSeconds, newStartSeconds, changedTrack)`。`changedTrack: Boolean` 让调用方无需 diff trackId 即可知道是否跨了 track（尤其对 ripple-delete 后的链式 `move_clip` 有用）。

**字段重命名**：旧 `MoveClipTool.newStartSeconds` → 新 `timelineStartSeconds`：
- 和 `AddClipTool.timelineStartSeconds` 一致（跨工具术语统一）。
- Backlog bullet 明确指定 `timelineStartSeconds`。
- Tool Input JSON 不持久化（仅作 LLM 当前 turn 的 spec），rename 无迁移成本（见 `unify-project-query` decision 同类确认）。

**删除 + doc cross-ref 更新**：
- 删 `core/src/commonMain/.../MoveClipToTrackTool.kt`（~220 行）。
- 删 `core/src/jvmTest/.../MoveClipToTrackToolTest.kt`（~220 行）。
- 5 端 AppContainer (`desktop`/`android`/`cli`/`server`/`ios`) 各删 1 import + 1 register line。
- `core/.../prompt/PromptEditingAndExternal.kt`："Moving clips" 章节从两段改成一段统一描述。
- `core/.../video/RemoveTrackTool.kt` KDoc 引用从 `MoveClipToTrackTool` 改成 `MoveClipTool`。

**Alternatives considered.**

1. **Option A (chosen)**: 统一到 `move_clip`，两 field 都 optional 但至少一个要 set。优点：一个 tool spec；LLM 选择面从"two-tool routing"变成"两个 optional field 的组合"，更直观；`changedTrack: Boolean` 输出字段让调用方无 diff 就能区分；preserves 所有既有语义；net **-1 tool**。缺点：需要 rename `newStartSeconds` → `timelineStartSeconds`（已通过 tool-input-not-persisted 证明无迁移成本）。
2. **Option B**: 保留两 tool，让 `MoveClipTool` 内部 delegate 到 `MoveClipToTrackTool`。拒绝：tool count 不降；LLM spec 成本不变；§3a.2 红信号保留。
3. **Option C**: 合并但字段名选 `newStartSeconds`（旧 `MoveClipTool` 字段名）+ `newTrackId`（可能对齐）。拒绝：`AddClipTool` / `add_subtitle` 等已用 `timelineStartSeconds` —— 保持术语一致让 LLM schema 模型更薄。`toTrackId` 比 `newTrackId` 动词性更强（"Move TO"）。
4. **Option D**: 同 track 时 `toTrackId == current` 仍然 fail loud（保留旧 `MoveClipToTrackTool` 的严格语义）。拒绝：统一 tool 的收益之一就是 LLM 不用先查再决定；返回 `changedTrack=false` 的 Output 语义已足够自我解释；严格 fail loud 只为了 "LLM 传错了应该知道"，但实际上传同 track 即时间移动是合法合并语义。
5. **Option E**: 保留 `newStartSeconds` field name（back-compat 缓冲期），同时接受新名 via alias。拒绝：`@SerialName` alias 是 kotlinx 支持，但 tool Input JSON 生命周期仅限单次 LLM turn（无跨 session 持久化），alias 增加 schema 表面积 + 文档负担，零收益。
6. **Option F**: 顺便把 `DuplicateClipTool.trackId` 也对齐成 `toTrackId` 方便记忆。拒绝：DuplicateClipTool 不在本轮 bullet 范围；触碰它会让 diff 失控。留给 repopulate 独立评估。

**Coverage.**

- 更新 `MoveClipToolTest.kt`：
  - 既有 7 test 保留 —— 正参数位置 `Input("p", "c1", 9.0)` 仍匹配（positional: projectId, clipId, timelineStartSeconds），Output 断言扩为 `fromTrackId=toTrackId=v1, changedTrack=false`。
  - 新增 5 test (migrated from deleted `MoveClipToTrackToolTest`):
    - `crossTrackMovePreservesTime` — `toTrackId` only，时间保持。
    - `crossTrackMoveWithRepositionShiftsTime` — `timelineStartSeconds + toTrackId`。
    - `crossTrackKindMismatchFailsLoud` — video clip → audio track 抛 loud error（§3a.9 反直觉边界）。
    - `unknownTargetTrackFailsLoud` — `toTrackId="nope"` 抛 loud error。
    - `toTrackIdEqualToCurrentIsSameTrackPath` — `toTrackId=v1` 等于当前 track，不 fail，走 same-track 路径，`changedTrack=false`。
    - `emptyInputFailsLoud` — 既不传 `timelineStartSeconds` 也不传 `toTrackId` → "at least one of" error。
- `./gradlew :core:jvmTest` 全绿（1150+ tests）+ `:apps:server:test` 全绿 + `:core:ktlintCheck` 全绿 + 4 端构建全绿。

**Registration.** 5 个 AppContainer (`desktop` / `android` / `cli` / `server` / `ios`) 各 -1 import + -1 register line。新 `MoveClipTool` 单独注册（保留）。

**§3a 自查.**

1. 工具数量: **-1**（净减）。PASS（§3a.1 显式收益）。
2. Define/Update: N/A。
3. Project blob: 不动 `Project`。PASS。
4. 状态字段: `timelineStartSeconds` / `toTrackId` 都是 optional positional-arg identifier，非 flag；`changedTrack: Boolean` 输出是 derived 信号。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: projectId 复用 `ctx.resolveProjectId`。PASS。
7. 序列化向前兼容: 旧 tool input `{"newStartSeconds": 5}` 不再解码（字段名 change）—— 但 tool input 是 per-turn LLM spec，不持久化；`unify-project-query` 已建立同类 precedent。PASS（单 turn LLM 重启意味新 schema 即生效）。
8. 五端装配: 全部 5 端 -1 import + -1 register。PASS。
9. 测试语义覆盖: 12 tests 覆盖 same-track move / cross-track move / cross-track+shift / kind mismatch / unknown track / same-id-as-current-track / empty input / source/filter preserve / overlap permit / snapshot emit / negative guard / missing clip。PASS。
10. LLM context 成本: -1 tool spec ~280 tokens 节省；新 helpText ~150 tokens（扩描述三组合）；净 **~130 tokens/turn 节省**。PASS（收益方向）。

**Non-goals / 后续切片.**

- **Follow-up: `debt-merge-apply-filter-pair`**（P1 下一条）— `ApplyFilterTool` + `ApplyFilterToClipsTool` 同样 singular/batch 分裂，同法合并。
- **Follow-up: `debt-merge-add-subtitle-pair`** / **`debt-merge-import-source-node-pair`** — 同 §R.5.4 家族。
- **不改 `DuplicateClipTool.trackId` 命名**（虽然和 `toTrackId` 不一致）—— 不在本 bullet 范围，独立 cycle 重命名代价低。
- **不加重叠校验** — 历史决策（overlap 允许 for PiP）保持，新 tool 继承。
