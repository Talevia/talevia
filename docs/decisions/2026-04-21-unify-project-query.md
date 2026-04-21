## 2026-04-21 — unify-project-query 第一切片：吸收 list_tracks / list_timeline_clips / list_assets（VISION §5.2 / §5.4）

Commit: `e0e5795` (pair with `docs(decisions): record choices for unify-project-query`).

**Context.** `core/tool/builtin/` 已经有 130+ 个 builtin tool，其中 20+ 个是 `list_*` / `find_*` 只读变体，每个 LLM turn 都把全部 spec 塞进 context；最近几周还在给它们加 `onlyX + sortBy + limit` 参数（参见 `2026-04-20-list-tracks-for-track-level-layout-introspection.md`、`2026-04-21-list-assets-sort-by.md`、`2026-04-21-list-transitions-only-orphaned-and-limit.md` 等 decisions）。增长模式是"每个维度一个新工具"而不是"少量 query 原语 + filter"。Backlog 的 P0 第 1 条 `unify-project-query` 把这个趋势标为需要逆转的劣化信号。

业界参照：`codebase-grep(pattern, glob, path)`、SQL `SELECT ... FROM ... WHERE ... ORDER BY ... LIMIT`、OpenCode `session/processor.ts` 的统一 tool dispatch 形态。三者共识是：读路径首选"少量强类型原语 + 参数化 filter"而非"为每个形状开一个工具"。

**Decision.** 引入 `ProjectQueryTool`（`project_query`），`select ∈ {tracks, timeline_clips, assets}` 做为 discriminator，每个 select 有自己一组 filter 字段 + sort key + limit/offset。Output 统一形状 `{projectId, select, total, returned, rows: JsonArray}`，其中 `rows` 按 select 各自的 typed `@Serializable` 行数据类（`TrackRow` / `ClipRow` / `AssetRow`）通过 `JsonConfig.default.encodeToJsonElement` 编码。下游消费者读回 `select` 字段，用对应的 row serializer 解出强类型。

**Absorbs (and deletes) this cycle:**
- `list_tracks` → `project_query(select=tracks)`
- `list_timeline_clips` → `project_query(select=timeline_clips)`
- `list_assets` → `project_query(select=assets)`

Net tool count: **-3 removed, +1 added = -2**。满足 §3a 约束 "工具数量不净增（新增必须同时删至少一个近似工具）"。

**Deliberate scope limits** (防止一口气吞掉所有 list/find tools):

1. **只做 3 个 select**。backlog 里列出的 `list_transitions` / `list_clips_bound_to_asset` / `list_clips_for_source` / `find_pinned_clips` / `find_unreferenced_assets` 不在本次吸收范围——这些要么有 genre-specific 映射（transitions 的 `fromClipId/toClipId` 推断）、要么跨 lockfile/source graph（pinned 需要 lockfile 查询、unreferenced 需要跨 clip+filter+lockfile 三 lane），合并进单一 select 会让 filter 字段指数膨胀。留给后续 cycle 增量吸收，每次一个 select。
2. **`find_stale_clips` 永远不进 project_query**。它背后是 DAG 推导 + `lockfile.findByAssetId` + `Source.byId` 对比 contentHash，本质是 aggregate derivation 而不是 pure projection——放进 query 原语会把 "row" 概念污染。
3. **`describe_*` 系列也不进**。`describe_clip` / `describe_project` / `describe_lockfile_entry` 返回的是单实体的深信息（带跨表 join + 推导字段），和 "多行投影" 的 query 模型是两件事；保留它们作为 "深看一个" 的入口。

**Fail-loud misapplied filters.** Filter 字段都挂在 top-level `Input` 上（trackKind / trackId / fromSeconds / toSeconds / onlyNonEmpty / onlySourceBound / kind / onlyUnused），但每个字段只对某些 select 合法。`rejectIncompatibleFilters` 在 `execute` 开头检查：用错了 select + 填错了 filter 组合 → `error()` 带具体字段名（例 "onlyUnused (select=assets only)"）。这样 LLM 拼错字段名时不会得到一个"看起来合理但其实 filter 被忽略"的空列表——宁可早挂，也别沉默。

