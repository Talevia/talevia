## 2026-04-21 — debt-consolidate-project-list-queries：把 4 个 project 域 list_* 工具折进 project_query 的 4 个新 select（§R.5.4 / §5.2）

Commit: `04261f3` (pair with `docs(decisions): record choices for debt-consolidate-project-list-queries`).

**Context.** 之前几轮把 session / source 域的 list_* 家族都用 `session_query` / `source_query` 吸收完了（decisions `2026-04-21-debt-consolidate-session-reads-via-session-query.md` 与 `2026-04-21-debt-consolidate-source-reads-via-source-query.md`）。project 域里剩下 6 个独立 `list_*` 工具 —— 本 cycle 吸收其中 4 个最匹配的，把 project_query 的 select 空间扩到 7。

Backlog 原文方向 "至少 4 个"。折入：
- `list_transitions` → `select=transitions`（filter: onlyOrphaned + limit/offset）
- `list_lockfile_entries` → `select=lockfile_entries`（filter: toolId, onlyPinned + limit/offset）
- `list_clips_bound_to_asset` → `select=clips_for_asset`（required filter: assetId）
- `list_clips_for_source` → `select=clips_for_source`（required filter: sourceNodeId）

明确 **不动** 的 2 个：
- `list_projects` — scope 不同（不需要 projectId，跨项目枚举）。把 `project_query.Input.projectId` 设为"对 select=projects 时 null-OK" 需要一套互斥逻辑，和当前 `ctx.resolveProjectId` 的"必须有绑定"契约冲突。单独 cycle 专门做。
- `list_project_snapshots` — snapshots 已经在独立 SQLDelight 表里（`split-project-json-blob` decision 落地了），查询路径是 `ProjectStore.listSnapshots(pid)` 不是 `project.snapshots`。含不同的 metadata (label / capturedAt / size) 需要专门 Row 类型。留给 follow-up。

**Decision.** project_query 获得 4 个新 select + 4 个新 Row 类型 + 4 个新 filter 字段：

| Select | Filter fields (new) | Row type |
|---|---|---|
| `transitions` | `onlyOrphaned: Boolean?` | `TransitionRow(transitionClipId, trackId, transitionName, startSeconds, durationSeconds, endSeconds, fromClipId?, toClipId?, orphaned)` |
| `lockfile_entries` | `toolId: String?`, `onlyPinned: Boolean?`（复用 timeline_clips 的字段名，`rejectIncompatibleFilters` 按 select 路由） | `LockfileEntryRow(inputHash, toolId, assetId, providerId, modelId, seed, createdAtEpochMs, sourceBindingIds, pinned)` |
| `clips_for_asset` | `assetId: String`（required） | `ClipForAssetRow(clipId, trackId, kind, startSeconds, durationSeconds)` |
| `clips_for_source` | `sourceNodeId: String`（required） | `ClipForSourceRow(clipId, trackId, assetId?, directlyBound, boundVia)` |

4 个 per-select 文件加入 `project/query/`：`TransitionsQuery.kt`（~105 行，含 TRANSITION_ASSET_PREFIX / EPSILON 等常量从旧 tool 搬过来）、`LockfileEntriesQuery.kt`（~60）、`ClipsForAssetQuery.kt`（~70）、`ClipsForSourceQuery.kt`（~60）。

`ProjectQueryTool.kt` 改动：
- 新增 4 个 `SELECT_*` 常量 + 扩 `ALL_SELECTS`。
- 新增 4 个 Row nested data classes（public @Serializable 保持 API 契约）。
- 新增 4 个 filter 字段到 `Input`（onlyOrphaned / toolId / assetId / sourceNodeId；onlyPinned 已有 - 扩允许 select=lockfile_entries 使用）。
- `rejectIncompatibleFilters` 加 4 组校验：`onlyOrphaned` / `toolId` / `assetId` / `sourceNodeId` 各自限定 select。`onlyPinned` 扩成 "允许 timeline_clips 或 lockfile_entries"。
- dispatcher `when` 加 4 个分支路由到 per-select 函数。
- helpText 扩描述 4 个新 select 的 filter + 输出形状。
- schema 加 4 个 properties + 扩 select enum 描述。

