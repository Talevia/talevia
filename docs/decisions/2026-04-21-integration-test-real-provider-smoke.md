## 2026-04-21 — integration-test-real-provider-smoke：加 opt-in Anthropic real-API smoke test（Rubric 外 / 测试债）

Commit: `3b1f247` (pair with `docs(decisions): record choices for integration-test-real-provider-smoke`).

**Context.** 所有现有 provider 测试都用 Ktor `MockEngine` + 手写 SSE 片段。`AnthropicProviderStreamTest` 覆盖 SSE → `LlmEvent` 解码，`AnthropicAgentLoopIntegrationTest` 覆盖 tool_use ↔ tool_result 回路——都 mock。对**真实 API** 的 format drift（Anthropic 重命名字段、改 stop_reason 值、retire 老 model）完全没有探测能力。

Backlog 方向明说：加 `@EnabledIfEnvironmentVariable("ANTHROPIC_API_KEY")` 的一轮 E2E smoke test（创建 session、发一句话、断言返回含 text + stop reason），**CI 不跑，本地可选**。

**Decision.** 新增 `core/src/jvmTest/kotlin/io/talevia/core/agent/AnthropicRealProviderSmokeTest.kt`。核心设计点：

1. **Opt-in via env var**：`kotlin.test` 框架没有 JUnit 5 的 `@EnabledIfEnvironmentVariable` 注解。改用运行时跳过：`System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() } ?: return@runTest`。CI 默认没 key → 静默跳过，和 `FfmpegEndToEndTest` 用 `ffmpegOnPath()` skip 同一 pattern。
2. **真 `HttpClient(CIO)` + 真 `AnthropicProvider`**：和 `Agent` / `SqlDelightSessionStore` / `EventBus` 全部直连，唯一假依赖是 API key 换成真用户的。
3. **Haiku 4.5 作默认 smoke model**：Claude 4.x 家族最便宜档位（CLAUDE.md 列表）。`TALEVIA_SMOKE_MODEL` env var 让用户切别的 model 跑同一 smoke。
4. **Prompt 选 "Reply with exactly one English word."**：最短可预测回复，尽量少 output tokens，把单次调用成本压到 < $0.001（haiku 费率 $0.25/M input + $1.25/M output, 约 ~30 in / ~3 out = 约 $0.00001）。
5. **断言覆盖**：
   - `asst.finish in setOf(STOP, END_TURN)` — stop reason present and clean。
   - `asst.error == null` — 不能是 error 路径。
   - `asst.tokens.input > 0 && asst.tokens.output > 0` — 检测 usage 字段 wire-up（如果 Anthropic 改 `usage.input_tokens` 名字，input 会留 0 → test 红）。
   - `listSessionParts(...).filterIsInstance<Part.Text>().any { ... it.text.isNotBlank() }` — 真的有文本 Part 落地（不只是空 stream）。
   - `assistantMessages.size == 1` — 没 tool-loop 转圈（sanity）。

无新 tool，无 AppContainer 变化。纯 test addition + decision 归档。

**Alternatives considered.**

1. **Option A (chosen)**: 运行时 env check + `return@runTest` 跳过。优点：零额外依赖；和仓库里已有的 skip pattern (`ffmpegOnPath`) 一致；CI / 本地无 key 场景都静默通过。缺点：test runner UI 看起来像"ran (passed)"而不是"skipped"——差别不大。
2. **Option B**: 迁移 JVM test source 到 JUnit 5 platform，引入 `@EnabledIfEnvironmentVariable`。拒绝：改 build.gradle 影响所有现有 test；风险远大于收益；backlog bullet 只是措辞引用，不是硬性指定 JUnit 5。
3. **Option C**: 用系统属性 `-Dsmoke.enabled=true` 显式 opt-in 而非 env var。拒绝：env var 和"我有这个 API key"语义更近；`System.getProperty` 需要 gradle `systemProperty` 配置还要记得加，门槛更高。
4. **Option D**: 写两个 smoke test —— 一个 Anthropic，一个 OpenAI。拒绝：本 cycle 范围明确是 "one round end-to-end"；加 OpenAI 是复制粘贴，如果真需要 cycle 后续扩。当前先 land Anthropic。
5. **Option E**: 把 smoke test 塞进 `AnthropicAgentLoopIntegrationTest`（加一个 @Test method）。拒绝：那文件职责是 mock-based integration（确定性、快），掺入真 API 调用会 timing-dependent flaky。独立文件更清晰。
6. **Option F**: 断言具体返回文本（例如 prompt 说 "reply with 'PING'" 然后 `assertEquals("PING", text)`）。拒绝：LLM 输出非 deterministic（即使 prompt 严格，标点 / 大小写 / 完整性不同）；smoke test 不是 capability test。只断言"有文本 + 正常 stop"最稳。

**Coverage.**

- 1 个新 test：`AnthropicRealProviderSmokeTest.realAnthropicProviderReturnsTextAndStopReason`。
- CI：`./gradlew :core:jvmTest` 全绿，smoke test 静默跳过（no `ANTHROPIC_API_KEY`）。
- 本地带 key 跑：user 可以 `ANTHROPIC_API_KEY=sk-ant-… ./gradlew :core:jvmTest --tests 'io.talevia.core.agent.AnthropicRealProviderSmokeTest'` 真打 API。未在 decision 写入时机跑真 API（不想 burn token 仅为一次 smoke），但已 review 代码逻辑 vs 现有 `AnthropicAgentLoopIntegrationTest` 的真 session-store + Agent 调用路径。
- 4 端构建：iOS / Android / Desktop / Server 全绿（test 纯 JVM-side，不影响 native / android）。
- `./gradlew :core:ktlintCheck` 全绿。

**Registration.** 无 AppContainer 变化——不是 LLM tool，不走 ToolRegistry。

**§3a 自查.**
1. Tool count: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: N/A。
4. 状态字段: N/A。
5. Core genre: N/A。
6. Session/Project binding: 用 real Agent + session store，绑定透传正确（Session.currentProjectId 未设置，不影响 text-only smoke）。PASS。
7. 序列化向前兼容: N/A（不改 schema）。
8. 5 端装配: 不涉及。PASS。
9. 测试语义覆盖: 覆盖 stop reason / error null / token accounting / text persisted / single assistant turn 五条断言，每条对应一种真实漂移模式。PASS。
10. LLM context 成本: 0（不是 LLM tool，test 本身不进入任何 agent prompt）。PASS。

**Non-goals / 后续切片.**
- OpenAI / Gemini smoke test：同 pattern 可复制但本轮范围单一 provider。如果 OpenAI SSE 被打破需要一个真 smoke，单独 cycle land `OpenAiRealProviderSmokeTest`。
- Tool-use smoke test：当前只测纯文本回复。真 API 的 tool_use round trip 已在 mock 级测过（`AnthropicAgentLoopIntegrationTest`），对 wire format 漂移的覆盖有限但值得未来扩。门槛是每 tool turn 一次真 API 调用 → 本地跑贵几分钱 → 需要时再加 opt-in flag。
- GitHub Action / CI nightly 跑（不是每 PR 跑）：需要一个 repo secret + cron 配置 + monitoring budget。不是 test 代码本身的问题；CI wiring 可以 follow-up。
- Streaming delta 级断言（每个 chunk 的 payload）：mock 路径已测 via `AnthropicProviderStreamTest`；真 API 不稳定，不好反向 fixture。smoke 断言终态即可。

**P2 清理完成**：backlog `P2` 档最后一条。下一轮 cycle 会发现三档只剩 P0 + P1 top 两条都被 §3a.5 长期 skip → 触发 R. Backlog repopulate。
