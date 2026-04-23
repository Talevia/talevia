## 2026-04-22 — Extract Chat Completions request-body builder out of OpenAiProvider (VISION §3a-3)

**Context.** Backlog bullet `debt-trim-openai-provider` called out
`OpenAiProvider.kt` 424 行 as a churn hotspot — `buildRequestBody` /
`buildMessages` absorbed several recent incidents (seed removal from image /
Sora bodies, `max_tokens` → `max_completion_tokens` rename, tool_calls anchor
requirement, `""` content for aborted assistant turns). The bullet also
mentions image-gen and Sora bodies — those are already in
`OpenAiImageGenEngine.kt` / `OpenAiSoraVideoGenEngine.kt` siblings (since
those were carved out during earlier cycles), so only the Chat Completions
pair remained inline.

Rubric delta：§3a-3 "长文件" `OpenAiProvider.kt` 424 → 303 行 (−121)；新增
`OpenAiRequestBody.kt` 163 行。主文件现在只做 SSE streaming + event mapping
+ 错误路径；请求体成型是独立单元。

**Decision.** 把 `buildRequestBody` / `buildMessages` 两个 private method
抽到新建的 sibling file `OpenAiRequestBody.kt`，`internal` top-level 函数：

- `buildChatCompletionsBody(request: LlmRequest, json: Json): JsonObject` —
  对应原 `buildRequestBody`。
- private `buildMessages(request: LlmRequest, json: Json): JsonArray` —
  `buildChatCompletionsBody` 内部调用的 message renderer；`private` 在 file
  作用域，只能被本文件的 `buildChatCompletionsBody` 调用。

`OpenAiProvider.stream` 的唯一改动是 `buildRequestBody(request)` → `buildChatCompletionsBody(request, json)`。
两个 private method 删除，unused imports（`JsonArray`, `put`, `putJsonArray`, `putJsonObject`,
`addJsonObject`, `Message`, `Part`, `ToolState`, `buildJsonArray`, `buildJsonObject`,
`ReplayFormatting`）自动被 ktlintFormat 清掉。

关键行为保留点（在新文件里都带 comment 说明）：
1. Assistant message 无 `tool_calls` 时 `content` 必须是 string（aborted 前轮
   emit `""`）。
2. 每条 `role: tool` 必须有前置 `tool_calls` entry anchor（Running /
   Completed / Failed 三态都要）。
3. Failed tool 的 `s.input` 可能为 null（schema-parse 失败），replay 时 {}
   兜底。
4. `Duration` 序列化 / 特殊 kotlin.time 处理 → 本文件不涉及。

**Alternatives considered.**

1. **整体不动** —— 424 行 < 500 阈值，不是强制 split。但 backlog 本轮排第三
   的 P1 bullet 就是这个；churn hotspot 是真实信号（`git log` 显示 OpenAI
   body 相关变更最近一个月有 4 次），侧重于"降低每次改动的 diff 大小 / 让
   review 更聚焦"而非"行数阈值"。否决不动方案。
2. **拆成三个文件**（OpenAiChatBody.kt / OpenAiChatMessages.kt /
   OpenAiResponses.kt 若未来新增 Responses API）—— 过度拆分。Responses API
   不在短期路径（当前只用 `/v1/chat/completions`，用户选择此 endpoint 的理由
   是 broad compat：本 decision 不引入假想未来）。两个 function 放一起语义
   连贯（top-level + 它调用的私有 helper），否决三文件方案。
3. **改成 class 形式**（`OpenAiChatRequestBuilder`）—— 无状态的纯函数没必要
   包成 class，那是 Effect.js-style 过度抽象（反需求红线）。否决。

业界共识对照：
- Kotlin file-level private `fun` 是 top-level helper 的惯用写法（`buildMessages`
  只被本文件的 `buildChatCompletionsBody` 调用）。
- OpenCode 的 provider plugin 类似模块化：`packages/opencode/src/provider/`
  下每种 body builder 多为 top-level 函数，不包 class。本 decision 与其一致。

**Coverage.**
- `:core:jvmTest` 全绿（no behavior change — 请求体逐字节等价）。
- `:apps:cli:test`、`:apps:desktop:assemble`、`:apps:android:assembleDebug`、
  `:core:compileKotlinIosSimulatorArm64` 全绿。
- `ktlintCheck` 绿（ktlintFormat 清理了 OpenAiProvider.kt 的 unused imports
  和 OpenAiRequestBody.kt 的 import 排序）。
- 行为等价性来自静态等价 —— 函数体 byte-level 一致（除 `json` 参数显式传递 +
  `request` 参数显式传递，原来从 class 闭包拿）。

**Registration.** 无 —— file 级拆分，没有新 tool / 新 API，不影响 5 个
`AppContainer` 的任何装配点。

**顺手记 debt — 跳过两条资本意义不足的 bullet。**
- `debt-resplit-project-query-tool`：`ProjectQueryTool.kt` 547 行但里面 300+
  行是 row data class，移出后 `ProjectQueryTool.XxxRow.serializer()` 嵌套引用
  会破；77+ 测试 + desktop SnapshotPanel 都要改。commit `6e7bd8f` 已显式
  文档化"rows stay nested because they're public decoding API"。540 → 547
  的 7 行增长是 `baad43f` + `8e00978` 两次 feat 的增量，不是 split 问题。
- `debt-resplit-session-query-tool`：`SessionQueryTool.kt` 586 行，`672b56c`
  后有 4 次 `feat(core): session_query(select=...)` 加 select 带来的新 row，
  同样的 public-API constraint。跳过理由一致。
两条 bullet 保留在 backlog，由用户 / 下一轮 rubric 裁决（rule 7 允许跳过不删）。
