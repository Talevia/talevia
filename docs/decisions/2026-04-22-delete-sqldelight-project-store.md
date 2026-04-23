## 2026-04-22 — Delete SqlDelightProjectStore + 4 project tables (VISION §3a-3 / clean-cut)

**Context.** Backlog bullet `delete-sqldelight-project-store`：commit `baad43f`
已把所有 5 个 `AppContainer` 切到 `FileProjectStore`，但 `SqlDelightProjectStore`
类（`core/src/commonMain/kotlin/io/talevia/core/domain/ProjectStore.kt` L98–320，
222 行）+ 4 张 SQLDelight 表 (`Projects.sq` / `ProjectSnapshots.sq` /
`ProjectLockfileEntries.sq` / `ProjectClipRenderCache.sq`) 仍躺在仓库里，没有
消费方读取。backlog 文本里"容器构造时仍 `SqlDelightProjectStore(db, ...)` 作
stub" 实际上已经不成立（CLI / Desktop / Server / Android Kotlin 容器都直接构造
`FileProjectStore(registry, defaultProjectsHome, bus)`，iOS 走 `newFileProjectStore`）—
所以是纯粹的"只剩定义，零消费方"的 dead code。Rubric delta：§3a-3 「长文件」维度
`ProjectStore.kt` 320 → 82 行，`.sq` 文件数 7 → 3（只留 Sessions / Messages /
Parts）。

**Decision.**

1. **删除 `SqlDelightProjectStore` 类**（L98–320）。`ProjectStore.kt` 只保留
   `ProjectSummary` data class + `ProjectStore` interface。interface 上保留
   `openAt` / `createAt` / `pathOf` 的默认实现（`openAt` / `createAt` throw
   `UnsupportedOperationException`，`pathOf` 返回 `null`）——这三个方法实际
   只被 `FileProjectStore` override，但保留默认让轻量测试 fake（如
   `ExportToolMimeTest.ThrowingProjectStore`）不必逐一实现；Compile-time
   验证 + 运行时 throw 已经覆盖误用场景。
2. **删除 4 个 `.sq` 文件**（`Projects.sq` / `ProjectSnapshots.sq` /
   `ProjectLockfileEntries.sq` / `ProjectClipRenderCache.sq`）。
3. **新增 `core/src/commonMain/sqldelight/io/talevia/core/db/3.sqm`**：
   ```sql
   DROP TABLE IF EXISTS ProjectClipRenderCache;
   DROP TABLE IF EXISTS ProjectLockfileEntries;
   DROP TABLE IF EXISTS ProjectSnapshots;
   DROP TABLE IF EXISTS Projects;
   ```
   按 SqlDelight 约定 `<N>.sqm` 对应 "schema v<N> → v<N+1>" 的迁移，本文件把
   现有 v3 数据库升级到 v4，`TaleviaDb.Schema.version` 从 3 自动升到 4。
   `TaleviaDbFactory.openFile` 读取 `PRAGMA user_version`，<target 时跑
   `Schema.migrate` 把老 DB 里的 4 张表一次性 DROP 掉；降级仍被明确拒绝。
4. **`ProjectStoreTestKit.kt`** 的 docstring 去掉"Replaces the old
   `SqlDelightProjectStore(TaleviaDb(driver))` boilerplate" / "reaching into
   `db.projectsQueries.selectAll()` to assert raw row state" 这两段历史说明
   —— 现在只有 `FileProjectStore` 了，说明是多余的。
5. **CLAUDE.md** "Observability / DB persistence" 小节同步更新：不再提 "The 4
   project-related tables ... are dead-code"，改成 "Schema v4 drops the 4
   project-related tables post-`baad43f`"。

