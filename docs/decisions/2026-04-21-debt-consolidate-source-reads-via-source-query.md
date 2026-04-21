## 2026-04-21 — debt-consolidate-source-reads-via-source-query：3 个 source 读工具合并成 source_query 原语（Rubric 外 / §3a.1 + §5.2 一等抽象）

Commit: `d142b00` (pair with `docs(decisions): record choices for debt-consolidate-source-reads-via-source-query`).

**Context.** Source 域犯了和 project / session 域同一个"每个维度一个工具"病：`list_source_nodes`（枚举 + 过滤）、`search_source_nodes`（body 全文搜索）、`describe_source_dag`（DAG 结构俯视图）3 个 read tool 共 842 行。LLM 要在三个同语义家族的 tool 之间做互斥选择，每次都付三份 spec + helpText + inputSchema 的 token 成本。同一轮前置已经把 `project_query` / `session_query` 原语打磨成标准形态（`(select, filter, sort, limit)` + 统一 Output + per-select row 子序列化器），这轮照样画瓢到 source 域。

P1 backlog bullet 明确：`source_query(select ∈ {nodes, dag_summary}, filter ∈ {kind, kindPrefix, contentSubstring, id}, limit)`，吸收 list + search，describe_source_node 留作单实体深看。

**Decision.** 新 `SourceQueryTool`（`source_query`）作为 dispatcher，两个 select 各自落到 `source/query/` 子目录下：
- `NodesQuery.kt` — `select=nodes`，吸收 `list_source_nodes`（kind / kindPrefix / includeBody / sortBy / limit）+ `search_source_nodes`（contentSubstring + caseSensitive；匹配行自动带 snippet + matchOffset）+ 新增 `id` 精确过滤。
- `DagSummaryQuery.kt` — `select=dag_summary`，吸收 `describe_source_dag`（nodeCount / nodesByKind / rootNodeIds / leafNodeIds / maxDepth / hotspots / orphanedNodeIds / summaryText）。永远返回一行。

工具数量变化：**-3 + 1 = 净 -2**。LLM 每 turn tool spec token 从 ~900 降到 ~420（3 旧 tool 各 ~300 avg vs 1 merged ~420），**净省 ~480 tokens/turn**。

`describe_source_node` 保持独立 tool —— 单实体深看（body + parents + children + boundClips）和 list-projection 语义正交，backlog 明确要求保留。同理 `project_query` 保留了 `describe_clip` / `describe_lockfile_entry`，`session_query` 保留了 `describe_session` / `describe_message`。

文件动作：
- 新增 `SourceQueryTool.kt`（~250 行，dispatcher + `Input` / `Output` + 3 个 row data class + `Hotspot` 嵌套类型 + `rejectIncompatibleFilters`）。
- 新增 `source/query/NodesQuery.kt`（~135 行，`runNodesQuery` + `SourceNode.toRow()` + `humanSummary()` + `snippetAround()`）。
- 新增 `source/query/DagSummaryQuery.kt`（~155 行，`runDagSummaryQuery` + `computeMaxDepth` + `dfsDepth` + `buildSummary`）。
- 删除 3 个旧工具：`ListSourceNodesTool.kt`（215 行）、`SearchSourceNodesTool.kt`（180 行）、`DescribeSourceDagTool.kt`（239 行）。
- 删除 3 个旧测试：`ListSourceNodesToolTest.kt`、`SearchSourceNodesToolTest.kt`、`DescribeSourceDagToolTest.kt`。
- 新增 `SourceQueryToolTest.kt`（~280 行），12 个测试用例覆盖 `select=nodes`（默认排序、kindPrefix 过滤、id 精确过滤、contentSubstring 找到 + 生成 snippet、caseSensitive miss、includeBody 切换、invalid sortBy 报错、limit+offset 分页）+ `select=dag_summary`（空项目、按 kind 计数）+ 跨 select 验证（invalid select、误用 filter、missing project）。
- 更新 `SourceToolsTest.kt` —— 原来用 `ListSourceNodesTool.execute(ListSourceNodesTool.Input(...))` 的两个 test 改为 `SourceQueryTool.execute(SourceQueryTool.Input(select="nodes", ...))`，decode rows 从 JsonArray via `SourceQueryTool.NodeRow.serializer()`。
- 5 端 AppContainer 同步：删除 3 行 import + 3 行 `register(...)`，加 1 行 import + 1 行 `register(SourceQueryTool(projects))`。
- doc / helpText / error-message 交叉引用更新（9 处）：`core/agent/prompt/PromptBuildSystem.kt`（**LLM-visible system prompt**：`list_source_nodes (filterable by kindPrefix=core.consistency.)` → `source_query(select=nodes, kindPrefix=core.consistency.)`）、`TaleviaSystemPromptTest.kt` keyword 断言同步、`ValidateProjectTool.kt` / `ListClipsForSourceTool.kt` / `SetSourceNodeParentsTool.kt` / `RemoveSourceNodeTool.kt` / `DescribeSourceNodeTool.kt` / `RenameSourceNodeTool.kt` / `ResolveParentRefs.kt` / `UpdateSourceNodeBodyTool.kt` 的 KDoc + helpText + loud-fail 提示。