**Alternatives considered.**

1. **保留 3 个旧工具不动、只加 `project_query` 作为新入口。** 拒绝：tool 数量净增 +1 而不是净减，§3a 硬否决。过渡期 LLM 还会被旧工具 spec 污染，反而拉大 context。当前阶段项目没有对外稳定面（CLAUDE.md 平台优先级小节），直接删不留 deprecation 窗口最干净。
2. **把 filter 做成单一 `filter: JsonObject?` 自由对象**（SQL WHERE 风格）。拒绝：和 Talevia 其他 tool 都走强类型 `@Serializable Input` 的约定冲突；JsonObject 里的 key typo 必须运行时检查且 LLM 拿不到 JSON Schema 辅助。flat typed fields + 运行时 "misapplied" 校验保住了两边——schema 指导 LLM 填哪些字段、runtime 校验阻止交叉误用。
3. **Output 用 sealed `QueryOutput` 多态**（每个 select 一个子类）。拒绝：`JsonConfig.default` 的 `classDiscriminator = "type"` 会在 JSON 里加 `"type"` 字段，污染 LLM 可读性；且 tool output JSON Schema 得声明 polymorphic 形状，Anthropic/OpenAI function-calling 都不擅长这个。选 "flat Output + rows:JsonArray + 按 select 解出 typed rows" 这种"外层统一 / 内层按需 typed"的折中。
4. **一次吸收所有 list/find 工具**（含 transitions / pinned / unreferenced / clips_bound_to_asset / clips_for_source）。拒绝：单 cycle 改动面过大，filter 字段会膨胀到 20+（每个特化都需要自己一组 filter），Input 的 KDoc + JSON Schema 本身就变成 500+ 行"哪个 filter 属于哪个 select"的文档。按切片增量吸收，每次补一个 select 更可审阅。

**Coverage.** `core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/project/ProjectQueryToolTest.kt` — 22 tests：
- 每个 select 的 happy path + 至少一个 filter/sort 组合（共 12 个）。
- 语义边界：
  - `unknownSelectThrows` — 未知 select 带提示。
  - `misappliedFilterThrowsLoudly` — 跨 select filter 报错（`kind` on `timeline_clips`）。
  - `misappliedTrackIdOnTracksSelectFails` — `trackId` 只能用于 timeline_clips。
  - `invalidTrackKindRejected` — 未知 trackKind 带提示。
  - `invalidSortForSelectRejected` — `duration` sort 属于 assets，不能用于 timeline_clips。
  - `limitClampsToMaxSilently` / `limitZeroClampedToOne` — 边界 clamp，不报错只夹范围。
  - `offsetPastEndReturnsNoRows` — 超 offset → `returned=0`，`total` 仍然准确。
  - `echoedSelectNormalisedLowercase` — 大小写归一化回显，便于下游判别。
  - `emptyTracksRowOmitsNullSpanField` — `JsonConfig.default` 的 `encodeDefaults=false` 不把 null span 字段写进 payload，下游 decoder 拿到干净 shape。

没有删除 happy path；删掉的是 `ListTracksToolTest.kt` / `ListTimelineClipsToolTest.kt` / `ListAssetsToolTest.kt` 三个独立 test 类——行为由 `ProjectQueryToolTest` 接管。Test count: 旧三套合计约 30+ 测试 → 新 1 套 22 测试，减少的是重复的 happy path scaffolding（三套都建了几乎一样的 fixture / ctx / store 模板），语义边界覆盖面增加。

**Registration.** 5 个 `AppContainer` 全都动：
- `apps/cli/src/main/kotlin/io/talevia/cli/CliContainer.kt`
- `apps/desktop/src/main/kotlin/io/talevia/desktop/AppContainer.kt`
- `apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt`
- `apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt`
- `apps/ios/Talevia/Platform/AppContainer.swift`

