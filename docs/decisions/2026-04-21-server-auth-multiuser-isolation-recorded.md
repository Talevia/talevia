## 2026-04-21 — server-auth-multiuser-isolation：显式记录 `apps/server` 单租户假设 + 升级触发条件（Rubric 外 / 安全债务）

Commit: `<self-ref>` (docs + ServerContainer KDoc, single commit — docs-only debt record).

**Context.** `apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt` 从 host 环境变量读 `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `REPLICATE_API_TOKEN`，把 `TALEVIA_MEDIA_DIR` 当做进程级单一 media 目录，所有 session / project / snapshot 共享同一个 SQLite 文件（`TALEVIA_DB_PATH`）——每个 HTTP 请求访问的是同一个整体 graph。`TALEVIA_SERVER_TOKEN` 提供一个 on/off 式 bearer token 做访问控制，但 token 一旦对外就等于把整个单租户 graph 全盘托出：任何持 token 的 caller 可以读所有其他人的 session/project，消费同一批 API key 额度，污染同一个 asset catalog。

VISION 里 server 是"可选 headless"的 CLI/desktop 无头孪生，典型部署是"开发机跑 server，自己连自己"。这套假设对那一场景是正确的，但如果未来有人把 server 公开暴露给远程多用户，当前代码会把单租户漏洞变成大型事故。这次**不修代码实现**——保留当前"可选 headless"的语义——但要把假设**显式落地**到文档 + KDoc，防止未来 contributor（包括 LLM）误以为 server 已经做了 multi-user 防护。

**Decision.** 两处写字：

1. `ServerContainer` 的类级 KDoc 加"**Assumes single-tenant**"大字 lede + 明确列出三条具体"**不安全公开暴露**"的事实：
   - API keys 进程级共享
   - `TALEVIA_MEDIA_DIR` 单目录、全局 asset catalog
   - SQLite 没有 tenant 列或 row-level ACL
   并直接 back-reference 到这份 decision 文件。
2. 本 decision 文件记录**升级到多租户的触发条件 + 最小方案骨架**——未来有 driver 时 grep `docs/decisions/` 找这篇当起点。

**Hard triggers that should force the upgrade.** 按严重度递减：

1. **外部用户拿到 server URL + `TALEVIA_SERVER_TOKEN`** — 即使是 "受信内部测试"，单 token = 完全访问。本来就不该发生。
2. **Billing dashboard 上出现非 self-consumption 的 AIGC 账单峰值** — 说明 token 或 env 泄漏给了别人，别人在用你的 provider 额度。
3. **SQLite 里出现不认识的 session / project** — 别人在写入。
4. **产品决定 server 走正式对外路径**（例如 cloud-hosted Talevia as a service，或多成员团队内部共享实例）。这是 planned migration，不是事故。

只要 #1-#3 任一发生过，**立即** rotate 所有 API keys + token，restore SQLite 到最近一次单租户快照，并启动 #4 的迁移。

**Minimal upgrade skeleton（when triggered）.**

A. **Per-tenant secret scoping.**
   - 去掉 `env["ANTHROPIC_API_KEY"]` 这类全局读取。引入 `TenantSecretStore`：按 `tenantId` 查询存储（例如 encrypted Postgres 表或 HashiCorp Vault）。
   - `ServerContainer` 改为 `ServerContainer.forTenant(tenantId)` 工厂；或 `Agent.run` 时带 `tenantId` 动态解析 secrets。
   - `/session` HTTP endpoint 必须从认证层（OAuth/OIDC/SSO）映射出 `tenantId`；拒绝未授权请求。

B. **Per-tenant media isolation.**
   - `TALEVIA_MEDIA_DIR` 改成根目录，按 `<root>/<tenantId>/` 分子目录。`FileMediaStorage` 构造时注入 `tenantId` 路径 prefix。
   - 或者换到 S3 / R2 桶 + IAM 策略按 `tenantId` 限 prefix。
   - `MediaPathResolver` 实现必须拒绝跨 tenant 路径解析（ACL check）。

C. **Per-tenant DB isolation.**
   - 方案 A: 每个 tenant 一个 SQLite 文件 `<db-root>/<tenantId>.db`——部署简单，迁移现有 schema 直接就能用，但 tenant 发现需要目录扫描。
   - 方案 B: 迁到 Postgres，所有表加 `tenant_id` 列 + RLS policy。scale 更好但 schema 迁移是一个大工程。
   - 方案 C: DB per tenant + service discovery——云厂商推荐做法，最贵但最干净。
   v0 → v1 升级默认选方案 A（lowest blast radius）；真正 scale 到 > 100 tenants 时再考虑 B/C。

D. **Session/project access control.**
   - 每个 `SessionId` 归属一个 `tenantId`。所有 `SessionStore` / `ProjectStore` 的读写 API 强制按 tenant 过滤。
   - `ServerPermissionService` 决策时要看 `(tenantId, sessionId)` 双元组，不能只看 session。
   - 跨 tenant session/project fork / copy 要求显式 tenant-granted IAM 权限，不能继承 "can access my own projects" 这种隐式权限。

E. **Audit + rate limit.**
   - 每个 tool 调用记 `(tenantId, toolId, inputHashPrefix, tsEpochMs)` 到独立 audit table。API key 异常消耗触发告警。
   - Per-tenant rate limit on AIGC tools — token 额度耗尽时 tenant 不能互相连累。

**Alternatives considered.**

1. **Option A (chosen): 现在只记录假设 + 触发条件，不动代码。** 当前 server 是"可选 headless"单租户用途，引入 tenancy 会立刻让 CLI/desktop 开发者也付 migration 成本而无收益。假设落到 KDoc + decision，grep-friendly。
2. **Option B: 现在就加 per-tenant scoping + 禁掉无认证启动。** 拒绝：产品 driver 不存在，过度工程。且禁掉无 auth 启动会破坏 `./gradlew :apps:server:run` 本地开发体验（user → open → localhost chat）。
3. **Option C: 把 server 模块从 repo 里彻底拆出去，直到有 real tenancy 需求再重写。** 拒绝：server 同时也是 CLI/desktop 的 headless 孪生测试目标（`:apps:server:test`），是 validation 基础设施。拆掉损失大。

**Non-goals for this decision.** 不实现任何 tenancy 逻辑；不改 `ServerContainer` 的构造签名；不新增 HTTP endpoint。**只**加 KDoc 警示 + 本文档。

**Coverage.** 无代码行为变化——ktlint 是唯一需要通过的检查（KDoc 是 Kotlin doc 注释，不影响编译或运行时）。`:core:ktlintCheck` + `:apps:server:test` 应该原样通过。本决议是文档 / 制度层面的 debt record，不走 `feat` + `docs(decisions)` commit pair。

**Registration.** 无需注册 — docs + KDoc 只在 `ServerContainer` 类上。
