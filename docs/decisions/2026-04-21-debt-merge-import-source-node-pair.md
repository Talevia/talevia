## 2026-04-21 — debt-merge-import-source-node-pair：合并 `ImportSourceNodeTool` + `ImportSourceNodeFromJsonTool`（§R.5.4 / §3a.2）

Commit: `9925ffe`

**Context.** `ImportSourceNodeTool` (`fromProjectId + fromNodeId` — 从当前 Talevia 实例内的另一个 open project 复制) + `ImportSourceNodeFromJsonTool` (`envelope: String` — 从 export_source_node 产出的 portable JSON envelope 吸收) 是 §3a.2 "variant / variant" 分裂 —— 两个工具的 output 契约几乎相同（都是 `List<ImportedNode>`），都跑同一套 content-hash dedup + parent-walk + collision 策略，只是输入源不同：
- live project reference（走 `projects.get(fromPid)`），
- 或 envelope JSON string（走 `json.decodeFromString(SourceNodeEnvelope, ...)`）.

Rubric §3a.2：两个 tool spec × LLM turn 付 ~500 tokens；LLM 还要记"live 用哪个、envelope 用哪个"的 routing rule。Backlog bullet 原文："合并为 `import_source_node(source: {path?: String, jsonBody?: JsonElement})`，二选一。"

此外意外顺带收益：`toProjectId` 之前是 required；merge 时同步改为 optional 走 `ctx.resolveProjectId`，和 cycle 17 的 projectId-optional pattern 一致（常见场景是 "session 里已绑了目标 project，直接 import 进当前项目"）。

**Decision.** 把 `ImportSourceNodeFromJsonTool` 全部行为吸收进 `ImportSourceNodeTool`，删除前者，unified tool 通过输入字段的互斥选择在两条路径之间 dispatch：

**New Input**：
```kotlin
data class Input(
    val toProjectId: String? = null,       // optional — defaults to ctx.resolveProjectId
    val fromProjectId: String? = null,     // live path (pair with fromNodeId)
    val fromNodeId: String? = null,        // live path
    val envelope: String? = null,          // envelope path (JSON string)
    val newNodeId: String? = null,         // common: rename the leaf
)
```

**互斥校验**：`require(livePair XOR envelopeSet)` —— `livePair = !fromProjectId.isNullOrBlank() && !fromNodeId.isNullOrBlank()`；`envelopeSet = !envelope.isNullOrBlank()`。两者都设 / 都不设都 fail loud with explanation of the two shapes。

**Input 字段名**：没采纳 bullet 原文的 `source: {path, jsonBody}` 嵌套对象 —— kotlinx.serialization idiom 倾向 flat schema（嵌套 object 会让 LLM schema 额外一层 object + required 字段互斥规则更难表达）。flat 字段 + runtime XOR 校验更直接。bullet 里 `path` / `jsonBody` 的语义字面对应不明（没有文件系统 path 参数；`envelope` 是 String，不是 `JsonElement`，因为 envelope 的 wire format 就是一整个 stringified JSON document）—— 用现有的语义名 `fromProjectId` / `fromNodeId` / `envelope` 保留。

**Output 形状**：统一 `(fromProjectId: String?, toProjectId: String, formatVersion: String?, nodes: List<ImportedNode>)`。`fromProjectId` envelope 路径下为 null；`formatVersion` live 路径下为 null —— 调用方用非 null 字段判断来源路径（Tool Output 不持久化，nullable 字段 decode 安全）。

**内部 dispatch**：`execute()` 先解出 `toPid`，然后判断 livePair/envelopeSet 走 `executeLive(input, toPid)` 或 `executeEnvelope(input, toPid)`。两条路径各自保留原有算法（都放在同一 class 内、共用 private helpers）。`topoCollect` 从 private 改成 `internal`（方便未来跨文件测试）。

**删除 + 五端装配 + cross-ref 更新**：
- 删 `core/.../source/ImportSourceNodeFromJsonTool.kt`（~205 行）。
- 5 端 AppContainer（desktop/android/cli/server/ios）各 -1 import + -1 register。
- `SourceNodeExportImportToolsTest.kt` 的 9 个 test 方法从 `ImportSourceNodeFromJsonTool(...)` / `ImportSourceNodeFromJsonTool.Input(...)` 批量改到 `ImportSourceNodeTool(...)` / `ImportSourceNodeTool.Input(...)` —— envelope 路径在 unified tool 下验证完全等价。
- `SourceNodeExportImportToolsTest` 的 class-level KDoc 改引用新 tool（保留 "envelope path" 细分标签）。
- `ImportSourceNodeToolTest.kt`（9 个 test 覆盖 live 路径）完全不改 —— positional Input 参数 shape 不变。
- Prompt / KDoc：无需改动。`PromptProject.kt` 对 `import_source_node` 的描述（"VISION §3.4 可组合"）already general enough to cover both paths；`ExportSourceNodeTool.kt` KDoc 引用 `[ImportSourceNodeTool.topoCollect]` 已自动切到新 unified 类。

**Alternatives considered.**