每个都：删 3 条旧 import + 3 条 `register(...)`、加 1 条 `ProjectQueryTool` import + `register(ProjectQueryTool(projects))`。Android 调试 APK + iOS simulator arm64 framework 都重跑确认编译通过。

**Upstream fix-ups.** 这次同时更新了引用旧工具 id 的地方：
- `core/agent/TaleviaSystemPrompt.kt` — 把 `list_assets` / `list_timeline_clips` 描述段替换为 `project_query(select=...)` 指南，保留每个 select 的字段列表 + filter 列表 + sort key 列表作为教学片段。另外 `prune_lockfile` / `remove_asset` 推荐流程里的 `list_assets(onlyUnused=true)` 改成 `project_query(select=assets, onlyUnused=true)`。
- `DescribeClipTool.kt` / `PinClipAssetTool.kt` 的错误消息从 "Call list_timeline_clips" 改成 "Call project_query(select=timeline_clips)"。
- `ListClipsBoundToAssetTool.kt` 的错误消息从 "call list_assets" 改成 "call project_query(select=assets)"。
- `RemoveAssetTool.kt` 的 helpText + KDoc。
- `PruneLockfileTool.kt` 的 KDoc。
- `ListTransitionsTool.kt` 的 KDoc（`[ListTimelineClipsTool]` → `[ProjectQueryTool]`）。
- 两个 test（`DescribeClipToolTest` / `PinClipAssetToolTest`）里断言错误消息包含 `list_timeline_clips` → `project_query(select=timeline_clips)`。

保留不动的 KDoc 引用（这些不指向 tool id，只是 "pattern mirror" 形容）：
- `ListProjectsTool.kt` 的 "mirrors the list_assets sortBy pattern" — 历史类比注释。
- `ListMessagesTool.kt` 的 "list_timeline_clips / list_source_nodes house style" — 同上。
- `RemoveFilterTool.kt` 的 "list_timeline_clips + iterate" — 推荐流程，将在后续 cycle 吸收 `list_transitions` 时一并更新。

它们都不影响 agent 运行时行为（不是 LLM 可见文本），留到触及各自文件的 cycle 顺手改。

**Session-project-binding 注记（§3a.6）.** `ProjectQueryTool.Input.projectId: String` 是暂接参数。等 `session-project-binding`（P1 backlog）落地后，这个字段应当从 `ToolContext` 的 `currentProjectId` 拿，只在显式跨 project 查询时才手传。先这样是为了和所有既有 project 工具形态一致；届时全量迁移到 context 注入，ProjectQueryTool 跟大部队一起换就行。

**LLM context 成本（§3a.10）.** 新 tool spec 约 2500 token（helpText 列出三个 select 的 rows/filter/sort 分类 + 每个 filter 字段的 JSON schema 描述）。吸收了三个旧 tool 的 spec（每个 ~700 token）→ 净减少约 ~-100 token/turn。更重要的是 growth 曲线：今后再加 `list_X`、`find_X` 只读工具是默认绕过的——先看能不能加进 project_query 的现有 select 或新增 select，加在 `@Serializable Input` 上的新 filter 字段摊分到的 token 成本远小于一个独立 tool 的 spec 成本。

**Non-goals / 后续切片**（在 backlog repopulate 时按需重新挂回）：
- 吸收 `list_transitions` 进 `select=transitions`（需要额外的 `onlyOrphaned` filter + `fromClipId / toClipId` 推断）。
- 吸收 `find_pinned_clips` / `find_unreferenced_assets` 进 timeline_clips / assets 的 filter 扩展（`onlyPinned` 要查 lockfile；`referenced` 要同时扫 clips + filters + lockfile 三 lane）。
- 把 `describe_*` 系列留着 / 不动。

这些是独立改动，值得各自走一次 plan → decision。
