## 2026-04-21 — debt-split-projectquerytool：把 602 行的 ProjectQueryTool.kt 拆成 dispatcher + 3 个 per-select 文件（Rubric 外 / R.5.3 长文件）

Commit: `e2c6fa7` (pair with `docs(decisions): record choices for debt-split-projectquerytool`).

**Context.** `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/ProjectQueryTool.kt` 602 行，是 R.5.3 长文件扫描当前仍高于 500 行阈值的 top 命中者（和刚完成的 `TaleviaSystemPrompt` 743 → 48 同级债）。三个 select（`tracks` / `timeline_clips` / `assets`）各自有一个 `runXxx` + `buildXxxRow`（外加 assets 的 `classify`）全塞同一个 class body，三种无关的 filter / sort / echo 逻辑互相挤在一起。每次给某个 select 加 filter（例如 `unify-project-query` 的后续 `onlyPinned` / `onlyReferenced`）都要在 600 行里"先找到对应 `runXxx`"，PR diff 也因为上下文过长变得嘈杂。

拆分是**纯码侧 dev-UX 改进**：LLM 看到的 tool spec（`id` / `helpText` / `inputSchema` / `Input` / `Output`）一个字节都没变；`ToolResult.outputForLlm` / `data` 结构完全一致；`ProjectQueryTool.TrackRow.serializer()` / `ClipRow.serializer()` / `AssetRow.serializer()` 的公开 API 也不变（测试依赖这些 serializer 反序列化 `rows: JsonArray`）。

**Decision.** 新建 `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/query/` 目录，按 select 切 3 个文件 + 1 个共享 helper 文件：

| 文件 | 内容 | 行数 |
|---|---|---|
| `ProjectQueryTool.kt` | dispatcher 壳：`Input` / `Output` / `TrackRow` / `ClipRow` / `AssetRow` + `execute()` 分派 + `rejectIncompatibleFilters()` + 公开 `SELECT_*` 常量 + `inputSchema` | 302 |
| `query/QueryHelpers.kt` | `internal` 常量（`VALID_TRACK_KINDS` / `ASSET_KINDS` / `TRACK_SORTS` / `CLIP_SORTS` / `ASSET_SORTS`）+ `trackKindOf` / `encodeRows` / `Duration.toSecondsDouble()` | 36 |
| `query/TracksQuery.kt` | `internal fun runTracksQuery(...)` + 私有 `buildTrackRow` | 100 |
| `query/TimelineClipsQuery.kt` | `internal fun runTimelineClipsQuery(...)` + 私有 `buildClipRow` | 143 |
| `query/AssetsQuery.kt` | `internal fun runAssetsQuery(...)` + 私有 `classifyAsset` + `buildAssetRow` | 113 |

`ProjectQueryTool.execute` 变成一行 `when`:

```kotlin
return when (select) {
    SELECT_TRACKS -> runTracksQuery(project, input, limit, offset)
    SELECT_TIMELINE_CLIPS -> runTimelineClipsQuery(project, input, limit, offset)
    SELECT_ASSETS -> runAssetsQuery(project, input, limit, offset)
    else -> error("unreachable — select validated above: '$select'")
}
```

3 个 per-select 函数签名一致：`(Project, ProjectQueryTool.Input, limit: Int, offset: Int) -> ToolResult<ProjectQueryTool.Output>`。Row data class 保留在 `ProjectQueryTool` 里（nested），per-select 文件以 `ProjectQueryTool.TrackRow` / `.ClipRow` / `.AssetRow` 方式引用——这一点必须保留：测试里直接用 `ProjectQueryTool.TrackRow.serializer()` 解码 `rows: JsonArray`，是公开 API 的一部分。常量 `SELECT_TRACKS` / `SELECT_TIMELINE_CLIPS` / `SELECT_ASSETS` 也留在 `companion` 里（`const val` 公开，per-select 文件写 `ProjectQueryTool.SELECT_TRACKS` 消费），保持入口单一。

