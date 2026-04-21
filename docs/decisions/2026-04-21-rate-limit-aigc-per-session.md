## 2026-04-21 — rate-limit-aigc-per-session：`SessionRateLimits` 占位类落地，无 live 强制（Rubric 外 / 操作债务）

Commit: `cca6d80` (pair with `docs(decisions): record choices for rate-limit-aigc-per-session`).

**Context.** VISION 对 AIGC 操作有成本意识（cost/Assistant.cost、Compactor 读 token usage）但**运行时没有任何 per-session 上限**。长跑 session 的失控场景：
- Agent 卡在工具错误重试 loop 里反复调 `generate_image` / `generate_video`——每次 Sora / DALL-E 调用都是实钱。
- 用户粘了一段 markdown 诱导 agent 展开成百上千个 shot 的 `generate_music`。
- 内部 e2e test 被漏写 `@EnabledIfEnvironmentVariable`，跑 CI 时 burn 真 API 额度。

Backlog bullet 明确：**先记债，暂不实现**——未来某天当这类场景把账单烧到痛点才上限流，现在只把"config shape 和 trigger 条件"写下来，避免到时候设计要从零起。

**Decision.** 新增 `core/session/SessionRateLimits.kt` 占位 data class：

```kotlin
@Serializable
data class SessionRateLimits(
    val maxCostPerSessionUsd: Double? = null,  // 成本上限（美元）
    val maxCallsPerMinute: Int? = null,        // 每分钟 AIGC 调用数
    val maxTotalCalls: Int? = null,             // 终身 AIGC 调用数
) {
    val isUnlimited: Boolean get() = (all three == null)
    companion object { val UNLIMITED = SessionRateLimits() }
}
```

**无 wiring**：
- 无 `Agent` ctor 改动，无 `Session` 字段添加，无任何强制路径。
- 类存在仅作为**将来的 wire contract**——字段名定稿，不会因为将来 cycle 还要重命名而引起 serialization 迁移。
- 没有 `BusEvent.SessionRateLimitTripped`，没有 `SessionRateTracker`。

**6 个测试**（`SessionRateLimitsTest`）把当前 API 形状钉死：
- `defaultsAreAllNull` — 默认构造 = 全 null。
- `unlimitedConstantEqualsDefault` — `SessionRateLimits.UNLIMITED` = `SessionRateLimits()`。
- `anySetCapFlipsIsUnlimitedFalse` — 设置任一 cap → `isUnlimited == false`。
- `defaultEncodedJsonIsEmptyObject` — `JsonConfig.default.encodeDefaults = false` + nullable fields → `{}`。**零字节代价**持久化到将来存储层。
- `fullConfigRoundTripsThroughJson` — 三个字段齐设 → encode/decode 等价。
- `legacyBlobWithoutFieldsDecodesToUnlimited` — 老 blob（`{}`）→ `UNLIMITED`。向前兼容钩子。

**三条触发条件（trigger spec，给未来 cycle）**：

| # | 触发 | 指标来源 | 拒绝姿态 |
|---|---|---|---|
| 1 | session 累计 cost 超 `maxCostPerSessionUsd` | `Assistant.cost.usd` 求和（`listMessages` 过滤 assistant 聚合） | `PermissionDecision.Denied("rate limit: cost")` |
| 2 | 最近 60s 内 AIGC 调用数 > `maxCallsPerMinute` | 扫 `listSessionParts` 里 `Part.Tool` where `toolId in AIGC_TOOL_IDS` 且 `createdAt > now - 60s` | `PermissionDecision.Denied("rate limit: rate")` |
| 3 | session 累计 AIGC 调用数 > `maxTotalCalls` | 同 #2 不加时间窗 | `PermissionDecision.Denied("rate limit: total")` |

`AIGC_TOOL_IDS` 候选集：`generate_image` / `generate_video` / `generate_music` / `synthesize_speech` / `upscale_asset` / `transcribe_asset`（注：`transcribe_asset` 属 ML 非 AIGC，可能不计入）。最终集合由实现 cycle 定，这里只列候选。

