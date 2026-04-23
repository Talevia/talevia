## 2026-04-22 — Per-session tool disable registry (VISION §5.4)

**Context.** Backlog bullet `session-tool-disabled-registry`：用户说 "别再
用 generate_video 了，太贵" 时，agent 只能记在 reasoning 里凭自觉遵守，后续
turn 里模型仍能随时看到 `generate_video` 的 tool spec 然后又调用它。同样
的 gap 对 "别用 shell 了"、"这个 session 只操作 timeline" 等 session-scoped
用户指令都存在。已有 `ToolApplicability.RequiresProjectBinding` /
`RequiresAssets` 的 eligibility filter 证明"request 组装时按 context 裁剪
tool spec" 是可行且安全的路径 —— 这轮给同一层加一把 session-scoped 锁。

Rubric delta：§5.4 "agent 从用户指令里学会长期遵守" 无 → 部分（session 内
持久，next turn 立即生效；但 persistent 作用域是 session 而不是 project，
切到新 session 会从默认全部启用开始）。

**Decision.** 4 处最小改动 + 1 个新 tool：

1. **`Session.disabledToolIds: Set<String> = emptySet()`**（新 field，
   default 空 set 保证序列化向前兼容 §3a-7）。
2. **`ToolAvailabilityContext.disabledToolIds: Set<String> = emptySet()`**
   （新 field）。
3. **`ToolRegistry.specs(ctx)` 在既有 applicability filter 之后叠一层
   `it.id !in ctx.disabledToolIds` 过滤**。顺序 applicability → disabled，
   因为 applicability 是 Core-intrinsic（"这个工具现在真能不能跑"），
   disabled 是 session-user-intent（"就算能，也别露出"）。先裁前者让
   disabled list 里的 dead id（比如未注册的 tool）不影响判定。
4. **`Agent.run` 读 `sessionSnapshot.disabledToolIds` 并 forward 到
   `AgentTurnExecutor.streamTurn(..., disabledToolIds)`**，后者继续塞进
   `ToolAvailabilityContext`。re-read-per-step 保证 mid-session
   `set_tool_enabled` 下一 turn 立即生效，mirror `spendCapCents` 的既有
   机制（commit `dd7b3b6`）。
5. **新 tool `SetToolEnabledTool`**（commonMain，`session.write`
   permission）：
   - Input: `sessionId: String? = null, toolId: String, enabled: Boolean`
   - Upsert shape（§3a-2 不做 Define/Update split）：一把工具 flip 两个方向；
     no-op 时 `changed=false` 回传。
   - Output: `{sessionId, toolId, enabled, changed}`。
   - 不 validate `toolId` 是否当前 registered —— session 持久化的 set 允许
     提前禁某个未来加载的 tool（e.g. env-gated AIGC engine）；disable 列表
     里的 dead id 不影响 registry filter 因为 `!in disabledToolIds` 对不存在
     的 id 平凡成立。
   - 在 5 个 `AppContainer`（CLI / Desktop / Server / Android / iOS Swift）
     都注册。
   - Tool spec 每 turn +约 220 token，§3a-10 可见范围内 —— 收益是
     session-scoped 禁用这个能力本身目前没有等价物，不是"又加一把类似 query
     的 tool"。

**Alternatives considered.**

1. **不持久化，只在 system prompt 里写 "用户说别用 X"**（被拒）：模型对长
   上下文指令的服从率低于实际过滤；本 decision 选择硬约束（filter at spec
   assembly）而非软约束（prompt instruction），因为硬约束是确定性的，符合
   VISION "Core 决定，模型执行" 的基调。
2. **加 `disable_tool` + `enable_tool` 两个 tool**（backlog bullet 原方向之一，
   被拒）：违反 §3a-2（同样的 Define/Update 分裂反模式，两个互斥分支等于两
   倍 spec 成本换零收益）。`SetClipAssetPinnedTool` 已经确立"一把 upsert 带
   `enabled: Boolean` 参数" 的模式，这轮继续沿用。
3. **把 mutation 折进 `session_query`**（backlog bullet 另一方向，被拒）：
   `session_query` 是 read-only query primitive，schema + contract 都按"只读"
   构建。引入 mutation select 破坏类型语义；`rename_session` /
   `set_session_spend_cap` / `archive_session` 已经确立了"session mutation
   用独立小 tool"的 pattern，新 tool 与之对齐。
4. **按 tool category 批量禁** (disable-all-aigc)（被拒）：categories 在
   项目里不是一等概念（tools 只有 id + permission），引入 category 是 §3a-5
   新类型的风险。粒度保留 per-tool-id；真需要批量时由模型连续发几个 call。

业界共识对照:
- OpenCode 的 tool definition（`packages/opencode/src/tool/tool.ts`）本身没
  有 per-session disable 概念 —— 模型自己的 system prompt 是约束点。Talevia
  这轮走硬约束路线是主动偏离；理由是 "Core 决定，模型执行" 的北极星，以及
  `ToolApplicability.RequiresProjectBinding` 已经验证的 filter 模式。
- `SetClipAssetPinnedTool`（`2026-04-21-debt-merge-pin-unpin-tool-pairs.md`）
  是 `set_<concept>_enabled(enabled: Boolean)` 模式的直接先例。

**Coverage.** `SetToolEnabledToolTest` 四个 case：
- `disablingATollAddsItToDisabledToolIds` —— happy path，写进 session state。
- `reEnablingRemovesFromDisabledToolIds` —— 反向，从 session state 移除。
- `noopWhenAlreadyInRequestedState` —— `changed=false` 在两个方向都成立
  （already-enabled 和 already-disabled），避免 idempotent call 的双重 mutate。
- `registrySpecsHidesDisabledTools` —— §3a-9 "测试覆盖语义" 核心 case：
  直接对 `ToolRegistry.specs(ctx)` 断言 disabled tool 从 spec 列表消失，同时
  `registry["generate_video"]` 仍可 resolve（tool 没 unregister，只是不 expose
  给 LLM；mid-session 已 dispatched 的 call 能跑完）。
- `rejectsBlankToolId` —— guard against typos。

全 5 端 gradle：`:core:jvmTest` ✓、`:apps:cli:test` ✓、`:apps:desktop:assemble`
✓、`:apps:android:assembleDebug` ✓、`:core:compileKotlinIosSimulatorArm64` ✓、
`ktlintCheck` ✓。

**Registration.** 5 个 `AppContainer`（CLI / Desktop / Server / Android /
iOS Swift）的 session 注册段 `register(SetSessionSpendCapTool(sessions))`
之后一行 `register(SetToolEnabledTool(sessions))` —— 与 SpendCap tool
对称；每处 imports +1 行、registrations +1 行。
