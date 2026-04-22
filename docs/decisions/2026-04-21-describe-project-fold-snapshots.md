## 2026-04-21 — describe-project-fold-snapshots：`describe_project` 输出加 `recentSnapshots` 列表（VISION §5.4）

Commit: `352d473`

**Context.** `describe_project` 的 Output 今天含 `snapshotCount: Int`（3 个 snapshot 的计数），但没有 snapshot 列表 —— UI 要在左侧面板渲染 "最近 3 个保存点" 还要再 call `list_project_snapshots`（或 `project_query(select=project_snapshots)`）凑一个第二请求。describe_project 的定位本来就是 "compact, one-paragraph summary across every axis"（见 KDoc），缺这一块让 UI 路径多一跳。Backlog bullet 原文："给 `describe_project.Output` 加 `recentSnapshots: List<SnapshotSummary>` 字段（cap 3-5 条，按 capturedAt DESC），UI 一次读到。Rubric §5.4。"

**Decision.** 给 `DescribeProjectTool.Output` 加两个字段：

```kotlin
@Serializable data class SnapshotSummary(
    val id: String,
    val label: String,
    val capturedAtEpochMs: Long,
)

@Serializable data class Output(
    // ... 既有字段 ...
    val snapshotCount: Int,
    /** Up to MAX_RECENT_SNAPSHOTS = 5 newest-first. Default empty. */
    val recentSnapshots: List<SnapshotSummary> = emptyList(),
    val outputProfile: ProfileSummary?,
    val summaryText: String,
)

companion object {
    const val MAX_RECENT_SNAPSHOTS: Int = 5
}
```

Populated in `execute` by `project.snapshots.sortedByDescending { it.capturedAtEpochMs }.take(5).map { SnapshotSummary(...) }`。新 `MAX_RECENT_SNAPSHOTS = 5` 常量作为公开 API（测试引用 + 未来调整时的单点 touchpoint）。

**字段选择**：只含 `(id, label, capturedAtEpochMs)` —— 不带 `project: Project` payload（会炸 describe 输出大小；UI 要展开完整 snapshot 走 `restore_project_snapshot` 或 `list_project_snapshots`）。对应 backlog bullet 的 "SnapshotSummary" 形状。

**Cap = 5**：覆盖典型 UI "last few saves" 列表；每条 summary ≈ 50 字节，5 条 ≈ 250 字节，LLM 能在 1 round-trip 内消化。超 5 条的项目 snapshotCount 仍显示完整计数（"11 snapshots"），rest 走 `list_project_snapshots`。

**summaryText 不变**：`renderSummary` 依然写 "N snapshots" —— 把 latest label 塞进 summary 会让文本长度从 ~300 跳到 ~400 chars，且 LLM 能看到 `recentSnapshots` 结构化数据自己渲染更灵活。保持 summaryText 稳定对 LLM 记忆验证和 prompt 里既有的"describe_project 这个 shape"约定有益。

**Alternatives considered.**

1. **Option A (chosen)**: 新 `SnapshotSummary` nested data class + `recentSnapshots: List<SnapshotSummary> = emptyList()` 字段。优点：精确匹配 bullet 指定结构；`= emptyList()` default 让既有 Output JSON decoders forward-compat；cap 5 权衡输出大小 + UI 覆盖面；tests 验证 cap + sort + empty 三种情况。缺点：加 13 行 KDoc 的 SnapshotSummary 类，但是 UI 侧透明 —— LLM 看到的是一个小列表。
2. **Option B**: 直接塞 `List<ProjectSnapshot>`（复用 domain type）。拒绝：ProjectSnapshot 含 `project: Project` full payload —— describe_project 的 Output 会 inflate 到 MB 级（snapshot 数 × 整个 project blob）。完全破坏 tool 的 "compact summary" 契约。
3. **Option C**: 不加字段，改用 `summaryText` 拼 "latest save: 'label-x'" 一行。拒绝：UI 没法从文本 parse；LLM 消费 summaryText 和 structured recentSnapshots 需求正交；bullet 明确要 structured list。
4. **Option D**: Cap 3 (bullet 原文 "3-5")。拒绝：5 比 3 多 2 条成本几乎可忽略（~100 字节），但 UI 典型 "快速恢复" 面板放 5 行更常见（3 有时只显示当次 + 前一次，看不到 "大改之前那个版本"）。5 是 backlog 区间的上限，选它给 UI 最多信息。
5. **Option E**: 用 `Project.snapshots.takeLast(5).reversed()` —— 依赖 snapshots 列表已经按时间升序（现状 `ProjectStore.upsert` 按插入顺序 append）。拒绝：snapshots 存储顺序是实现细节，不是 contract；`capturedAtEpochMs DESC` 是语义正确的 "最近"；显式 sort 让新插 out-of-order snapshot（例如 rebase 合进来的）也能正确排。测试 `recentSnapshotsCapsAtFiveOnHighCount` 专门覆盖这个边界。
6. **Option F**: 扩 `Input` 加 `snapshotLimit: Int? = null` 让调用方自己选。拒绝：increases LLM schema surface without clear driver（UI 调用一致固定为 5，更多 snapshots 走 list_project_snapshots 专门分页）；选用硬 cap 5 + `snapshotCount` 让调用方判断 "是否要 follow-up list_snapshots"。

