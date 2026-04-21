## 2026-04-21 — process-level-project-mutex：单进程 Mutex 够用现在，记录升级触发条件（Rubric 外 / 操作债务）

Commit: `a551b6c` (docs-only debt record, single commit).

**Context.** `SqlDelightProjectStore.mutex` 是 kotlinx `Mutex`，进程内可见。Desktop / CLI / 单副本 server 都是单进程读写同一个 SQLite 文件时这足够—两个并发 `mutate` 串行。

**Why it's debt, not bug today.** 当前 server 是 "可选 headless" 单实例，没有多副本需求。即便 server 模块未来被独立部署多个实例，它们通常 **不共享** SQLite 文件（每个副本有自己的 `TALEVIA_DB_PATH`），所以也不会撞这个坑。

**Trigger for upgrade.** 触发重构的硬信号：
1. 两个 JVM 进程（例如一个 CLI + 一个 server，或两个 server 副本）真的指向同一个 `TALEVIA_DB_PATH`。
2. 观察到写丢失 / 并发 `mutate` 交错（通过 `TALEVIA_MEDIA_DIR` 下的 db 行读出来和预期不一致，或 lockfile entries 出现乱序 ordinal）。
3. 决定用多个进程分担 agent 工作负载（例如后台 regenerate worker + 前台交互 agent 写同一个项目）。

**Proposed refactor when triggered.**
- **Option A**: `BEGIN IMMEDIATE` transaction 包 SQLite 的写——SQLite 层面的进程间串行。成本低，但读会被写阻塞（默认 5s busy timeout 就挂），需要 tuning。
- **Option B**: SQLite advisory lock via `PRAGMA locking_mode = EXCLUSIVE`——同文件只允许一个 writer，其他进程读也会被阻。更严格也更暴力。
- **Option C**: 改用 Postgres / 真 transactional store——只有 server 走高并发产品化路径时才值得。

**Non-goals.** 本 cycle 不动代码行为——只在 `SqlDelightProjectStore` 加一行 KDoc 指向本 decision，和删 backlog bullet。

**Alternatives considered.** 2：
- Option A (chosen): 记录 + 留触发条件。理由：当前无 driver，预防性抽象会增加复杂度没收益。
- Option B: 现在就加 `BEGIN IMMEDIATE`。拒绝：会引入 SQLite busy-timeout 配置和 retry 逻辑，单进程场景下徒增复杂度和 tail-latency 噪声。

**Coverage.** 无行为变化 — 本决议是观察记录。KDoc 里留 "search this file when multi-process becomes real"。

**Registration.** 无需注册。
