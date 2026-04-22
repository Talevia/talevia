## 2026-04-22 — tool-spec-token-budget-metric：agent 可见每轮注册工具的 token 预算（VISION §5.4 + §3a-10）

Commit: `938c90d`

**Context.** §3a-10 "LLM context 成本可见" 的硬性监管项：每轮对话里 LLM 先要为所有注册工具的 spec（id + helpText + JSON Schema）付一份 token，这份开销**完全看不见**。仓库里的 tool count 已经从 repopulate 当时的 104 爬到 105，`ListToolsTool` / `AigcPricing.priceBasisFor` 解决的是调用侧的成本可见性，但"spec 自己的 token 重量"没人报。agent 无法判断 "我是不是该建议 consolidate 两个近似工具"。

OpenCode 没有对应原语——那边每轮在 `session/prompt.ts` 里直接构造 system prompt + tool spec，没有自省。这是 Talevia 为抵御 tool-count 膨胀自己引入的护栏。

**Decision.** 给 `session_query` 加 `select=tool_spec_budget`：registry-wide 单行快照，report `(toolCount, estimatedTokens, specBytes, registryResolved, topByTokens[≤5])`。估算式：`(id + helpText + JsonConfig.default.encodeToString(inputSchema)).length / 4`（向上取整 half-up，避免非零但小的 spec 被压到 0 token）。Top-N 按 token 降序 take 5——让 LLM reason "哪些最重"，但不让 response 随 registry 大小线性膨胀。

- 新文件：`core/src/commonMain/kotlin/io/talevia/core/tool/builtin/session/query/ToolSpecBudgetQuery.kt`（～80 行）。
- `SessionQueryTool` 加可选构造参数 `toolRegistry: ToolRegistry? = null`、新增 `SELECT_TOOL_SPEC_BUDGET` / `ToolSpecBudgetRow` / `ToolSpecBudgetEntry` / dispatch 分支 / sessionId 拒绝分支（registry-wide snapshot，`sessionId` 填了就是 typo）、helpText 加一段。
- 5 个装配点全部传 `toolRegistry = this` / `toolRegistry: registry`：`apps/cli/CliContainer.kt`、`apps/desktop/AppContainer.kt`、`apps/server/ServerContainer.kt`、`apps/android/AndroidAppContainer.kt`、`apps/ios/Talevia/Platform/AppContainer.swift`。ToolRegistry.register 本身不关心插入顺序，`execute()` 在 dispatch 时才读 `registry.all()`——composition root 已经完成装配，没有先-有-鸡-还-是-先-有-蛋问题。

Zero 新 tool id（bullet 要求的是 select，不是新 Tool）。helpText 增量 ≈ 60 词 ≈ 80 token / turn——接受：`tool_spec_budget` 本身就是为了让 agent 判断是否要削减其它 spec 开销，80 token 投入换全 registry 可见性。

**Alternatives considered.**

- *Option A (chosen): `session_query` 新增 `select=tool_spec_budget`*。复用已建立的 query-primitive 形态（`select=status` / `select=cache_stats` / `select=context_pressure` 都是 single-row snapshot）；不加新 Tool，不增加 5-端装配面；和现有 session 自省面融合。缺点：名字里带 "session" 但数据是 registry-wide（等价于 `select=sessions`——那个也是全局）；通过 "sessionId 拒绝 + helpText 明确 Session-independent" 缓解。
- *Option B: 扩 `list_tools` Input 加 `includeSpecBudget: Boolean`*。拒绝：`ListToolsTool` 的 Summary 是 per-tool 行，加 budget 字段后 LLM 每次 list 都要分页；`session_query` 的 single-row 形式更匹配"我想要一个总数"的查询意图。如果未来有人要 "每工具 token / cost 联动分析"，再在 ListToolsTool 加一个 sort 维度即可。
- *Option C: 新建独立 `tool_budget` tool*。拒绝：净增 1 个 Tool id + 5 端装配面 + ≥ 200 LLM token spec（§3a-1）。当 `session_query` 已经是 query primitive 的时候再加 sibling 几乎总是错的。
- *Option D: 用一个 provider-specific tokenizer（tiktoken / anthropic claude-tokenizer）精确估算*。拒绝：tiktoken 是 JVM native lib（违反 `core/commonMain` 零平台依赖红线）；2026-04 的 "1 token ≈ 4 bytes" 已经和 OpenAI / Anthropic tokenizer 的 English + JSON 文本平均偏差 < 15%，对"要不要 consolidate"这种决策的阈值信号足够。Decision 显式标注 "order-of-magnitude, not exact"。

**Coverage.**

`core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/session/SessionQueryToolSpecBudgetTest.kt`，五个 case：

1. `reportsCountTokensAndTopHeavyTools` — 注册 EchoTool + HeavyTool（helpText 1200 字），断言 toolCount=2、estimatedTokens>0、specBytes≥3×estimatedTokens、topByTokens 里 HeavyTool 排在 echo 之前。
2. `topByTokensIsCappedAtFive` — 注册 12 个 NumberedTool，断言 topByTokens.size == 5（防响应膨胀）。
3. `zeroToolsReportsZeroTotals` — 空 ToolRegistry，断言所有计数 0、`registryResolved=true`（空 registry 是 resolved，不是 unresolved）。
4. `rigWithoutRegistryReportsUnresolved` — 构造 SessionQueryTool 时不传 registry，断言 `registryResolved=false`。
5. `sessionIdIsRejectedAsRegistryWideSnapshot` — 传 `sessionId="..."` 同时 select 本 budget，断言 `assertFailsWith<IllegalStateException>`。

§3a-9 "测试覆盖语义而非 happy path" —— 反直觉 case 覆盖：top-N 上界、unresolved-registry、sessionId 拒绝。

`:core:jvmTest`、`:core:ktlintCheck`、`:core:compileKotlinIosSimulatorArm64`、`:apps:cli:compileKotlin`、`:apps:desktop:compileKotlin`、`:apps:server:compileKotlin`、`:apps:android:compileDebugKotlin` 全绿。

**Registration.** 五端 AppContainer 全部注入 `toolRegistry = this` / `toolRegistry: registry`：CLI、Desktop、Server、Android、iOS(Swift)。测试 rig 保持无 registry 版本以确保 opt-in 语义真实（`rigWithoutRegistryReportsUnresolved` 就是这条路径）。
