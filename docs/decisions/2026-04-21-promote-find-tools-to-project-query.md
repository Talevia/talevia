## 2026-04-21 — promote-find-tools-to-project-query：把 `find_pinned_clips` / `find_unreferenced_assets` 折进 `project_query` 的 onlyPinned / onlyReferenced filter（VISION §5.2）

Commit: `f92e694` (pair with `docs(decisions): record choices for promote-find-tools-to-project-query`).

**Context.** 2 个 find tool 实际上是 `project_query` 的特化 projection：
- `find_pinned_clips` = `project_query(select=timeline_clips, onlyPinned=true)` — 走 timeline，筛 "backing lockfile entry pinned" 的 clip。
- `find_unreferenced_assets` = `project_query(select=assets, onlyReferenced=false)` — 走 assets 目录，筛 "没被 clip / filter / lockfile 引用" 的孤儿资产。

保留它们当独立工具只会让 LLM 每 turn 多付 2 份 spec token。`find_stale_clips` **不动**——它的 DAG 遍历（根据 `sourceBinding` 推算 "上游源节点是否 contentHash 变过"）不是 pure projection，是 aggregate 计算。本轮严格限定在"能退化成 filter 的 find tool"。

**Decision.** `project_query.Input` 加两个 tri-state optional Boolean filter：

| 字段 | Select | 含义 |
|---|---|---|
| `onlyPinned: Boolean? = null` | `timeline_clips` | `true`=仅返回有 pinned lockfile entry 的 clip（替代 `find_pinned_clips`）；`false`=返回未 pinned 的（含无 entry 的 imported / text clip）；`null`=不过滤。 |
| `onlyReferenced: Boolean? = null` | `assets` | `true`=仅返回被引用（clip / Video filter LUT / lockfile）的资产；`false`=仅返回孤儿资产（替代 `find_unreferenced_assets`）；`null`=不过滤。 |

`onlyPinned` 的判定逻辑下沉到 `TimelineClipsQuery.kt` 的 `matchesPinned(clip, project, onlyPinned)` 辅助函数。对 `Clip.Text`（无 assetId）和无 lockfile entry 的 clip（imported media）统一归类为 "not pinned"——符合 `find_pinned_clips` 的原有行为。

`onlyReferenced` 的判定下沉到 `AssetsQuery.kt` 的 `anyRef: Set<String>`——并集覆盖 clip refs + Video clip 的 LUT filter refs + lockfile entries。这是比现有 `inUseByClips`（只算 clip refs）更广的定义，匹配 `find_unreferenced_assets` 的 "safe-to-delete" 契约。

两个 filter 经过 `rejectIncompatibleFilters` 在 dispatcher 层显式校验 select：`onlyPinned` 只允许 timeline_clips，`onlyReferenced` 只允许 assets。typo 场景（LLM 写错 select）立即 loud-fail。

**文件动作**：
- 修改：`ProjectQueryTool.kt`（Input 加 2 字段 + schema + helpText + rejectIncompatibleFilters 2 条）。
- 修改：`query/TimelineClipsQuery.kt`（for-loop 加 `if (input.onlyPinned != null && !matchesPinned(...)) continue` + 新 helper）。
- 修改：`query/AssetsQuery.kt`（构建 `anyRef` set + sequence filter + scopeBits 增两分支）。
- 删除：`FindPinnedClipsTool.kt`（130 行）、`FindUnreferencedAssetsTool.kt`（258 行）。
- 删除：`FindPinnedClipsToolTest.kt`、`FindUnreferencedAssetsToolTest.kt` 两个测试文件。
- 测试新增：`ProjectQueryToolTest` 里 6 个新 case 覆盖 `lockfileFixture()` + onlyPinned true/false、onlyPinned reject on wrong select、onlyReferenced true/false、onlyReferenced reject on wrong select。
- 5 端 AppContainer（CLI / Desktop / Server / Android / iOS）删 2 行 import + 2 行 `register(...)` 每个。
- doc 交叉引用：`DescribeClipTool.kt` KDoc 里 `find_pinned_clips` → `project_query(select=timeline_clips, onlyPinned=true)`。

工具数量净变化：**-2 + 0 = 净 -2**。LLM 每 turn tool spec 成本估算：
- 旧 `find_pinned_clips` spec ~250 tokens + `find_unreferenced_assets` spec ~350 tokens = 600 tokens。
- 新 `project_query` 的 helpText 和 schema 多出来的字段描述 ~120 tokens（onlyPinned + onlyReferenced 的 JSON schema + help-line 补充）。
- **净节省 ~480 tokens/turn**。

**Alternatives considered.**