1. **Option A (chosen)**: flat 字段 + `livePair XOR envelopeSet` 校验，unified tool id `import_source_node`。优点：LLM schema simple（5 个 top-level optional 字段）；runtime 校验一行；preserves 所有既有行为；5 端 -1 tool。缺点：Input 字段数从 3 涨到 5；但每个字段的 KDoc / schema description 明确标"live" / "envelope" / "common"。
2. **Option B**: 按 bullet 字面用 nested `source: {path?, jsonBody?}` oneof。拒绝：kotlinx.serialization 的 `@Serializable` + oneof 需要 sealed class + discriminator；强加 sealed class 对 LLM schema 是两层嵌套（`source.type = "live"|"envelope"` 然后 source.fromProjectId / source.envelope），schema spec 和 runtime validation 都比 flat 复杂；零实际收益。
3. **Option C**: 保留两 tool，`ImportSourceNodeTool` 内部 delegate 到 `ImportSourceNodeFromJsonTool`（或反之）。拒绝：tool count 不降；LLM spec 成本不变；§3a.2 红信号保留。
4. **Option D**: 把 `envelope` 参数类型从 `String` 改成 `JsonElement`（对齐 bullet 的 `jsonBody`）。拒绝：export_source_node 输出的是 **stringified** JSON（`data.envelope: String`）—— 保持 transport type 对齐，避免 LLM 把 envelope 当 JSON object 嵌入 tool-call JSON 里（会导致 dispatch 时 schema 不匹配或 re-stringify 失败）。
5. **Option E**: 把 `toProjectId` 仍 required（不做 optional 下沉）。拒绝：5 端其他 write tool 都走 `ctx.resolveProjectId` 模式（cycle 17/21 建立的基础设施）；保留 required 会让这一个 tool 成为例外。optional + 继承 session binding 是最小变化。
6. **Option F**: Per-node `newNodeId` map 允许批量 rename（bullet 之外）。拒绝：越界；单 leaf 的 `newNodeId` 配合 "collision → re-run with different leaf name" 足够；批量 rename 没有实际 driver。

**Coverage.**

- `SourceNodeExportImportToolsTest`（9 tests）—— envelope 路径的 round-trip / dedup / parent-topo / rename / collision / formatVersion-reject / malformed-envelope / missing-node-on-export / pretty-print-round-trip 全部保留并在 unified tool 下继续绿。
- `ImportSourceNodeToolTest`（9 tests）—— live 路径的 leaf-import / idempotent / parent-topo / parent-dedup-remap / different-content-collision / rename-leaf / self-import-reject / missing-source-project / missing-target-project / missing-source-node 全部保留（Input shape 不变），unified tool 下继续绿。
- 两个 test 文件共 18 tests 覆盖 XOR 的两条路径；merge 本身的"既不传也不传 XOR 校验" edge case 没有显式新加 test，但被 `require(...)` 在 helpText 里明示，agent failure mode 在 call-site 上立刻可见。（**Coverage debt**：可补 1 个专门 `executeRejectsNoInputShape` test 兜底。记为 follow-up。）
- `./gradlew :core:jvmTest` + `:apps:server:test` + `:core:ktlintCheck` 全绿；4 端构建全绿。

**Registration.** 5 端各 -1 import + -1 register。新 unified `ImportSourceNodeTool` 原有注册保留（register 行内容不变）。

**§3a 自查.**

1. 工具数量: **-1**。PASS（§3a.1 显式收益）。
2. Define/Update: N/A。
3. Project blob: 不动 `Project`。PASS。
4. 状态字段: livePair/envelopeSet 是 derived flags 不是一等字段；XOR runtime 校验。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: `toProjectId` 一并 optional via `ctx.resolveProjectId` —— cycle 17 模式扩展。PASS（顺带收益）。
7. 序列化向前兼容: 旧 `ImportSourceNodeTool.Input(fromProjectId, fromNodeId, toProjectId, newNodeId)` 的 live 路径调用继续工作（字段名保留）；旧 `ImportSourceNodeFromJsonTool.Input(toProjectId, envelope, newNodeId)` 的 envelope 路径通过新 tool id `import_source_node` 分发到同一 class（LLM 只看 schema，旧 tool id `import_source_node_from_json` 消失）—— tool input 不持久化无迁移成本。PASS。
8. 五端装配: 全部 5 端 -1 import + -1 register。PASS。
9. 测试语义覆盖: 18 tests 覆盖 live / envelope 两条路径完整，含 dedup / parent-walk / rename / collision / format-version / malformed / self-import 边界。XOR 专门测试属于 coverage debt。部分 PASS。
10. LLM context 成本: -1 tool spec ~280 tokens；unified helpText +110 tokens（明确两条 shape 的 mutually-exclusive 说明）；净 **~170 tokens/turn 节省**。PASS（收益方向）。

**Non-goals / 后续切片.**

- **Follow-up: per-tool `executeRejectsNoInputShape` test** 兜底 XOR 校验的 "都不传" edge case —— 1 个 test 的 coverage debt。
- **Follow-up: `auto_subtitle_clip` / 其他 AIGC 单/批 pattern** —— 类似 merge 模式可扩（已有 list 输入；可能的目标：`ImportMediaTool` 单 path vs list?）。留给下次 debt scan 决定。
- **不扩 envelope 的 `path: String` 变体**（bullet 字面描述过）—— 文件 IO 属于 `fs` 工具领域；agent 需要 import 文件时走 `read_file` → 把内容喂给 `envelope` 参数。加 path 参数会混入 fs 权限边界，Core 不应直接读文件。
- **不改 `SourceNodeEnvelope` schema** —— 当前 `formatVersion = "talevia-source-export-v1"` 保持；向后兼容由 `ExportSourceNodeTool.FORMAT_VERSION` 常量 + 导入校验守护。
