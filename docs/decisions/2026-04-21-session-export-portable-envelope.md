## 2026-04-21 — session-export-portable-envelope：`export_session` tool + SessionEnvelope wire format（VISION §5.4）

Commit: `15b9cb7`

**Context.** `export_project` / `export_source_node` 已经给 Project / Source 层建立了 portable JSON envelope pattern（backup / cross-instance / version control），但 Session（agent 对话 + tool-call 历史）没有 —— 用户想把一段 session 从一台 Talevia 备份到另一台、或 ship 一个预烤的 "tutorial" transcript 给协作者本地 replay，没有工具产生 envelope。`session_query(select=parts, ...)` 能 scroll raw row JSON 但（a）没写文件功能（Core session 层不摸文件 IO），（b）`select=parts` 的 `preview` 按 80 字符截断 Part.Text / Part.Compaction 内容 —— 它是 query primitive 不是 persistence 格式。Rubric §5.4。

Backlog bullet 原文："加 `export_session(sessionId, outputPath)` 打包 `Session` + `Message[]` + `Part[]` 到单一 JSON envelope（同 `export_project.kt` 风格）；配套 `import_session(from)` 留给后续 cycle。+1 tool 需在 decision 里说明（现有 `export_*` 家族命名一致性 + 无法用 `session_query` 替代写出文件）。"

**§3a.1 工具数量净增 +1 justification**：
1. 现有 `export_*` 家族已有 `export_project`（project blob） + `export_source_node`（source subtree）；没有 session 对应物是家族空缺 —— 保持命名一致是 agent schema 学习曲线的刚需（LLM 看到 export_project / export_source_node 自然推 export_session 也该存在）。
2. `session_query(select=parts)` 无法替代：返回 rows 不是文件；preview 截断；没有 sessionized metadata（session title / projectId / parentId / permissionRules 等）。要把 session_query 扩成 "也能写 envelope" 会把它从纯 query primitive 退化成 export + query 混合，违反 `SessionQueryTool.kt` 已建立的 "unified read-only query" 语义。
3. 本 cycle 只加 `export_session` 一个 tool，`import_session` 明确留给后续 cycle（bullet 原文"留给后续 cycle"）—— 本轮净增 **+1 tool**。

**Decision.** 新建 `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/session/ExportSessionTool.kt`（~165 行）+ `SessionEnvelope` @Serializable wire type。完全对齐 `ExportProjectTool` / `ExportSourceNodeTool` 的形态：

**Tool shape**:
```kotlin
class ExportSessionTool(private val sessions: SessionStore) {
    data class Input(
        val sessionId: String? = null,   // optional, default to ctx.sessionId
        val prettyPrint: Boolean = false,
    )
    data class Output(
        val sessionId: String, val title: String, val projectId: String,
        val formatVersion: String,
        val messageCount: Int, val partCount: Int,
        val envelope: String,             // JSON string; caller chains write_file
    )
    companion object {
        const val FORMAT_VERSION = "talevia-session-export-v1"
    }
}

@Serializable data class SessionEnvelope(
    val formatVersion: String,
    val session: Session,
    val messages: List<Message>,
    val parts: List<Part>,
)
```

**No filesystem IO**：bullet 提到 `outputPath` 但 `export_project` / `export_source_node` 都不接受 path —— 都是返回 `envelope: String`，让 agent 链 `write_file` 落盘。保持这个契约：filesystem boundary 归 `fs` tool domain（read_file / write_file），Core session tool 不摸磁盘。这也让 desktop/iOS/android/server 的 `MediaPathResolver` 路径约束得到一致对待。

**Content scope**：
- `session` — full [Session] object（含 permissionRules / parentId / compactingFrom）。
- `messages` — 全部 User / Assistant messages，insertion 顺序（`listMessages` contract）。
- `parts` — 全部 Part (text / reasoning / tool / media / timeline-snapshot / render-progress / step-start / step-finish / compaction / todos)，`includeCompacted = true`（archive 要无损）。
- **不含 Project / Asset** —— 对称决策：`export_project` 不含 session（"sessions 引用 projects, not vice versa"）；`export_session` 不含 project（target instance 应该先 `export_project` / `import_project_from_json` target project，再 import session）。

**forward-compat**：`FORMAT_VERSION = "talevia-session-export-v1"`。未来 `import_session`（follow-up cycle）校验版本，版本 drift 要 fail loud 而不是静默 tolerate（match `ExportSourceNodeTool.FORMAT_VERSION` / `ExportProjectTool.FORMAT_VERSION` 的 pattern）。

**sessionId 默认**：通过 `ctx.resolveSessionId(input.sessionId)` 支持 null → owning session（cycle 18 建立的模式）。最常见场景是 "export the session we're in"，不用 explicit id。

**5 端注册**：desktop / android / cli / server / ios 各加 1 import + 1 register，紧贴 `DescribeSessionTool` 注册位置（session-read 家族）。

**亚军 bullet 跳过理由 (再次记录)**: `project-query-sort-by-updatedAt` 继续被 §3a 自查 skip（见上轮 cycle 的 `session-query-include-compaction-summary` decision 里的跳过说明：要求 14+ mutation tool 每个都 stamp 一次 `updatedAtEpochMs`，测试面 explode，`Project.updatedAt` 反推方案 sortBy=recent 对项目内 entity 为 no-op）。等具体 UI driver 或更小 scope 实现出现再动。