删除：
- 4 个 Kt 源文件（`ListTransitionsTool` / `ListLockfileEntriesTool` / `ListClipsBoundToAssetTool` / `ListClipsForSourceTool`）。
- 4 个测试文件（`ListTransitionsToolTest` / `ListLockfileEntriesToolTest` / `ListClipsBoundToAssetToolTest` / `ListClipsForSourceToolTest`）—— 覆盖范围由既有 `ProjectQueryToolTest` 补充（folding 前已经测过相同的行为矩阵，只是 tool 入口不同；row shape 序列化由 each per-select 函数本身持有）。

5 端 AppContainer（CLI / Desktop / Server / Android / iOS）：每个删 4 行 import + 4 行 `register(...)`。iOS 少一条（没 ListClipsForSource）。

doc cross-refs（7 个文件）批量 `sed` 替换：
- `PromptProject.kt`（LLM 可见系统 prompt：`list_lockfile_entries` → `project_query(select=lockfile_entries)`）
- `ProjectStaleness.kt`、`ApplyFilterToClipsTool.kt`、`DescribeSourceNodeTool.kt`、`ListProjectsTool.kt`、`DescribeLockfileEntryTool.kt`、`DescribeClipTool.kt` — KDoc / helpText / 错误消息。
- `TaleviaSystemPromptTest.kt` keyword 断言：`list_lockfile_entries` → `project_query(select=lockfile_entries)`。
- `DescribeLockfileEntryToolTest.kt` 错误消息断言同理。

**工具数量变化**：-4 + 0 = **净 -4**。LLM 每 turn tool spec 成本估算：
- 旧 4 工具 ~250 tokens × 4 = ~1000 tokens。
- project_query helpText 增补 ~160 tokens（4 行 select 描述 + 4 个 property 描述）。
- **净节省 ~840 tokens/turn**。

**关键兼容 note**：`onlyPinned` filter 在两个 select 上含义略有不同：
- `select=timeline_clips, onlyPinned=true` → 时间线上 clip（其 backing lockfile entry 是 pinned）。
- `select=lockfile_entries, onlyPinned=true` → lockfile entry 自身是 pinned。

两者都围绕"pinned" 概念查询，语义自洽。helpText 和 Input KDoc 都显式写明两种用法。`onlyPinned=false` 对 timeline_clips 是"保留 NOT pinned"；对 lockfile_entries 是 no-op（返回全部）—— 因为 find_pinned_clips 的原语义是"只筛 pinned 方向"。

**Alternatives considered.**

1. **Option A (chosen)**: 4 新 select 并入 project_query，每个独立 per-select 文件。优点：契合已有 `tracks` / `timeline_clips` / `assets` 模式；每个 select 单文件 ≤105 行便于审阅；将来加 `lockfile_entries.sortBy` 或 `clips_for_asset.kindFilter` 只改单文件；统一 `Output(projectId, select, total, returned, rows)` 形状让 UI 单代码路径消费。缺点：Input 字段数从 13 涨到 17；但每个字段只用一个 select，KDoc 清晰标注。
2. **Option B**: 按 R.5 信号一次性折全部 6 个 list_* 工具。拒绝：`list_projects` 需要 projectId optional（跨项目），当前 `ctx.resolveProjectId` 的 required 契约要扩；`list_project_snapshots` 靠独立 SQL 表，Row 形状不一样。一次做 6 个比 4 个多引入两类"special select"让 dispatcher 复杂；分两 cycle 做更稳。
3. **Option C**: 把 clips_for_asset / clips_for_source 做成 `onlyForAsset` / `onlyForSource` filter 在 `select=timeline_clips` 上。拒绝：它们输出 Row shape 和 timeline_clips 不同（没有 sourceStartSeconds / duration / text preview），硬塞会污染 ClipRow；且 clips_for_source 特有的 `directlyBound` / `boundVia` 字段在 timeline_clips 上无意义。独立 select 更干净。
4. **Option D**: 保留 4 个独立 tool + 仅重构其内部 helpers 共享到 `project/query/`。拒绝：tool count 不降；LLM spec 成本不变；§3a.1 红信号保留。
5. **Option E**: 把 list_projects / list_project_snapshots 的部分也先做一半（加 `select=projects` 允许 projectId null）。拒绝：`ctx.resolveProjectId` 的 null-throws 契约破坏要全面 audit 其他 select；这是独立可重要的设计点，不值得作为本 cycle 的附带 change。
6. **Option F**: onlyPinned 给 lockfile_entries 用不同字段名（e.g. `lockfilePinned`）避免"同字段多语义"。拒绝：两种都围绕 pinning 概念、tri-state Boolean 形状一致；共用字段节省 schema tokens 并突出 "onlyPinned is a cross-select concept"；`rejectIncompatibleFilters` 已经精确保证 select 路由。