数据删除是 one-way door（降级被 `TaleviaDbFactory` 拒绝），符合 user memory
`feedback_no_compat_clean_cuts` 的默认姿态。任何仍存在 v1 / v2 / v3 `talevia.db`
文件里的 Projects / ProjectSnapshots / ProjectLockfileEntries /
ProjectClipRenderCache 行都与磁盘上 `talevia.json` 文件 bundles 是脱同步状态
—— bundles 一直是权威源（commit `baad43f` 起），SQL 行只是残留。迁移不做数据
搬运，只做 `DROP TABLE`。

**Alternatives considered.**

1. **保留 4 张表 + 空转 `SqlDelightProjectStore`** 以防"万一将来还想恢复
   SQL 后端"（被拒）：违反 VISION §5.6 dead-code 信号 + user memory clean-cut
   原则。`FileProjectStore` 已经同时跑过 Desktop / CLI / Server / Android /
   iOS 五端（一个月+），没有"SQL 更好"的实测信号；为假设的未来需求保留死代码
   正是 CLAUDE.md "Don't design for hypothetical future requirements" 直接
   禁止的模式。
2. **只删代码不删表** = 保留表 schema 以便 legacy DB 依然"能打开"（被拒）：
   legacy DB 打开后也无法读 Projects 行了（SqlDelightProjectStore 已删），表
   的存在纯粹是 "PRAGMA user_version 不 bump" 的假象。`TaleviaDbFactory` 对
   `user_version < target` 要求跑 migrate —— 不 bump 就等于"schema 永远卡在
   v3"，再引入新迁移时会跳过这一步产生版本错乱。Bump 是必须的。
3. **把 `openAt` / `createAt` / `pathOf` 提到 `ProjectStore` 接口为 abstract**
   （初始版本选择，被 ExportToolMimeTest 的 ThrowingProjectStore 否决）：
   任何测试 fake 都得同时实现这三个方法才能编译，成本大于收益。保留默认
   实现（throw / null）让轻量 fake 只 override 它们真正关心的方法。

业界共识对照：
- **SqlDelight 2.x 迁移约定**：`N.sqm` 作为 "v<N> → v<N+1>" 的 migration 是
  官方文档路径（<https://sqldelight.github.io/sqldelight/2.0.2/jvm_sqlite/migrations/>）；
  `Schema.version` 自动等于 max(migration number) + 1 = 4 for the current
  state after this commit.
- **SQLite `DROP TABLE IF EXISTS`** 幂等，对 v4 fresh-install DB 无害（那些
  表根本不存在），对 v3 legacy DB 有效；`IF EXISTS` 避免 migration 本身抛错
  —— 本项目已有的 1.sqm / 2.sqm 用 `CREATE TABLE` 是 "add-only"，新表不存在；
  对称的 "drop-only" 用 `DROP TABLE IF EXISTS` 同样安全。
- **`PRAGMA user_version` one-way door**：CLAUDE.md 的 "Schema 版本使用
  PRAGMA user_version；降级拒绝" 是该约定的具体实现，`TaleviaDbFactory.openFile`
  L61–67 就是那条 `current > target` 拒绝分支。

**Coverage.**
- `:core:jvmTest` 全 green，含 `TaleviaDbFactoryTest.schema-version downgrade refuses to open`（验证 `user_version` 更新到新 target 后再下降会被拒），`file-backed db persists schema across reopen`（验证 `Schema.version == user_version` 往返）。
- `:apps:cli:test` 绿（CLI 启动路径仍然 `FileProjectStore(registry, projectsHome, bus)`）。
- `:apps:desktop:assemble`、`:apps:android:assembleDebug`、`:core:compileKotlinIosSimulatorArm64` 全 green —— 确认 `ProjectStore` 接口的 5 端都还能编译。
- `ktlintCheck` green。
- 未覆盖的一角：`MigrationTest` 风格的 SqlDelight migration 单测本项目还没有 build（`verifyMigrations` 未开启）。这条在 debt 档 append 了 `debt-add-sqldelight-migration-verification` bullet，下轮处理。

**Registration.** 无 —— 纯类 / 文件删除 + 迁移添加。5 个 `AppContainer`
已经全部使用 `FileProjectStore`，没有需要改的装配点。