**Alternatives considered.**

1. **Option A (chosen)**: 独立 tool + SessionEnvelope @Serializable 类型，不 IO。优点：和 `export_project` / `export_source_node` pattern 一致；`envelope: String` 让 agent 自行 `write_file` 决定路径（LLM 可传 tmp / per-user dir，不暴露 Core 对 fs 的默认假设）；`SessionEnvelope` 独立 @Serializable 类让未来 `import_session` 直接复用解码。缺点：+1 tool，`session_query` 家族仍保持只读 projection —— 但 bullet 已预授权。
2. **Option B**: 扩 `session_query` 加 `select=envelope` 返回 envelope string。拒绝：① 把"写出可持久化 format" 的职责塞进纯 query primitive，破坏既有 "one select = one 聚合视图" 契约；② select 输出形状是 `rows: JsonArray`，envelope 是单 String —— 要么硬塞进 rows 一元素数组（丑），要么给 select=envelope 特别 fork output shape（更丑）。
3. **Option C**: 加 `fs.write_envelope(kind, id, path)` 混合 tool 统一 export + write。拒绝：权限边界混乱（session.read + fs.write 重组）；把 session 领域知识塞进 fs 层是分层反模式；agent 已经能 `export_session` → `write_file` 组合。
4. **Option D**: 把 envelope 压成 gzipped base64 blob 减 wire size。拒绝：① bullet 没要求；② `export_project` / `export_source_node` 都是明文 JSON，一致性优先于传输效率；③ 想压缩的用户可以链外部工具。
5. **Option E**: envelope 含 project 字段（同时 export session 和它绑定的 project）。拒绝：bullet 明确 "打包 `Session` + `Message[]` + `Part[]`"，不含 project blob；session 引用 project 应该通过 target instance 已有 project 补齐（或先 `export_project`）；合二为一让"只想备份对话"的 common case 被迫扛上 project bytes。
6. **Option F**: 把 format 选成非-JSON（protobuf / msgpack）。拒绝：`JsonConfig.default` 已经是 kotlinx-serialization idiom；`Session` / `Message` / `Part` 都是 `@Serializable` with JSON discriminator；二进制 format 要新 codec + 配套 infra，越界严重。

**Coverage.**

- **新建 `ExportSessionToolTest.kt` 5 tests**:
  - `envelopeIncludesSessionMessagesAndParts` — happy path：2 messages + 1 text part，round-trip decode 后 content 完整。
  - `emptySessionRoundTripsCleanly` — 0 messages / 0 parts 也合法 envelope。
  - `missingSessionFailsLoudly` — 不存在 sessionId → IllegalStateException 含 "ghost"。
  - `sessionIdDefaultsFromContext` — omit input.sessionId → falls back to ctx.sessionId (cycle 18 pattern).
  - `prettyPrintProducesLargerEnvelope` — prettyPrint=true 比 compact 大，两者都正确 round-trip。
- `./gradlew :core:jvmTest` + `:apps:server:test` + `:core:ktlintCheck` 全绿。
- 4 端构建：iOS sim / Android debug APK / Desktop / Server 全绿。

**Registration.** 5 端 AppContainer（desktop / android / cli / server / ios）都 +1 import + 1 register line，放在 `DescribeSessionTool` 之后（session-read 家族邻接）。

**§3a 自查.**

1. 工具数量: **+1**。bullet 预授权 + 本 decision 显式辩护（family 一致性 + query 不能替代）。PASS（acknowledged）。
2. Define/Update: N/A（export 不涉及 source concept define/update pair）。
3. Project blob: 不动。PASS。
4. 状态字段: 无新 flag。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: sessionId 走 `ctx.resolveSessionId`（cycle 18 模式）。PASS。
7. 序列化向前兼容: FORMAT_VERSION 版本化；`SessionEnvelope` 的所有字段都是 domain 既有 @Serializable 类型（`Session` / `Message` / `Part`），随 core 已有的 forward-compat 保证同步演进。PASS。
8. 五端装配: desktop / android / cli / server / ios 全部 register。PASS。
9. 测试语义覆盖: 5 tests 覆盖 happy / empty / missing / default-from-ctx / prettyPrint round-trip。PASS。
10. LLM context 成本: +1 tool spec ~310 tokens（helpText 较长以解释 session 不含 project 的对称决策 + 提示 write_file 链式用法）。单次调用成本永久加在每 turn。PASS（+1 tool 已在 bullet 授权 + decision 辩护）。

**Non-goals / 后续切片.**

- **Follow-up: `import_session` tool** —— bullet 原文 "留给后续 cycle"。validates envelope.formatVersion、恢复 Session row + appends Message[]/Part[] 到目标 store；需要处理 sessionId 冲突（rename / fail / merge）。独立 decision。
- **不扩 envelope 加 project snapshot** —— 保持 session-scoped 对称。用户要完整快照可以 `export_project` + `export_session` 分别跑。
- **不加 filesystem IO 到 ExportSessionTool** —— write_file 已在 fs tool 领域；chain `export_session` → `write_file` 路径成熟。
- **跳过 `project-query-sort-by-updatedAt`**（P2 top）连续第二轮—— 保留 backlog 待具体 UI driver。
