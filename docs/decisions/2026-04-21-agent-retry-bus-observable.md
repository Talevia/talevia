## 2026-04-21 — agent-retry-bus-observable：`agent.retry.*` per-reason counters（VISION §5.4）

Commit: `bc82cee`

**Context.** `BusEvent.AgentRetryScheduled` 在 Agent 里 publish，`EventBusMetricsSink` 接收并增 counter `agent.retry.scheduled`。问题是计数器扁平 —— ops dashboard 只能看到"本小时 retry 了 N 次"，但没法区分 overload / rate_limit / http_5xx / 其他。排障时分辨不出："是 provider outage 还是用户侧 rate limit 超标"决定了响应策略（应该 page provider oncall 还是降低调用并发）。backlog bullet 明确方向："给 `Metrics.counterName` 加 `agent.retry.count{reason=<slug>}` 分类 counter，`/metrics` Prometheus 端点就能按 reason label 查图。"

`BusEvent.AgentRetryScheduled.reason: String` 字段由 `RetryClassifier.reason(message, retriableHint)` 产生，内容是自由文本："Provider is overloaded" / "Rate limited" / 原始 provider 消息（例 `anthropic HTTP 503: overloaded_error: ...`）等。把自由文本直接当 counter 名会炸 Prometheus cardinality（每条独特消息产生一个 series），必须先 bucket 到固定 slug 集合。Rubric §5.4（observability：ops 能诊断真正发生了什么）。

**Decision.** `EventBusMetricsSink` 加 `retryReasonSlug(reason: String): String`，把自由 reason 映射到固定 slug 集合，然后 counter name 从扁平 `agent.retry.scheduled` 变成 per-reason `agent.retry.<slug>`：

| 输入 pattern（lowercase match） | Slug | 例子 |
|---|---|---|
| `http <429>` | `http_429` | "openai HTTP 429: rate_limit" |
| `http <5xx>` | `http_5xx` | "anthropic HTTP 503: overloaded_error" |
| `http <其他 3 位数>` | `http_<code>` | 其他 3xx / 4xx 消息（极少命中，RetryClassifier 已经过滤） |
| "overload" 子串 | `overload` | "Provider is overloaded" |
| "rate limit" / "rate_limit" / "too many" 子串 | `rate_limit` | "Rate limited" / "too many requests" |
| "quota" / "exhausted" 子串 | `quota_exhausted` | "Provider quota exhausted" |
| "unavailable" 子串 | `unavailable` | "Provider unavailable" |
| 其他 | `other` | provider-specific messages not matched |

**关键决策 — HTTP 优先级**：如果消息同时含 HTTP 状态码和 semantic 关键字（例 "anthropic HTTP 503: overloaded_error"），选 `http_5xx` 而不是 `overload`。原因：transport-layer signal 对 ops 更 actionable —— 503 is "provider is physically unreachable right now"，触发的是 "wait + check provider status"；"overloaded" 在 503 语境下是它的 cause string，不是另一个分类维度。Cascade 写成 "先检 HTTP 状态，再 fallback semantic 关键字" 让这个优先级从代码结构显式。

**Summing the family**：dashboard 查询 "所有 retry 总数" 用 `sum(agent.retry.*)` —— bullet 原文要的 "per-reason label" 和 "总数" 都支持。没有保留一个扁平 `agent.retry.scheduled` counter 来避免重复计数（每个 retry 事件 **只增 1 个** family counter，不是 1 总 + 1 slug）。

**cardinality bound**：`other` bucket 吸收未分类消息，避免任意 provider-formatted string 成为独立 counter name。`http_<code>` 对非 429 / 5xx 的 code（e.g. `http_400`）理论上可生成新 counter，但 `RetryClassifier` 本身已经 filter 掉非 retriable HTTP 状态（只 5xx / 429 会让 reason 返回非 null），所以实际 cardinality ≤ 8: `{http_429, http_5xx, overload, rate_limit, quota_exhausted, unavailable, other}` + 偶发 `http_xxx`。

**Alternatives considered.**

