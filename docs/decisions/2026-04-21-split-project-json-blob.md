## 2026-04-21 — split-project-json-blob：把 snapshots + lockfile.entries 拆到独立 SQLDelight 表（VISION §5.3 / §3.4）

Commit: `a2ecb40` (pair with `docs(decisions): record choices for split-project-json-blob`).

**Context.** `SqlDelightProjectStore.mutate()` 每次调用都把整个 `Project`（timeline + source DAG + assets + renderCache + `snapshots` + `lockfile.entries`）序列化回 `Projects.data` 这一列。`add_clip` 一次→整份 encode、整份 write-through。但 `snapshots` 和 `lockfile.entries` 是**append-only 语义**（一旦写入就不会改；新生成就追加一条）——把它们和 timeline 热路径绑在一列，每次 mutate 的写放大会随项目寿命**非线性**恶化：一个长跑 6 个月的叙事片项目里会积 300+ lockfile entries（每次 `generate_image` / `generate_video` 追一条），每一次 `set_clip_volume` 都要把这 300 条重 encode。设计约束 §3a #3 把这个模式列为硬性否决。

Backlog `unify-project-query` 上轮刚做完（commit `e0e5795`、decision `2026-04-21-unify-project-query.md`），P0 第 1 条来到 `split-project-json-blob`。

**Decision.** Schema 升级到 v2，新增两张**兄弟表**：

- `ProjectSnapshots(project_id, snapshot_id, label, captured_at, payload)` — PRIMARY KEY `(project_id, snapshot_id)`，INDEX `(project_id, captured_at ASC)`。`payload` 是整份 `ProjectSnapshot` JSON（含嵌套的 `Project`）。
- `ProjectLockfileEntries(project_id, ordinal, input_hash, asset_id, tool_id, payload)` — PRIMARY KEY `(project_id, ordinal)`，INDEX `(project_id, input_hash)` 和 `(project_id, asset_id)`。`ordinal` 是显式递增的追加序号（不是 ROWID），读回时 `ORDER BY ordinal ASC` 得到原 `Lockfile.entries` 顺序。`input_hash` / `asset_id` / `tool_id` 冗余出来做索引查询；`payload` 还是权威版本的完整 `LockfileEntry` JSON。

`Project` data class **完全不动**：`snapshots: List<ProjectSnapshot>`、`lockfile: Lockfile` 字段及其 default 值保留。序列化向前兼容——老 blob（v1 带 inline 数据）的解码无差异。

`SqlDelightProjectStore` 变化：

- **`upsert(title, project)`**：`db.transaction {}` 内—
  1. 写 `Projects.data`：`project.copy(snapshots=emptyList(), lockfile=Lockfile.EMPTY)` 编码后的 slim blob。
  2. `ProjectSnapshotsQueries.deleteAllForProject(id)` → `forEach insert`。
  3. `ProjectLockfileEntriesQueries.deleteAllForProject(id)` → `forEachIndexed insert`（`ordinal = index`）。
- **`get(id)`**：读 `Projects.data` 解码出 `base: Project`，然后 `selectByProject` 两张表 → `snapshotsFromTable` / `entriesFromTable`。**Legacy fallback**：如果表为空但 `base.snapshots` / `base.lockfile.entries` 非空，保留 blob 里的值（这是 v1 项目还没被 mutate 过的情况）；否则用表里的值。返回 `base.copy(snapshots=..., lockfile=Lockfile(...))`。
- **`list()`**：`selectAll` 后每行走 `assembleProject(row.data_, row.id)`——同一个读路径。
- **`delete(id)`**：`db.transaction {}` 一起清 `Projects` + 两张兄弟表（没靠 FK CASCADE，SQLite 的 `foreign_keys` pragma 项目范围没开）。
- **`setTitle` / `summary` / `listSummaries`**：不动——只读 `Projects` 行的元数据列。

**Migration.** SQLDelight 约定 `N.sqm` 是"从 v_N 迁移到 v_{N+1}"的 DDL-only 脚本：

- 新增 `1.sqm`：两张新表 + 三条 INDEX 的 `CREATE TABLE` / `CREATE INDEX`。
- `TaleviaDb.Schema.version` 生成为 `2`。
- `TaleviaDbFactory.open()` 已经处理 `PRAGMA user_version`：user_version=0 跑 `Schema.create`（含新表）；user_version=1 跑 `Schema.migrate(driver, 1, 2)`（执行 `1.sqm`）；user_version>target 拒绝打开（降级保护）。

