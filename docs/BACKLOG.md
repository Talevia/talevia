# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

## P1 — 中优，做完 P0 再排


## P2 — 记债 / 观望

- **bundle-source-footage-consolidate** — 当前 bundle 自包含 AIGC 产物 + `copy_into_bundle=true` 显式 import 的资产，但用户原始 footage 仍走 `MediaSource.File(absolutePath)`，跨机器不可移植。alice 的 `/Users/alice/raw.mp4` 在 bob 那里 ffmpeg "file not found"。**方向：** 加 `consolidate_media_into_bundle` 工具一次性把项目用到的全部 `MediaSource.File` 复制进 `<bundle>/media/` 并改写为 `BundleFile`；同时在 `import_media` 加智能默认（< 50MB 自动 in-bundle）。注意配套需要 git LFS 文档说明。Rubric §3.1 / §5.4。
- **bundle-asset-relink-ux** — 跨机器场景：bob 打开 alice 的 bundle，绝对路径资产解析失败时目前 export 直接报错。没有"原素材在哪？"的引导 UX。**方向：** `ProjectStore.openAt` / `get` 时收集所有 `MediaSource.File` 路径不存在的 assetId，emit `BusEvent.AssetMissing(assetId, originalPath)`；新增 `relink_asset(assetId, newPath)` 工具一次性把绑同一原素材的所有 clip 重指。CLI / Desktop 在 export 前显式列出 missing。Rubric §5.4。
- **bundle-mac-launch-services** — macOS 下双击 bundle 目录想打开 Talevia 的 UX：当前没有 `.talevia` 扩展名约定 + `Info.plist` `CFBundlePackageType` 注册，Finder 把 bundle 当普通目录展开。**方向：** 给 desktop app 的 `Info.plist` 加 `LSItemContentTypes` 声明 `io.talevia.project`（dir 包），约定 bundle 目录扩展名 `.talevia`，`createAt` / `openAt` 接受带或不带扩展名。Rubric §5.4 / packaging。
- **bundle-mobile-document-picker** — Android / iOS 当前限制于 app sandbox 内的 bundle (`<filesDir>/projects/` / `Documents/projects/`)。用户没法从 SAF / Files.app 选一个外部 bundle 打开。**方向：** Android 接 `Storage Access Framework` (`Intent.ACTION_OPEN_DOCUMENT_TREE`)，iOS 接 `UIDocumentPickerViewController`，结果 URI / NSURL 通过 platform-specific resolver 转成 Okio Path 喂给 `FileProjectStore.openAt`。Rubric §5.4 / mobile。
- **bundle-talevia-json-split** — 当前 `talevia.json` 把 timeline + assets + source DAG + lockfile + snapshots 全装一个文件，单个 mutation 的 git diff 涨几百行；snapshot 多了文件可能涨到 MB 级。**方向：** 当真出现 diff 噪声时拆 `assets.json` / `timeline.json` / `lockfile.json` / `snapshots/<id>.json` 子文件；envelope `talevia.json` 只留 schemaVersion + 元数据 + 子文件清单。先写 decision 评估触发条件再动。Rubric §3a-3。
- **bundle-cross-process-file-lock** — `FileProjectStore` 用 in-process `Mutex`，多进程同时打开同一 bundle 会丢更新。`SqlDelightProjectStore` 时代有同样 caveat 但 SQLite 自身有 `BEGIN IMMEDIATE` 兜底。**方向：** 写时拿 OS 文件锁（`FileChannel.tryLock` 在 JVM；`flock` 在 native），失败时 fail-loud 提示"another Talevia process holds this bundle"。Rubric §5.3 / 正确性。
- **recents-registry-list-summaries-index** — `RecentsRegistry.listSummaries()` 当前每次扫所有 entry + 解码每个 `talevia.json` envelope，N≥几百时变慢。**方向：** registry 自身缓存 `(title, updatedAtEpochMs)`，envelope 写时同步 registry；`listSummaries` 直接读 registry 不再扫 bundle。Rubric §5.3 / 性能（profiling 触发再做）。
- **debt-server-container-env-defaults** — `baad43f` 之后 `ServerContainer.kt:215` 用 `env["TALEVIA_RECENTS_PATH"]!!.toPath()`，但所有 server test 都传 `env = emptyMap()`；线上 `Main.kt` 走 `serverEnvWithDefaults()`，tests 不走，于是 `ServerSmokeTest` / `LifecycleTest` / `MetricsEndpointTest` / `InputValidationTest` 15 个测试 NPE 全红（`./gradlew :apps:server:test` 在 clean `main` 上已经红）。**方向：** 要么把 `serverEnvWithDefaults` 默认调用下沉到 `ServerContainer` 自己（`Main.kt` 只传 overrides），要么在测试 harness 里显式用 `serverEnvWithDefaults()`。Rubric §3a-9 / 正确性（顺手记于 debt-fold-set-source-node-body-helpers cycle，非本轮产物）。
- **debt-add-sqldelight-migration-verification** — 本项目的 SqlDelight 迁移（目前 `1.sqm` / `2.sqm` / `3.sqm`）没有对应的自动 verification 测试。`build.gradle.kts :sqldelight` 块没开 `verifyMigrations.set(true)`，也没有手工 `MigrationTest` 用 `JdbcSqliteDriver` 建 v1 DB → 跑 `Schema.migrate` → 断言每一步 target schema 与 `.sq` 文件一致。**方向：** 在 `:core:jvmTest` 加一个 `TaleviaDbMigrationTest`：针对每个已知 prior version，用 1.sqm 手写 v1 初始状态 → 循环跑 `Schema.migrate` 到 current → 断言 `PRAGMA user_version == Schema.version` 且 `Sessions` / `Messages` / `Parts` 表仍有。或更省事地开 gradle 插件的 `verifyMigrations.set(true)` 并维护 `schemas/` 目录。Rubric §3a-9（顺手记于 delete-sqldelight-project-store cycle）。
- **debt-server-tests-externality** — `debt-server-container-env-defaults` 已在 P2 ≥7 cycles，红的 `:apps:server:test` 每轮 server-adjacent 改动都要 stash 验证 "这是我的锅吗"，形成 cycle-level 外部性。**方向：** 下次 repopulate 把 `debt-server-container-env-defaults` 升到 P1（或 P0），让它跟普通优先级窗口竞争之前给它外部性补偿。Rubric 外 / 顺手记录。
- **debt-unify-project-diff-math** — `project_query(select=timeline_diff)` 和 `DiffProjectsTool.diffTimeline` 复制了同一份 ~40 行 diff 数学 + `changedClipFields` + `kindString` 辅助。**方向：** 抽到 `core/tool/builtin/project/diff/TimelineDiffCompute.kt` 里的 `internal fun computeTimelineDiff(from, to): DiffRow` + 共享行类型；`DiffProjectsTool` 保留 serialized Output shape 但内部 delegate。和 `debt-unify-project-diff-math` 一并落。Rubric 外 / 顺手记录。