1. **Option A (chosen)**: tri-state Boolean `onlyPinned` / `onlyReferenced` filter，对应 `true` / `false` / `null` 语义清晰。优点：UI / LLM 语义对称（`onlyX=true` 和 `onlyX=false` 是互补查询；`null` 是 opt-out）；实现只需在现有 filter-chain 里插一行；符合本项目 `onlyNonEmpty` / `onlyUnused` / `onlySourceBound` 已有惯例。缺点：`onlyX=false` 的语义并非"无 X"，而是"非 X"——对 "`onlyPinned=false`" 的新 LLM 读起来可能以为"排除 pinned"（实际正是这个含义）。文档里 helpText 和 schema description 显式写清楚。
2. **Option B**: 引入 `pinnedFilter: PinnedFilterMode`（Any/Only/Never sealed enum）替代 Boolean。拒绝：`@Serializable sealed` 在 JSON schema 里渲染成 oneOf 或 enum string，LLM 要学额外的值集（"any" / "only" / "never"）；Boolean 三态（null = any）和 Kotlin serialization / JSON 的 nullable 约定一致，LLM context 更短。
3. **Option C**: 新增 `select=pinned_clips` / `select=orphan_assets` 而不是 filter。拒绝：select 空间会膨胀成"{tracks, timeline_clips, assets, pinned_clips, orphan_assets, ...}"——每个"view"都一行。而 filter 是**正交**的维度，可以和 `trackKind` / `kind` 组合（"只看 pinned 的 audio 片段"），select-only 方案组合要写多层嵌套。
4. **Option D**: 只折 `find_pinned_clips`，`find_unreferenced_assets` 因为 "sortBy / limit + broader reference set" 保留独立。拒绝：sortBy / limit 在 `project_query(select=assets)` 里本来就有；broader reference set 只是 `AssetsQuery` 的内部 refCount 计算扩展，不需要新的工具。
5. **Option E**: 保留 2 个 find tool 作为 helper，实现内部都调 `project_query`。拒绝：tool count 不减、LLM spec cost 不降、§3a.1 / §5.2 信号原样。属于"码侧整洁但不解决债"的 antipattern。
6. **Option F**: 把 `onlyReferenced` 定义为和 `onlyUnused` 互斥（前者更广）并 deprecate `onlyUnused`。拒绝：`onlyUnused` 的语义是 "zero clip references"（和 `inUseByClips` 一致）；`onlyReferenced` 是 "any reference anywhere"（含 lockfile / filter）。两者回答不同问题；共存有价值。

**Coverage.**

- `ProjectQueryToolTest` 新增 `lockfileFixture()` + 6 个用例：
  - `onlyPinnedTrueReturnsOnlyPinnedClips` — 三个 Video clip + 一个 Text clip 的 fixture；`onlyPinned=true` 只返回 c-pinned。
  - `onlyPinnedFalseReturnsEverythingExceptPinned` — 同 fixture；`onlyPinned=false` 返回 c-unpinned + c-imported + c-text（共 3）。
  - `onlyPinnedRejectedOnNonTimelineClipsSelect` — `select=assets, onlyPinned=true` → loud-fail。
  - `onlyReferencedFalseReturnsOnlyOrphans` — fixture 有 5 assets（a-pinned/a-unpinned/a-imported 被 clip 引用；a-lockfile-only 被 lockfile 引用；a-truly-orphan 无引用）；`onlyReferenced=false` 只返回 a-truly-orphan。
  - `onlyReferencedTrueSkipsTrulyOrphanedOnly` — `onlyReferenced=true` 返回 4 个，排除 a-truly-orphan。同时验证 a-lockfile-only 被算作 referenced（broad 定义）。
  - `onlyReferencedRejectedOnNonAssetsSelect` — `select=timeline_clips, onlyReferenced=true` → loud-fail。
- 既有 ~40 个 `ProjectQueryToolTest` 测试不变。
- 删除的 `FindPinnedClipsToolTest` / `FindUnreferencedAssetsToolTest` 的所有语义覆盖都被新 6 case 吸收。
- `./gradlew :core:jvmTest` 全绿（`--rerun-tasks` 确认新增 test 都跑）。
- `./gradlew :core:ktlintCheck` 全绿（`ktlintFormat` 自动清了 import 顺序）。
- 4 端构建：iOS sim / Android APK / Desktop / Server / JVM core 全部通过。

**Registration.** 5 端 `AppContainer` 各自删 2 行 import + 2 行 `register(...)`，净删除 4 行每端。无新注册。

**§3a 自查.**
1. Tool count: **-2**（2 删 + 0 新）。PASS。§3a.1 显式收益，是本债的修复目标。
2. Define/Update: N/A。
3. Project blob: 不动 Project。PASS。
4. 状态字段: `onlyPinned` / `onlyReferenced` 都是 tri-state（`null` / `true` / `false`）—— 不是二元。PASS。
5. Core genre: 无 genre 名词；filter 名都是结构性。PASS。
6. Session/Project binding: 走 `ctx.resolveProjectId(input.projectId)`（上一轮落地），兼容新 optional 语义。PASS。
7. 序列化向前兼容: `Input` 新字段都带 `= null` default；旧 JSON 调用（无 onlyPinned / onlyReferenced）照样解。PASS。
8. 5 端装配: 全部同步删除。PASS。
9. 测试语义覆盖: 6 个新 case 覆盖 true / false / reject-on-wrong-select 边界；含跨 select 错误路径。PASS。
10. LLM context 成本: **净 -480 tokens/turn**。PASS（收益方向）。

**Non-goals / 后续切片.**
- `find_stale_clips` 不动——`sourceBinding` + DAG contentHash 遍历不是 projection。未来可以做 `project_query(select=stale_clips)` 但 stale 推导需要 context state（project.source.revision / 上次 render 时间），和现有 filter-after-sort 路径不同。分开做。
- `onlyReferenced` 当前 reference 集合包含 "lockfile entry"——策略上代价是 lockfile 中的 orphan（某个 asset 已从 timeline 删除但 lockfile 尚未 GC）会被算作 "referenced"。这符合 `find_unreferenced_assets` 的原契约（safe-to-delete = 什么都没引用），但调用方如果想要 "strictly unused by clips" 应该用 `onlyUnused=true`。两个 filter 回答不同问题，共存。
- 把所有 `find_*` 残留工具（`find_stale_clips`）的 helpText 里引用更新："find_pinned_clips" 在 `DescribeClipTool` 已更新，其他位置（system prompt / 其他 helpText）可在下轮清理；这轮不扩 scope。