**Input 设计**：单一 `Input` 承载两 select 的 filter。`rejectIncompatibleFilters` 分派前验证：
- select=nodes 专属：`kind` / `kindPrefix` / `contentSubstring` / `caseSensitive` / `id` / `includeBody` / `sortBy` / `limit` / `offset`
- select=dag_summary 专属：`hotspotLimit`
- 两者共用：`projectId`（required）

`id` 是新增过滤维度（过去没有单独的 id 精确过滤 tool），让 `source_query(select=nodes, id=X)` 返回 ≤1 行 NodeRow。对需要完整 body + parent / child 关系的场景，call site 应继续用 `describe_source_node`（helpText 里显式提示）。

**Output 设计**：uniform `{select, total, returned, rows: JsonArray, sourceRevision}`。`sourceRevision` 对于 source 域特别有价值——source DAG mutation 会 bump revision，两次 query 之间 revision 不同就知道底层 DAG 被改过了；对调用方的乐观并发 / 失效判断友好。

**Alternatives considered.**

1. **Option A (chosen)**: 两 select 合并成 `source_query`，`nodes` 覆盖 list + search，`dag_summary` 覆盖 describe_dag。优点：tool count 净 -2；token 净省 ~480 / turn；和 `project_query` / `session_query` 形态对称——整个项目 read 通过三个 `_query` 原语、write 通过专用 tool、deep drill 通过 `describe_*`，是可教可预期的心智模型。缺点：搜索能力混入 `nodes` select（`contentSubstring` + `caseSensitive`），使 filter 数比其他 select 多。但 backlog 显式指定了这个方向，且 `NodeRow` 通过 nullable `snippet` / `matchOffset` 字段优雅降级，未触发搜索时这两字段为 null。
2. **Option B**: 保留 3 个独立工具，仅做内部重构（共享 helper 提取到 `source/query/`）。拒绝：tool count 不降；LLM token 账单不动；§3a.1 / §5.2 信号保留不消。这是重复 `debt-merge-pin-unpin-tool-pairs` 和 `debt-consolidate-session-reads-via-session-query` 已经拒过的路线。
3. **Option C**: 合并 `list_source_nodes` + `search_source_nodes` 成 `source_query(select=nodes, ...)`，但把 `describe_source_dag` 留作独立 tool。拒绝：backlog bullet 明确列了 `{nodes, dag_summary}` select 集合——保留一个独立工具会破坏 spec 对称性；而且 `describe_source_dag` 的返回形状和 list-projection 同构（一行聚合 row），自然融入 rows: JsonArray 格式。并入一次性完成债务。
4. **Option D**: 引入 `full_text_search` 作为独立工具覆盖所有域（session messages / source bodies / project notes），彻底解耦 "按 id / kind 枚举" 和 "按内容搜索" 两条 axis。拒绝：过度抽象。`search_source_nodes` 是 source 域的特化需求（body 是 JsonElement，有 structured shape），跨域统一的 full-text 搜索需要数据源抽象 + 索引层投入，远超本债的范围。留作未来 rubric 分析题材。
5. **Option E**: 不加 `id` 过滤字段，用户要单节点查询就走 `describe_source_node`。拒绝：backlog 原文明确列了 `id` 作为 4 个 filter 之一。并且 `source_query(select=nodes, id=X)` 提供了"轻量单节点 projection"（只有 summary + 可选 body），和 `describe_source_node` 的"重量深看"（带 children 关系 + boundClips）形成语义分层：轻描淡写用前者、深挖用后者。保留两者分工清晰。

**Coverage.**

