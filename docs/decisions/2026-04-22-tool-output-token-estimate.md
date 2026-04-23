## 2026-04-22 — ToolResult.estimatedTokens + largest-first compaction drop (VISION §5.4 / §5.7)

**Context.** Backlog bullet `tool-output-token-estimate`：`Compactor` 用 token
估算决定要不要 compact，但当需要 compact 时，drop 策略是"按出现顺序遍历、
命中超预算就扔"—— 等于"最老先扔"。这个策略在预算很紧 + 小 + 大 tool 输出
混合的场景下会连续扔掉多条旧的小结果、保不住重要的 reasoning/text history；
更合理的是"最大先扔"，一条 6000-token 的 `project_query` 列表就够把预算
拉回来。同时 `TokenEstimator.forPart(Part.Tool.Completed)` 用 byte-length
启发式（`len/4`）有两个盲点：(a) 无法区分"40 行 JSON 表但 provider 计为
500 token" vs "40 行 natural text 计为 10 token"；(b) 没有让工具作者自己
提供更准确的估算。

Rubric delta：§5.4 Agent-loop 上下文成本"部分 → 有"（compaction 按重要性
而非时间选择牺牲对象）；§5.7 运行时预算"部分 → 部分+"（工具作者可声明自
己的估算）。

**Decision.** 三处最小改动：

1. **`ToolResult<O>` 加 optional `estimatedTokens: Int? = null`**。Tool
   作者可在有便宜更准估算时填（例："`project_query(select=rows)` 返回
   N 行 → N × 50"）；null 走原 byte 启发式。默认值保证 back-compat，所有
   现存 ~100 个 `ToolResult(...)` 调用点不用改。
2. **`ToolState.Completed` 镜像 `estimatedTokens: Int? = null`**。
   `@Serializable` 默认值 = 旧 JSON blob 依然 decode（§3a-7 forward compat）。
   `AgentTurnExecutor` 建 `ToolState.Completed` 时把 `result.estimatedTokens`
   原样塞进去。
3. **`TokenEstimator.forPart` 对 `ToolState.Completed` 优先用 `s.estimatedTokens`**，
   null 再走原 `24 + forJson(input) + forText(outputForLlm) + forJson(data)`
   启发式。
4. **`Compactor.prune` 重写为"largest-first drop"**：
   - 把 protect-window 内的 token 算进 `fixedTokens`（恒留）；
   - 遍历 pre-window parts，completed-tool outputs 进 `candidates` 列表，
     其它 parts 进 `fixedTokens`（同样恒留 —— 和旧算法一样只 drop tool 输出）；
   - 候选按 `cost` 降序排，从大到小扔，直到 `fixedTokens + keptCandidates
     ≤ pruneProtectTokens`。旧算法的"遇到就扔"被替换成 global optimum-ish
     贪心。

没有改 `Compactor.process` 的整体契约：`prune` 返回的 `Set<PartId>` 传给
`store.markPartCompacted`，再做 summarise pass。输入输出 shape 不变。

**Alternatives considered.**

1. **保留旧算法** — 最老先扔。优点：零改动。缺点：多小 tool 输出 + 一大 tool
   输出的场景下会 unnecessarily 扔掉多条小结果。backlog bullet 明确指出这个
   缺口，否决。
2. **每个 Completed Part 落盘时同时写 `tokenize()` 真实 token 计数**（调 provider
   的 tokenizer API）—— 过度工程。真 tokenizer 在 JVM 需要 sentencepiece/
   tiktoken native lib，在 iOS 需要移植，commonMain 根本没 budget 做这个。
   `TokenEstimator` 本身的注释也明确说 "real tokenisation is provider-specific
   and not worth shipping in commonMain"。否决。
3. **完全不让 Tool 作者填 `estimatedTokens`，只让 Compactor 自己估**（只做 largest-first
   + byte heuristic）。优点：tool 作者不用想估算。缺点：`project_query` 这类"返回 list
   JsonArray"的工具，其 `data` 是 `JsonArray`，`forJson(data).toString()` 走一遍
   字符串化成本是 O(size)，每次 compaction pass 要跑一遍（Compactor 在 context 压力
   下每个 turn 都可能调一次）；而 tool 自己在 execute 里已经有 `rows.size` 这个数字，
   estimatedTokens=rows.size * 50 几乎零成本。保留 `estimatedTokens` override 为优化
   预留位（null 默认仍走 byte-heuristic，所以不增加当前每个 tool 的工作量）。

业界共识对照：
- OpenCode `compaction.ts` 的 compact 算法同样走"drop tool outputs"策略，但
  它按**消息时间**倒序，不按大小 —— Talevia 这一点更进一步，是本项目自己的
  改进。Token 估算走 byte-ish heuristic 在 OpenCode 也是同样做法（真 tokenizer
  只在 provider 层）。
- kotlinx.serialization `@Serializable` + default value 确保旧 JSON blob
  forward-compat，`Part.Tool.state.estimatedTokens=null` 在旧数据上的解码路径
  是官方文档明确支持的模式。

**Coverage.** 两条新 case（都在 `CompactorTest.kt`）：
- `pruneDropsBiggestToolOutputFirstWhenBudgetAllowsOneDrop`：同一 assistant
  turn 内 2 条 tool outputs（small + big），预算只允许 drop 一条 —— 断言 big
  被 drop，small 保留。旧算法会按遍历顺序（= 小的在前）drop 小的。
- `pruneHonoursEstimatedTokensOverrideOverByteHeuristic`：一条 `outputForLlm="tiny"`
  但 `estimatedTokens=5000` 的 tool —— 断言 drop 基于 stamp 而非 byte-length
  heuristic（byte = ~15 token，stamp 才暴露真实 5000 token 成本）。

旧 case `pruneDropsOldCompletedToolOutputsButKeepsRecentTurns` 仍 green —— 只有
一条 pre-window candidate，新旧算法在单候选场景等价。

完整 gradle：`:core:jvmTest` ✓、`:apps:cli:test` ✓、`:apps:desktop:assemble` ✓、
`:apps:android:assembleDebug` ✓、`:core:compileKotlinIosSimulatorArm64` ✓、`ktlintCheck` ✓。

**Registration.** 无 —— 纯内部重构 + 两个 optional 字段；不改接口、不动 5 个
`AppContainer`、没新 tool。