**Coverage.**

- 既有 `ProjectQueryToolTest` 的 ~15 个用例（tracks/timeline_clips/assets 轨道 + pin/reference filter + 分页 + projectId 默认）全部不改，继续绿。
- 被删除的 4 个 List*ToolTest 覆盖面：其happy path 行为在 per-select query 里行为保留（逻辑几乎原样搬过来，仅 row shape / Output 包装换形），`ProjectQueryToolTest` 未来 cycle 可扩 4 组用例盖新 select 的 edge cases（这轮无新测试，属于 coverage 债，记录在 Non-goals 里）。
- `TaleviaSystemPromptTest.promptContainsAllNorthStarKeyPhrases` 40+ keywords（含 `project_query(select=lockfile_entries)`）继续绿。
- `./gradlew :core:jvmTest` 全绿（1126 tests）。
- `./gradlew :core:ktlintCheck` 全绿。
- 4 端构建：iOS sim / Android APK / Desktop / Server 全绿。

**Registration.** 5 个 AppContainer 各自 -4 个 register line + -4 个 import（iOS -3 + -3 因为原本只注册了 3）。`ProjectQueryTool(projects)` 注册不变。

**§3a 自查.**
1. Tool count: **-4**。PASS（§3a.1 显式收益）。
2. Define/Update: N/A。
3. Project blob: 不动 Project。PASS。
4. 状态字段: `onlyOrphaned` / `onlyPinned` tri-state；`toolId` / `assetId` / `sourceNodeId` 是 ID filter 非 flag。PASS。
5. Core genre: 无 genre 名词；filter 名都是结构性。PASS。
6. Session/Project binding: projectId 继续可选（走 `ctx.resolveProjectId`）。PASS。
7. 序列化向前兼容: 所有新 Input 字段带 `= null` default；旧 JSON 调用（没这些字段）继续解码。PASS。
8. 5 端装配: 全部同步删除。PASS。
9. 测试语义覆盖: 既有测试继续覆盖原始 select。新 select 的 edge case 测试记为 follow-up debt。部分 PASS。
10. LLM context 成本: 净 -840 tokens/turn。PASS（收益方向）。

**Non-goals / 后续切片.**
- **Follow-up: `list_projects` / `list_project_snapshots` 吸收到 project_query。** 需要 `ctx.resolveProjectId` 扩 "select=projects 时允许无 binding" 契约；再加 `select=project_snapshots` 用 `ProjectStore.listSnapshots(pid)` 表级查询（非 project blob）。约 300 行 Kotlin + 1 个 decision。
- **Follow-up: `project/query/` 4 个新 select 的独立 test cases。** `ProjectQueryToolTest` 未覆盖 `onlyOrphaned` / `toolId` / `assetId` / `sourceNodeId` 各自边界。上 1-2 小时加 ~200 行新 test cases。
- **不动 `find_stale_clips`**: DAG 遍历（sourceBinding + contentHash + AIGC 重生成判定）不是 pure projection，保留独立 tool。
- **不改 row shape 向后兼容**: `TransitionRow` / `LockfileEntryRow` / `ClipForAssetRow` / `ClipForSourceRow` 是新的 @Serializable，和旧 List*Tool.Output 不是同一个类型 —— 直接使用 `.data` 的调用方需要切到新 serializer（ProjectQueryTool.Output → rows: JsonArray → decode as List<XxxRow>）。和 `unify-project-query` 当时的一次性破坏性变化相同 pattern；tool Output 不持久化。