**写时迁移**（lazy）：existing projects 一律留在 v1 blob 里，新表里没行。第一次任意 `mutate` 触发的 `upsert` 自动走上面的 "slim blob + 两表行"——snapshot/lockfile 数据从 blob 搬到表。没动过的老项目继续通过 legacy fallback 读取，零停机、零数据丢失、零一次性迁移脚本。付出的是读路径每次多两个 `SELECT`——但这两个 select 都有 `project_id` 索引，SQLite 查一个命中索引的空表是微秒级。

**Alternatives considered.**

1. **Eager migration — 在 `Factory.open()` 跑 `Schema.migrate` 之后，Kotlin 侧遍历所有 Projects、解码 blob、拆出数据、写两表、重新 encode slim blob 回 Projects.data。** 拒绝：
   - 打开 DB 时间和项目数成正比，app 启动延迟不可预测；
   - 需要额外的一次 "迁移完成标记"（另一个 PRAGMA 或 meta 表），否则重启会重迁；
   - 失败回滚复杂度高（迁移到一半 crash → 部分项目在新 schema、部分在旧）。
   
   Lazy migration 把成本摊到第一次 mutate，那一刻用户已经在操作这个项目、额外几毫秒不敏感。

2. **SQLDelight `.sqm` 里直接执行 JSON 操作**（SQLite 1.38+ 有 JSON functions，可以在迁移里 `json_extract` 拆出子数组）。拒绝：
   - `.sqm` 是纯 SQL DDL/DML，SQLite JSON 功能依赖运行时编译选项，Android 端的 SQLite 版本在旧 API level 上不保证带 JSON 扩展；
   - 即便能跑，写出来的 SQL 是不可测、不可单步调试的一坨——失败模式比 Kotlin 迁移更难诊断；
   - 我们在 commonMain 写 SQLDelight 要保持跨平台一致。

3. **保留 `Project.snapshots` / `Project.lockfile` 在 blob 里不动，只把"hot path"字段（timeline / source / assets / renderCache）单独抽到新表。** 拒绝：
   - 等于颠倒了责任——append-only 的部分反而继续承受 encode 成本，热路径的字段变成独立表后其实读取成本更高（每次 `get` 要 join），write amplification 没解决还引入新的读放大；
   - §3a #3 的具体指导就是 "append-only → 独立 SQLDelight 表"。

4. **兄弟表用 FK CASCADE 替代代码层显式 delete。** 拒绝：SQLite 的 `PRAGMA foreign_keys = ON` 不是默认开的，需要在每次连接建立时设置；`TaleviaDbFactory` 目前没设，打开后引入一个跨平台设置有顺带的测试面（iOS 的 `NativeSqliteDriver` vs JVM `JdbcSqliteDriver` 行为不同）。代码层 `db.transaction` 里串行 `deleteAllForProject` 三张表更可预测、测试更简单。未来若启用 FK，可以把 CASCADE 加回来再删代码层的显式 delete。

5. **把 `Lockfile.entries` 压扁成 `input_hash -> LockfileEntry` 的映射表、不保留 ordinal。** 拒绝：`Lockfile.findByInputHash` 的契约是 "most recent match wins"（append-only ledger 允许同 hash 重复，最新的胜）。映射表的 PRIMARY KEY 强制唯一 → 后写覆盖前写 → 丢失历史。用 `(project_id, ordinal)` 复合主键 + `input_hash` INDEX 两全其美。

**Coverage.** `core/src/jvmTest/kotlin/io/talevia/core/domain/SqlDelightProjectStoreSplitTest.kt` — 9 tests：