**Coverage.**

- 既有 `DescribeProjectToolTest` 15+ test 保持不改（Output 字段加值是 forward-compat；既有 assertion 都不触 `recentSnapshots`）。
- `snapshotCountReflectsSavedSnapshots` 扩了 3 个新 assertions：`recentSnapshots` 的 id / label / capturedAtEpochMs 列表。
- **新增 2 tests**：
  - `recentSnapshotsEmptyWhenNoSnapshots` — 空 snapshots project 返回 `emptyList()`。
  - `recentSnapshotsCapsAtFiveOnHighCount` — 7 个 snapshots 且 capturedAt 顺序 shuffle，cap 后得到 5 个按时间 DESC 正确排序（§3a.9 边界 case：insertion order ≠ output order）。
- `./gradlew :core:jvmTest` + `:apps:server:test` + `:core:ktlintCheck` 全绿；4 端构建全绿。

**Registration.** 无新 tool；5 端 AppContainer 无变动。纯 schema 扩展。

**§3a 自查.**

1. 工具数量: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 `Project`（read-only tool）。PASS。
4. 状态字段: 无新 flag。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: projectId 已 optional。PASS。
7. 序列化向前兼容: `recentSnapshots = emptyList()` default；旧 JSON decode 时字段缺失 → 空列表。`SnapshotSummary` 是新 `@Serializable` nested class —— 仅 Output 包含，Output 不持久化。PASS。
8. 五端装配: 无 tool 变更。PASS。
9. 测试语义覆盖: happy path + 空 snapshot + cap-on-large（带乱序 capturedAt）—— 3 条增量测试覆盖关键边界。PASS。
10. LLM context 成本: Output schema 加 `recentSnapshots: List<SnapshotSummary>` + `SnapshotSummary.serializer()`，生成的 outputSerializer 给 LLM schema 加约 120 tokens/turn（永久成本）。每次 describe_project 调用的 outputForLlm 是 summaryText（不变）—— data 字段通过 tool_use 反馈给 LLM 的是 structured JSON（空 snapshots 时只多 `"recentSnapshots": []` ~20 tokens；有 snapshot 时 5 条约 300 tokens）。作为 per-call cost 合理，且 UI 一跳拿到信息的收益更大。PASS。

**Non-goals / 后续切片.**

- **不改 `list_project_snapshots`** —— 独立 tool 保持用作完整列表 + 分页入口。本改动只加 "most recent 5" 的 fast path。
- **不扩 `SnapshotSummary` 字段**（例如 `sizeBytes` / `capturedByTool`）—— 当前 domain `ProjectSnapshot` 只有 4 字段（id / label / capturedAtEpochMs / project），full 已经暴露。size / tool attribution 要先在 domain 层加字段，是独立 cycle。
- **不加 `describe_project(snapshotLimit=X)`** —— 当前 cap 5 硬编码是合理的 UI 契约；扩 Input 等具体 UI driver 出现。
- **Follow-up**：UI Desktop/iOS 侧接入 `recentSnapshots` 渲染 —— 属于 UI 层任务，Core 端已准备好。