私有 companion 常量（`ALL_SELECTS` / `DEFAULT_LIMIT` / `MIN_LIMIT` / `MAX_LIMIT`）只被 `execute()` 用，留在主文件里当 `private`。`VALID_TRACK_KINDS` / `ASSET_KINDS` / `TRACK_SORTS` / `CLIP_SORTS` / `ASSET_SORTS` 原本是主文件的 private companion 常量，拆到 `QueryHelpers.kt` 后变成 package-private `internal val`——正好三个 per-select 文件共用，又不污染到其它包。

**Byte-identical 不变量。** 这次拆分和 `debt-split-taleviasystemprompt` 的约束类似，但目标不是"LLM 输入字节相等"而是"公开行为字节相等"：

1. `Tool.id` / `Tool.helpText` / `Tool.inputSchema` / `Tool.inputSerializer` / `Tool.outputSerializer` 全没动——这四样 tool spec 是 LLM 直接看到的。
2. `execute()` 返回的 `ToolResult.title` / `outputForLlm` / `data` 三个字段，对同一 Input 产生完全相同的字符串和 Output 对象：所有构造调用沿用原有 named / positional 参数顺序；`title` 里的 `"project_query tracks (${page.size}/${filtered.size})"` / `"project_query timeline_clips (...)"` / `"project_query assets ($kindFilter)"` 格式原样搬过去；`outputForLlm` 的 markdown body（tracks 的 `- #${r.index} [${r.trackKind}/${r.trackId}] $span`、clips 的 `- [${c.trackKind}/${c.trackId}] ${c.clipId} @ ...`、assets 的 `Project ...: N matching assets, returning M (offset O, scopeBits)`）都是直接搬运，无格式调整。
3. `rejectIncompatibleFilters` 留在主文件且逻辑 0 改动——filter 互斥表是 schema 的一部分，不能 silent 迁移。

没有新增测试来锁定这些，但 `ProjectQueryToolTest` 对三个 select 各自有 happy path + 异常路径覆盖（filter 误用、空 project、分页、sort），如果任何 `title` / `outputForLlm` 字节变化或 `Output` 字段语义漂移直接红。复用既有测试比新写"拆分前后字节对比"划算——后者需要存一份旧字符串 snapshot 在测试文件里，长期维护成本大于收益。

**Alternatives considered.**

1. **Option A (chosen)**: dispatcher 主文件 + `query/` 子目录下每 select 一个文件 + 共享 helpers。优点：每个文件 ≤ 143 行，单 select 可独立审阅；主文件 302 行一屏半读完；`internal fun runXxxQuery` 签名一致，将来 `unify-project-query` 后续加新 select（比如 `lockfile_entries`）就是新增一个文件+一行 `when` 分支。缺点：多 4 个文件，Input/Output 与实际实现分离——但 nested row + 顶层函数的组合已经是 Kotlin 标准做法（如 `kotlinx.serialization` 的 `Json` + companion 函数们）。
2. **Option B**: 把 per-select 函数做成 `sealed interface ProjectQueryHandler` 的实现类，`execute()` 按 select 取 handler 跑。拒绝：引入 polymorphism 抽象，为"将来可能的 handler 动态注册"优化——`Anti-requirements` 里明确禁止"为假设的未来需求设计"；当前只有三个 select，dispatcher `when` 更直接。并且 handler 抽象要求签名对齐，会把 `trackKind` filter 这种只有两个 select 需要的字段塞进统一 Input contract，反而让 `Input` 更糟糕。
3. **Option C**: 按"logic 一层 / schema 一层"拆：主文件只保留 schema + Input/Output，execute 全在 `ProjectQueryImpl.kt`。拒绝：`execute()` 只有 15 行分发逻辑，挪走后主文件剩 200 行纯数据类 + schema，`ProjectQueryImpl` 成为"一个 400 行的大 class"——债没消除，只是换了文件名。
4. **Option D**: `companion object` 里塞 private `runTracks` / `runTimelineClips` / `runAssets` top-level extension functions，file 级 `private` 仍在主文件。拒绝：文件长度一点没变，仅把 visibility 从 `private` 改成 `private`（无变化），是做样子不是拆债。
5. **Option E**: 把 row data class 也搬到 per-select 文件，主文件只剩 Input/Output + execute。拒绝：`ProjectQueryTool.TrackRow.serializer()` 是测试和潜在 UI 消费点使用的公开 API，搬到子文件就变成 `io.talevia.core.tool.builtin.project.query.TrackRow`（import 路径变化），破坏向后兼容且无明显收益——nested row 是"这 3 个类型属于 ProjectQueryTool 公开契约"的语义信号，保持 nested 更清晰。