- `schemaVersionIsTwo` — 直接断言 `TaleviaDb.Schema.version == 2L`。防 repopulate 或无意新增 `.sqm` 文件偷偷再抬一档。
- `upsertPersistsSnapshotsAndLockfileEntriesAndEmptiesBlob` — 写一个带 2 snapshots + 2 lockfile entries 的项目，验证两表各 2 行 + blob 里 snapshots/lockfile.entries 都被清空。
- `getReassemblesFullProjectFromBlobAndTables` — 写进去的 Project 原样读回（含嵌套 snapshot payload）。
- `lockfileOrdinalPreservesAppendOrderAcrossUpserts` — 同 `input_hash` 重复追加时，`findByInputHash` 返回后写入的那条（ordinal-driven 排序契约）。
- `legacyBlobWithInlineSnapshotsAndLockfileReadsCorrectly` — 直接往 `Projects.data` 写 v1 格式 blob（inline snapshot/lockfile，且两表空），`get` 能正确 fallback 到 blob 数据。
- `firstUpsertAfterLegacyReadMigratesToTables` — v1 legacy 行，`get` 后立刻 `upsert` → 两表填充 + blob 清空（lazy migration 的关键契约）。
- `deleteRemovesRowsFromSiblingTables` — `delete(pid)` 后三张表都没剩该 project 的行（无代码层 orphan）。
- `emptySnapshotsAndLockfileRoundTripCleanly` — 空 snapshots/empty lockfile 走一圈 upsert → get 保持空，不会因 delete+insert 产生幽灵行。
- `shrinkingLockfileRemovesRowsFromTable` — 先写 3 条 lockfile entries、再 upsert 只剩 1 条，验证表里真的剩 1 行（`upsert` 的 delete-all + re-insert 正确执行，不漏删）。这防止"缩短 list" 的 gc_lockfile 流被 silently 破坏。
- `listReturnsAllProjectsWithAssembledSiblings` — `list()` 批量读也正确组装每个 project 的兄弟数据（N+1 读但结果正确）。

既有测试 `ProjectStoreConcurrencyTest` 不动——mutex 语义没变，并发写验证还成立。

**Registration.** 无需工具注册——纯 Core 内部重构。所有平台通过 `commonMain` 共享 schema，`TaleviaDbFactory` 的 `PRAGMA user_version` 检查已经覆盖升级路径：

- JVM 应用（desktop / cli / server）：`TaleviaDbFactory.open()` 读 env 决定 path，`TALEVIA_DB_PATH` 指向现有 v1 DB 时会走 `Schema.migrate(driver, 1, 2)`。
- Android：`AndroidAppContainer` 用 `AndroidSqliteDriver(TaleviaDb.Schema, context, "talevia.db")` 自动处理版本——driver 内置比对 schema.version vs stored version，`migrate` 触发 `1.sqm`。
- iOS：Kotlin/Native 的 `NativeSqliteDriver` 同理；`AppContainer.swift` 里 `let driver = factory.createInMemoryDriver()` + `TaleviaDbCompanion.shared.invoke(driver)`—目前 iOS 用 in-memory，`Schema.create` 一步到位；未来接持久 DB 时 migration 由 driver 处理。

本次 schema v2 会在五端任一首次打开现有 v1 DB 时触发一次 DDL-only 迁移（3 条 CREATE TABLE + 3 条 CREATE INDEX，毫秒级），之后所有读写走新路径。

**Session-project-binding 注记（§3a.6）.** 本次改动不引入任何 tool input，`mutate` / `get` 直接收 `ProjectId`——等 `session-project-binding`（P1 backlog）落地后由 session context 注入，store 层签名保持不变即可。

**LLM context 成本（§3a.10）.** 零变化——没有新 tool spec、没有 helpText、系统 prompt 没动。唯一"被动省下"的是以后 `ListProjectSnapshotsTool` / `ListLockfileEntriesTool` 的读路径可以直接 SELECT 对应表 + LIMIT/OFFSET，不用解码整个 Project blob（后续 cycle 可以顺手把这俩工具改成直接读表，token 和内存都省）——但本 cycle 保守，这两个 tool 还是走 `store.get(id)` 的统一读路径。

**Non-goals / 后续切片.**

- 把 `ListProjectSnapshotsTool` / `ListLockfileEntriesTool` 改成直接 SELECT 对应表（避免 decode 整份 Project）。
- 把 `Lockfile.findByInputHash` 的 O(n) linear scan 换成 `@Transient` index（backlog P1 `lockfile-byhash-index`，独立的抽象层改动，不动存储层）。
- `renderCache` 的拆分——当前留在 blob 里。它不是 append-only，是每次 export 重算 + 替换的缓存，放 blob 里合理。除非 cache 条目数膨胀到成为 encode 瓶颈，不动。