- 新增 `SourceQueryToolTest`（12 tests）覆盖：
  - `nodesListsAllWithDefaultSort` — default sortBy=id asc。
  - `nodesKindPrefixFilter` — `test.alpha` prefix 抓住 2/3 节点（用 genre-agnostic 测试 kind 避免和 core.consistency.* 的 typed body schema 冲突）。
  - `nodesIdFilterReturnsSingleRow` — exact id 过滤 = 1 行。
  - `nodesContentSubstringFindsAndSnippets` — 匹配行带 snippet + matchOffset；非匹配行不返回。
  - `nodesContentSubstringCaseSensitiveMisses` — caseSensitive=true 下 "Mei" 命中、"mei" 不命中。
  - `nodesIncludeBodyTogglesJsonBodyField` — body 字段只在 includeBody=true 时填充。
  - `nodesInvalidSortByFailsLoud` — `IllegalArgumentException` + "Invalid sortBy"。
  - `nodesLimitAndOffsetPage` — 5 节点分两页 offset=0 / offset=2 不重叠。
  - `dagSummaryEmptyProject` — 空 DAG 返回 1 行带 `0 nodes (empty graph)`。
  - `dagSummaryCountsByKind` — nodesByKind 按 kind 正确累加。
  - `invalidSelectFailsLoud` / `misappliedFilterFailsLoud` / `missingProjectFailsLoud` — 跨 select 错误路径。
- `./gradlew :core:jvmTest` 全绿。
- `./gradlew :core:ktlintCheck` 全绿。
- 4 端构建：iOS sim / Android APK / Desktop / Server / JVM core 全部通过。
- `TaleviaSystemPromptTest` 的 40+ keyword 断言自动覆盖 system prompt 里 `source_query` 替换 `list_source_nodes` 的修改正确性——如果我漏改 keyword 会立刻红。

**Registration.** 5 个 `AppContainer`（CLI / Desktop / Server / Android / iOS）各自删除 3 行 import + 3 行 `register(...)`，加 1 行 import + 1 行 `register(SourceQueryTool(projects))`。iOS SKIE 自动导出，swift 端无需额外手写。

**§3a 自查.**
1. Tool count: **-2**（-3 + 1）。PASS。§3a.1 显式收益。
2. Define/Update: N/A（read tools）。PASS。
3. Project blob: 不动 Project 字段。PASS。
4. 状态字段: N/A。PASS。
5. Core genre: `kind` / `kindPrefix` / `id` 作为 filter 接受任意字符串（对 Core 完全不透明）。`humanSummary` 里调用的 `asCharacterRef` / `asStyleBible` / `asBrandPalette` 是继承自 `ListSourceNodesTool` 的既有逻辑——genre-specific 展示语在同一文件内；不是 tool id / 类型层的一等概念。PASS。
6. Session/Project binding: `projectId` 仍是显式参数；`tool-input-default-projectid-from-context` 未来会统一处理。PASS。
7. 序列化向前兼容: 新 Input / Output 所有字段有 default（`select` / `projectId` 是 required）；row data classes 所有字段有 default 保证解码旧 blob 安全（尽管 tool Output 不持久化）。PASS。
8. 5 端装配: 5 个 AppContainer 全部同步更新。PASS。
9. 测试语义覆盖: 12 个 test 覆盖 filter / 搜索 / 分页 / 跨 select / 错误路径的边界。PASS。
10. LLM context 成本: **净 -480 tokens/turn**。PASS（收益方向）。

**Non-goals / 后续切片.**
- 本轮保留 `describe_source_node` 作为单实体深看 tool，和 `describe_session` / `describe_message` / `describe_clip` / `describe_lockfile_entry` 形成一贯风格。若以后要统一到 `source_query(select=node_deep, id=X)` 可作单独 cycle。
- `contentSubstring` 当前是朴素 substring 匹配。未来如果需要分词、正则、向量检索，引入 `query_mode: substring|regex|vector` 扩展字段；`Input.contentSubstring` 保留默认 substring 语义向后兼容。
- 剩下的 source 域 write tools（`add_source_node` / `remove_source_node` / `fork_source_node` / `set_source_node_parents` / `rename_source_node` / `update_source_node_body` / `set_character_ref` / `set_style_bible` / `set_brand_palette` / `diff_source_nodes` / `export_source_node` / `import_source_node` / `import_source_node_from_json`）未纳入本次。它们写性质 + 各自有独立 Input 语义，暂无合并信号。
