## 2026-04-22 — provider-retry-on-transient：已在 `5de0ce8` + `bc82cee` + `d345a93` 落地（VISION §5.4）

Commit: `<docs-only>` — no feat pair; this decision retires a stale backlog bullet.

**Context.** 2026-04-22 repopulate 把 `provider-retry-on-transient` 写为 P0："单次 429 / 5xx / 网络抖动立即冒泡为 tool error，agent 无回退策略"。实际走读 `core/src/commonMain/kotlin/io/talevia/core/agent/RetryPolicy.kt` + `Agent.kt:381-430` 后发现：

- **重试循环已存在**：`Agent.runLoop` 在 `turnResult.error` + `retriable` 命中时执行 `attempt = 1..maxAttempts-1` 的退避循环。由 `private val retryPolicy: RetryPolicy = RetryPolicy.Default` 注入，默认 `maxAttempts = 4`（3 次重试）、`initialDelayMs = 2000`、`backoffFactor = 2.0`、`jitterFactor = 0.2`（±20%）、`rateLimitMinDelayMs = 15_000`。即 bullet 要求的 "3 次 / 2s / 4s / 8s" 正是默认策略（带 jitter 的 2_000 → 4_000 → 8_000，capped by `maxDelayNoHeadersMs = 30_000`）。
- **分类器已存在**：`RetryClassifier.reason(message, retriableHint)` 和 `.kind(...)` 专门为 429 / 5xx / "overloaded" / "rate limit" / "too many requests" / "exhausted" / "unavailable" / "network" / "timeout" / "connection reset" 判定 `BackoffKind.RATE_LIMIT` / `SERVER` / `NETWORK` / `OTHER`。context-overflow 错误显式不可重试。
- **Provider 侧已填 retriable hint**：`AnthropicProvider.kt:113` 和 `OpenAiProvider.kt`（同型）都在 HTTP 响应上设置 `retriable = status >= 500 || status == 429 || status == 408`，并解析 `retry-after-ms` / `retry-after` header 填充 `retryAfterMs`。
- **Telemetry 已存在**：每次 scheduled retry 发布 `BusEvent.AgentRetryScheduled(sessionId, attempt, waitMs, reason)`（Agent.kt:425-431）；`agent.retry.<reason>` 计数器在 `MetricsRegistry` 中按 reason slug 分桶（`bc82cee`）。跨 provider fallback 发布 `BusEvent.AgentProviderFallback(fromProviderId, toProviderId, reason)`（Agent.kt:395-402）。
- **相关决策已落地**：`docs/decisions/2026-04-22-adaptive-retry-backoff.md`（jitter + rate-limit floor）、`docs/decisions/2026-04-22-agent-retry-bus-observable.md`（bus 事件）。

bullet 里提出的 "新 `BusEvent.ProviderRetry`" 也是冗余：`AgentRetryScheduled` 已承担同一语义（session-scoped, attempt-indexed, reason-tagged），再加一个同义事件只会把 bus 订阅者的 filter 复杂化。

**Decision.** 删除 `docs/BACKLOG.md` 里的 `provider-retry-on-transient` 这一条 P0 bullet，并留下本 decision 作为"已查证、已落地、无新工作可做"的书面记录。下次 repopulate 不再把 retry 当 gap。

**Alternatives considered.**

- *Option A (chosen): 删 bullet + 写 "already shipped" decision*。精确对应当前 VISION §5.4 评分状态（"有"），避免下轮再把同一个虚假缺口排进 P0。符合既有"already shipped"惯例：`2026-04-22-cross-session-spend-aggregator-already-shipped-in-8964878.md`、`2026-04-22-project-diff-source-graphs-already-exists.md`。
- *Option B: 跳过 bullet、保留在 BACKLOG 原样*。拒绝：skill 规则把"跳过保留"留给需要用户决策或踩红线的场景；本 bullet 是作者（我）repopulate 时没充分读码造成的假缺口，留着只会让后续 cycle 再次原地踏空。
- *Option C: 重新定义 bullet 为"把 4-attempt 默认值改成 3" / "加 `BusEvent.ProviderRetry` 别名"*。拒绝：前者是 tuning 而非 gap（当前 OpenCode 也用 4 次），后者是增加同义事件。§3a-1 "工具/API 不净增" 适用。

**Coverage.** `core/src/jvmTest/kotlin/io/talevia/core/agent/AgentRetryTest.kt` 已经覆盖：default policy、max attempts 退出、retry-after header 优先级、分类器分桶。`core/src/jvmTest/kotlin/io/talevia/core/agent/RetryPolicyTest.kt` 覆盖 backoff 曲线 + jitter 边界。`AgentProviderFallbackTest.kt` 覆盖 exhausted-retry → provider 切换路径。本 decision 不新增代码也不新增测试。

**Registration.** 无需注册——pure docs 清理，不动任何装配点。