**Coverage.**

- 既有 `ProjectQueryToolTest` 对三个 select 的 happy path / filter 错误 / 分页 / sort 测试**一字未改**。拆分丢掉任何一行语义都会让它们红（3 个 select × 4 类测试 = 12+ 断言覆盖）。
- `:core:jvmTest` 全绿（约 25s 完成）。
- `:core:ktlintCheck` 全绿。
- 五端装配面编译全绿：iOS sim (`compileKotlinIosSimulatorArm64`)、Android APK (`apps:android:assembleDebug`)、Desktop (`apps:desktop:assemble`)、Server (`apps:server:assemble`)、JVM core。各平台 AppContainer 调用 `ProjectQueryTool(projects)` 的方式完全不变。

**Registration.** 无 tool 注册变化——纯重构。`ProjectQueryTool` 构造器签名（`(projects: ProjectStore)`）不变，5 个 `AppContainer`（CLI / Desktop / Server / Android / iOS）一行都不用改。

**§3a 自查.**
1. Tool count: 0 变化（1 个 `ProjectQueryTool` 依然占一个 `*Tool.kt`）。PASS。
2. Define/Update: N/A（纯读）。PASS。
3. Project blob: 不动 Project。PASS。
4. 状态字段: N/A。
5. Core genre: 拆出来的 `runTracksQuery` / `runTimelineClipsQuery` / `runAssetsQuery` / `QueryHelpers` 不含 `character_ref` / `style_bible` / `brand_palette` 这类 genre 名词，纯结构性。PASS。
6. Session/Project binding: `Input` 仍接 `projectId: String`——`tool-input-default-projectid-from-context` 那条 backlog 会把它统一改成可选 from-context，这次不动（和拆分正交）。PASS。
7. 序列化向前兼容: `Input` / `Output` / `TrackRow` / `ClipRow` / `AssetRow` 的字段顺序、可空性、默认值、`@Serializable` 全没动。PASS。
8. 5 端装配: 不变（构造器签名不动）。PASS。
9. 语义测试: 依赖既有 `ProjectQueryToolTest`（3 select × 多路径覆盖）。PASS。
10. LLM context 成本: **零变化**——`helpText` / `inputSchema` / `id` 一字节未改，每 turn token 数一模一样。PASS。

**Non-goals / 后续切片.**
- P1 `debt-split-agent-kt`（`core/agent/Agent.kt` 581 行）是类似的同档债，本 cycle 不合并处理——两个拆分互不依赖，分开做 PR 更易审阅。
- `unify-project-query` 后续可能并入的新 select（`lockfile_entries` / `transitions` / `stale_clips` 的 projection 部分），按今天这个架构只需新增 `query/XxxQuery.kt` + 一行 `when` 分支。dispatcher 外壳已经给将来留好位置。
- `promote-find-tools-to-project-query`（P2）把 `find_pinned_clips` / `find_unreferenced_assets` 折进 `timeline_clips` / `assets` select 的新 filter，也会受益于这次拆分——直接在 `TimelineClipsQuery.kt` / `AssetsQuery.kt` 里加 filter 而不用碰 dispatcher 文件。