**实现 cycle 会做的事（非本轮）**：
1. `Agent` ctor 加 `rateLimits: SessionRateLimits = UNLIMITED` 参数。
2. `SessionRateTracker(store, session, limits)` 类：每次 `dispatchTool` 前调 `tracker.check(toolId): RateDecision`。
3. `AgentTurnExecutor.dispatchTool` 在 permission check 之后、tool.dispatch 之前调 tracker。
4. `BusEvent.SessionRateLimitTripped(sessionId, trigger: RateTrigger)` 让 UI 展示"你用了 $8.23 / $10"。
5. 5 端 AppContainer 可选从 env var / config file 读限制（如 `TALEVIA_SESSION_COST_MAX_USD=10`）。
6. 测试覆盖并发（同 session 两路并发调用如何不 double-count）、边界（刚好达到上限 vs 超过 1 美分）、retry 不重复计费。

**Alternatives considered.**

1. **Option A (chosen)**: 只加 data class + 测试，零 wiring。优点：成本最低；字段命名提前敲定；`encodeDefaults=false` + 空 `{}` 序列化保证零持久化字节增长；未来实现 cycle 可以直接用这个 config shape 不用再 PR review 命名。缺点：有人看到代码以为有限流生效，踩坑。缓解：KDoc 顶部大字标 "**Placeholder**"；`isUnlimited` API 强提示；实现 cycle 到来时决策文件有迹可循。
2. **Option B**: 一次性做完（class + wiring + tracker + bus event + 5 端 config）。拒绝：bullet 明说 "暂不实现"；而且上线一个限流机制会改变默认行为（长跑 session 突然报错），不写 UI 配置和 error 消息会反向伤用户。先种 shape 再按需启用是更稳的路径。
3. **Option C**: 把 `SessionRateLimits` 做成 `object`（singleton config），而不是 data class。拒绝：单例配置无法 per-session 差异化——将来不同 session 可能需要不同 cap（免费 vs 付费用户、测试 session vs 生产）。`data class` + 字段注入天然支持。
4. **Option D**: 用 `Long` 单位 cents 而不是 `Double` USD。拒绝：和项目已有 `Assistant.cost.usd: Double` 字段对齐最重要；cent 精度对 "$10 cap" 粒度足矣但增加了 API 认知（caller 要 `cap * 100`）。USD 一致性更划算。
5. **Option E**: 把 trigger spec 也编进代码（enum `RateTrigger { COST, RATE, TOTAL }` + 一个 `which(limits, session): RateTrigger?` 纯函数）。拒绝：实现 cycle 的工作；现在写等于"写了一半不能测"。
6. **Option F**: 不加 data class，只留 decision doc。拒绝：文字会被遗忘；代码 + 测试让它活着（Schema.kt 同类钩子的 precedent 这轮就加了）。

**Coverage.** 6 个 `SessionRateLimitsTest` 用例（列于上）覆盖默认 / serialization / companion 的每一条 API。`./gradlew :core:jvmTest` 全绿；`:core:ktlintCheck` 全绿；4 端（iOS sim / Android APK / Desktop / Server）全绿。

**Registration.** 无注册——不是 LLM tool，不需要 AppContainer 里构造。将来 wiring cycle 会把它注入 `Agent` 构造器，届时 5 端同步一次。

**§3a 自查.**
1. Tool count: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 Project（这里是 session 层）。PASS。
4. 状态字段: 三个 `Int?` / `Double?` 配置字段不是 flag。PASS。
5. Core genre: N/A。
6. Session/Project binding: N/A。
7. 序列化向前兼容: 所有字段 `null` default；`encodeDefaults=false` → 空对象 `{}`；`ignoreUnknownKeys=true` 让将来加字段不破坏老 blob。PASS。
8. 5 端装配: 不变化。PASS。
9. 测试语义覆盖: 6 个 test 覆盖 happy default / json round-trip / legacy decode / companion / isUnlimited 边界。PASS。
10. LLM context 成本: 0（不是 LLM tool，KDoc 不入 prompt）。PASS。

**Non-goals / 后续切片.**
- 实际 enforcement wiring（`SessionRateTracker` / `Agent` 参数 / bus event / 5 端 config）—— 触发条件明朗后单独 cycle。
- 跨 session 限流（账户级 budget）—— 不同层的问题，`Account` / `Workspace` 概念目前不存在；用户自己多开 session 就能绕过 per-session 限流，account-level 配额需要不同的数据模型。
- 对 ML lane（transcribe / upscale）的限流范围：目前定义只覆盖生成类；如果 upscale 变贵（Replicate Real-ESRGAN 按分辨率 × 秒数计费），可以扩 `AIGC_TOOL_IDS`。