1. **Option A (chosen)**: slug 映射 + per-slug counter（`agent.retry.<slug>`）。优点：Prometheus-native scrape formatting（counter name 本身就是标签，和同项目其他 counter 一致）；固定 cardinality（≤ 8）；cascade 选 transport > semantic。缺点：dashboard 查 "总数" 要 sum；但 backlog bullet 的 direction 允许（"agent.retry.count{reason=<slug>}" 是概念性表达，实际实现 per-counter-name 等价）。
2. **Option B**: Prometheus 标签 `agent_retry_scheduled_total{reason="<slug>"}`。拒绝：① 当前 `MetricsRegistry` 是简单 `Map<String, Long>`，没有 labels 基础设施；② 扩 labels 要加 value-tuple 作为 key（或 `MetricFamily` type），是 infra 级变更，不在本 bullet 范围；③ Prometheus exposition format 可以用 `counter_name{label="x"}` 表示，但实际 scrape 时是多个"value 对一个 label set"的 entries —— 和 name-per-slug 在 PromQL 查询层几乎等价（`sum by (reason) (agent_retry_*)` vs `agent_retry_scheduled{reason=x}`）。
3. **Option C**: 直接用 free-form `reason` 做 counter name。拒绝：cardinality 失控（provider messages 含 session-specific 细节如时间戳 / 请求 id；可能长度数 KB；series churn 炸 Prometheus）。
4. **Option D**: cascade 方向反转（semantic > transport）—— "overloaded" 的 503 → `overload`。拒绝：ops 更关心 transport-layer signal（"503 意味着 provider 整体不可用"），semantic 是 cause 不是 category。选 `http_5xx` 让 alert rules 更简单（"all HTTP 5xx > X per hour" 是通用信号）。
5. **Option E**: 保留扁平 `agent.retry.scheduled` 同时增 per-reason —— double counting。拒绝：double counting 让 dashboard panel 显示总数时必须减去 per-reason 和（或反之），数据真相分叉；sum(per-reason) 已经等于总数，扁平 counter 冗余。
6. **Option F**: 加 latency histogram (`agent.retry.wait_ms`)。拒绝：越界；当前 bullet 只要 per-reason counter，histogram 另算。KDoc 提及方向，留 follow-up cycle。

**Coverage.**

- **新增 `agentRetrySplitsByReasonSlug` test**（commonTest/metrics/MetricsTest.kt，~40 行）：publish 8 个 `AgentRetryScheduled` 覆盖 7 种 slug（含"rate_limit"两种形式：'Rate limited' + 'too many requests'），验证每个 slug 独立计数 + family 总数为 8。§3a.9 反直觉边界覆盖：`http_5xx` 优先级 over `overload`（"HTTP 503: overloaded_error" 分到 http_5xx）。
- 既有 `sinkCountsEachBusEventOnce` / `permissionRepliedSplitsOnAccepted` / `publishBeforeSubscribeIsDropped` 保持全绿（metrics 其他路径不动）。
- `./gradlew :core:jvmTest` + `:apps:server:test` + `:core:ktlintCheck` 全绿；4 端构建全绿。

**Registration.** 无 tool / 无 AppContainer 变化。纯 metrics sink 内部重构。

**§3a 自查.**

1. 工具数量: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: 不动。PASS。
4. 状态字段: slug 是 derived label 不是 domain flag。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: N/A。
7. 序列化向前兼容: `BusEvent.AgentRetryScheduled` 本身 schema 不变；只改 `EventBusMetricsSink.counterName` 内部。PASS。
8. 五端装配: `EventBusMetricsSink` 只在 server 端接入（desktop/iOS 等 app 不装 metrics；这是既有行为）；改动不动装配。PASS。
9. 测试语义覆盖: 8 种输入 × 7 种 slug + 1 family-sum invariant，覆盖 cascade 优先级、大小写不敏感、空匹配（"other" fallback）。PASS。
10. LLM context 成本: 0（metrics 是 server 内部，LLM 不可见）。PASS。

**Non-goals / 后续切片.**

- **Follow-up: `agent.retry.wait_ms` histogram** — 每次 retry 的 `waitMs` 可以喂给 `MetricsRegistry.observe`，给 ops dashboard 加一个 "retry 等待延迟分布"；backlog bullet 没要求，但当同类 `tool.<id>.ms` histogram 已经在 pipeline 里。留给专门 cycle。
- **Follow-up: Prometheus label-based counter refactor** —— 把 `MetricsRegistry` 从 `Map<String, Long>` 升级成 `Map<MetricKey, Long>` where `MetricKey = (name, labels)`。infra 级变更，独立 cycle，会 touch scrape endpoint 和所有 per-reason counter 的接入方式。
- **不改 `RetryClassifier.reason`** —— 它的 free-form 返回 value 继续给 `BusEvent` / logging 用；slug bucketing 只在 metrics 层（"display as metric" vs "surface as event"）。
- **不删 `AgentRetryTest`** —— 既有 retry 触发测试对准确 reason string 做断言，metrics 分类不影响 retry 事件本身。
