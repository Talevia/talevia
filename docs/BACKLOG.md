# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **delete-file-media-storage-interface** — `MediaStorage` / `FileMediaStorage` / `InMemoryMediaStorage` / `FileBlobWriter` 已被 `Project.assets` + `BundleMediaPathResolver` + `BundleBlobWriter` 替代，但仍被 `ExtractFrameTool` + `ApplyLutTool` + `ImportMediaTool` proxy 路径 + Android/iOS proxy generators 引用，导致容器仍要构造 `media: MediaStorage = InMemoryMediaStorage()` 占位。**方向：** 把这几个剩余消费方的 `media.get(assetId)` 切到 `project.assets.find { it.id == assetId }`；写路径切到 `FileBundleBlobWriter`；删 `MediaStorage` 接口 + 3 个实现 + 容器 placeholder。Rubric §3a-3。

## P1 — 中优，做完 P0 再排

- **debt-resplit-project-query-tool** — `ProjectQueryTool.kt` 在 `6e7bd8f` 首次拆分至 540 行后又长回 547 行，说明上次拆分不彻底 / 漏了一个肥 select 分支。**方向：** 扫一遍各 `handle<Select>` 分支，把任何 > 40 行的单独挪到 `project/query/<select>.kt` 同级；主文件保留 dispatch + schema。Rubric §3a-3。
- **debt-resplit-session-query-tool** — `SessionQueryTool.kt` 534 行，同类症状；同一轮 repopulate 一并 split。**方向：** 和 ProjectQueryTool 同一个套路。Rubric §3a-3。
- **partial-tool-input-streaming** — provider 流式发来 tool JSON delta，core 现在闭门拼到 complete 再 dispatch；UI 只看到「工具在想…」几秒钟没有 feedback。**方向：** `LlmEvent` 新增 `ToolInputDelta(toolCallId, jsonFragment)`；provider 层直通；Part 保留 accumulated JSON 供 dispatch。不改 ToolResult 协议。Rubric §5.4。
- **tool-output-token-estimate** — `ToolResult` 没带 token 估算，`Compactor` 选哪一条来压缩只能按 part 顺序。**方向：** 在 `ToolResult` 加 `estimatedTokens: Int?`（/4 字节粗估），compaction 按大小降序取 top-N 压掉。Rubric §5.4。
- **auto-revert-on-failed-export** — `ExportTool` 中途 ffmpeg 崩溃 / 被 cancel 时 mezzanine 文件在磁盘留存，没人清。`deleteMezzanine` 存在但只在 gc 路径调用。**方向：** 在 `ExportTool.execute` try-finally 里对失败路径调 `engine.deleteMezzanine(tmpPath)`；正常成功路径不动。Rubric §5.2。
- **aigc-cache-hit-ratio-metric** — `AigcPipeline.findCached` 命中 / 未命中对外不可见；用户 / agent 都无法证明「seed 锁住、cache 真的 hit 了」。**方向：** 每次 findCached 命中 / 未命中时 publish `BusEvent.AigcCacheProbe(toolId, hit)` 并让 `CounterRegistry` 落到 `/metrics` 的 `talevia_aigc_cache_hits_total` / `talevia_aigc_cache_misses_total`。Rubric §5.3 / Observability。
- **session-tool-disabled-registry** — agent 目前没法在一个 session 内部把某个工具暂时禁掉（比如「别再用 generate_video 了，太贵」）。**方向：** `SessionStore` 加 `disabledToolIds: Set<String>`，`AgentTurnExecutor` 过滤；新增 `disable_tool` / `enable_tool` 两个 session 域工具或把它接到 `session_query` 的 mutation path。Rubric §5.4。
- **cli-resume-last-session** — `apps/cli` 退出再启动就是全新 session，上下文丢光。`TaleviaDbFactory` 已经把 session 存 DB，但 CLI Repl 启动时不会恢复。**方向：** 给 CLI 加 `--resume` / `--resume=<id>` 旗；无参时从 `SqlDelightSessionStore` 取最近一次活跃 session。Rubric §5.4 / CLI。
- **fork-project-tool-trim-stats-bug** — `ForkProjectTool` 的 `Output.clipsDroppedByTrim` / `clipsTruncatedByTrim` 通过对 **持久化后已经 trim 过的 project** 再跑一次 `applyVariantSpec` 来统计，永远拿到 `(0, 0)`。`SqlDelightProjectStore` 时代意外 pass，`baad43f` 切到 `FileProjectStore` 的精确 round-trip 后暴露 —— `ForkProjectToolTest.variantSpecDurationDropsTailClipsAndTruncatesStraddlers` 当前 `@Ignore` 着。**方向：** 改用第一次 `applyVariantSpec(baseFork, spec)` 调用的 `reshape` 数字塞进 `Output`，把第二次 `apply` 删掉；解开 `@Ignore`。Rubric §3a-3 / 正确性。
- **bundle-cross-machine-export-smoke** — `baad43f` 的核心目标"git push 出去的 bundle 在另一台机器能 reproduce export"还没 e2e 验证过。CI 里也没 cross-machine 同等物。**方向：** 在 `apps/cli` 或 `apps/server` 测试里加一个 smoke test：`talevia new /tmp/a` → 加 clip + AIGC（fake provider）+ ApplyLut → export → 记 hash → `cp -r /tmp/a /tmp/b` → `talevia open /tmp/b` → 重新 export → assert hash 相同。Rubric §3.1（产物可 pin） / §5.3。
- **import-media-tool-bundle-resolver** — `ImportMediaTool.execute` 在 `copy_into_bundle=false` 路径仍走 `MediaStorage.import`，没用 `BundleMediaPathResolver`；对 character_ref 等 bundle-local 资产做 reference-by-path 时会绕过 bundle resolver 的 path safety 校验。**方向：** 把所有 register 路径统一到 `projects.mutate { it.copy(assets = it.assets + asset) }`，删掉 `storage.import` 调用；resolver 路径单独一段（非 bundle 资产仍走绝对路径，但通过 `BundleMediaPathResolver` 出口拒掉非法路径）。Rubric §3a-3。
- **extract-frame-tool-bundle-write** — `ExtractFrameTool` 仍构造 `MediaStorage` + `MediaBlobWriter`（旧 API），写帧到全局 `~/.talevia/media/generated/`，绕开了"产物随 bundle"的承诺。**方向：** 切到 `BundleBlobWriter` + `Project.assets` append（与 AIGC 工具对齐）；测试 fixture 复用 `ProjectStoreTestKit.createWithFs()` + `FileBundleBlobWriter`。Rubric §3.1。

## P2 — 记债 / 观望

- **debt-split-fork-project-tool** — `ForkProjectTool.kt` 497 行，逼近 500 行阈值，`ForkProject` + 子 forks 继续加功能会越过。**方向：** 先把 duplicate-asset / duplicate-sessions / rename path 的子逻辑抽到 `project/fork/*.kt` 同级文件，预防性拆分。Rubric §3a-3。
- **debt-registered-tools-contract-test** — 没有 CI 检查能发现「新加了一个 Tool.kt 却忘了在 CliContainer/DesktopContainer/… 注册」。曾经漏过一次（`cb551be` fix）。**方向：** 新增 `RegisteredToolsContractTest`：扫 `core/tool/builtin/**/*Tool.kt`，assert 每个类名都在至少一个 `AppContainer` 的注册 list 里出现。Rubric §3a-8。
- **source-query-by-parent-id** — `SourceQueryTool` 能按 id / kind / search 查，但无法「列出直接 / 传递子节点」。对 propagate / fold 相关 reasoning 价值大。**方向：** 新增 `select=descendants` / `select=ancestors`，参数 `root: SourceId, depth: Int = -1`。复用 `deepContentHashOf` 的 DAG 遍历逻辑。Rubric §5.5。
- **timeline-diff-tool** — `DiffProjectsTool` 给全项目级别 diff，但没法单独看两个 snapshot 之间 Timeline 的变动。**方向：** `project_query(select=timeline_diff, fromSnapshot=..., toSnapshot=...)` 返回 clips/tracks 增删改列表。Rubric §5.1 / §5.4。
- **gemini-provider-stub** — 只有 Anthropic / OpenAI；provider abstraction 还没被第三个实现压过测试。**方向：** 给 `core/provider/gemini/` 打一个 skeleton，映射 Gemini streaming 事件到 `LlmEvent`，装配点 gated by `GOOGLE_GENAI_API_KEY`。可以只覆盖 text 一轮对话；tool-use 留给后续。Rubric §5.2 + provider 中立红线。
- **tts-provider-fallback-chain** — `SynthesizeSpeechTool` 目前一个 provider 下去，provider 挂就 tool error。**方向：** 在 Core 容器里接受多个 engine 按优先级注入，`AigcPipeline` 遇到非 cache-hit 失败时切到下一个引擎；lockfile 记录最终用的 provider。Rubric §5.2。
- **bundle-source-footage-consolidate** — 当前 bundle 自包含 AIGC 产物 + `copy_into_bundle=true` 显式 import 的资产，但用户原始 footage 仍走 `MediaSource.File(absolutePath)`，跨机器不可移植。alice 的 `/Users/alice/raw.mp4` 在 bob 那里 ffmpeg "file not found"。**方向：** 加 `consolidate_media_into_bundle` 工具一次性把项目用到的全部 `MediaSource.File` 复制进 `<bundle>/media/` 并改写为 `BundleFile`；同时在 `import_media` 加智能默认（< 50MB 自动 in-bundle）。注意配套需要 git LFS 文档说明。Rubric §3.1 / §5.4。
- **bundle-asset-relink-ux** — 跨机器场景：bob 打开 alice 的 bundle，绝对路径资产解析失败时目前 export 直接报错。没有"原素材在哪？"的引导 UX。**方向：** `ProjectStore.openAt` / `get` 时收集所有 `MediaSource.File` 路径不存在的 assetId，emit `BusEvent.AssetMissing(assetId, originalPath)`；新增 `relink_asset(assetId, newPath)` 工具一次性把绑同一原素材的所有 clip 重指。CLI / Desktop 在 export 前显式列出 missing。Rubric §5.4。
- **bundle-mac-launch-services** — macOS 下双击 bundle 目录想打开 Talevia 的 UX：当前没有 `.talevia` 扩展名约定 + `Info.plist` `CFBundlePackageType` 注册，Finder 把 bundle 当普通目录展开。**方向：** 给 desktop app 的 `Info.plist` 加 `LSItemContentTypes` 声明 `io.talevia.project`（dir 包），约定 bundle 目录扩展名 `.talevia`，`createAt` / `openAt` 接受带或不带扩展名。Rubric §5.4 / packaging。
- **bundle-mobile-document-picker** — Android / iOS 当前限制于 app sandbox 内的 bundle (`<filesDir>/projects/` / `Documents/projects/`)。用户没法从 SAF / Files.app 选一个外部 bundle 打开。**方向：** Android 接 `Storage Access Framework` (`Intent.ACTION_OPEN_DOCUMENT_TREE`)，iOS 接 `UIDocumentPickerViewController`，结果 URI / NSURL 通过 platform-specific resolver 转成 Okio Path 喂给 `FileProjectStore.openAt`。Rubric §5.4 / mobile。
- **bundle-talevia-json-split** — 当前 `talevia.json` 把 timeline + assets + source DAG + lockfile + snapshots 全装一个文件，单个 mutation 的 git diff 涨几百行；snapshot 多了文件可能涨到 MB 级。**方向：** 当真出现 diff 噪声时拆 `assets.json` / `timeline.json` / `lockfile.json` / `snapshots/<id>.json` 子文件；envelope `talevia.json` 只留 schemaVersion + 元数据 + 子文件清单。先写 decision 评估触发条件再动。Rubric §3a-3。
- **bundle-cross-process-file-lock** — `FileProjectStore` 用 in-process `Mutex`，多进程同时打开同一 bundle 会丢更新。`SqlDelightProjectStore` 时代有同样 caveat 但 SQLite 自身有 `BEGIN IMMEDIATE` 兜底。**方向：** 写时拿 OS 文件锁（`FileChannel.tryLock` 在 JVM；`flock` 在 native），失败时 fail-loud 提示"another Talevia process holds this bundle"。Rubric §5.3 / 正确性。
- **recents-registry-list-summaries-index** — `RecentsRegistry.listSummaries()` 当前每次扫所有 entry + 解码每个 `talevia.json` envelope，N≥几百时变慢。**方向：** registry 自身缓存 `(title, updatedAtEpochMs)`，envelope 写时同步 registry；`listSummaries` 直接读 registry 不再扫 bundle。Rubric §5.3 / 性能（profiling 触发再做）。
- **debt-server-container-env-defaults** — `baad43f` 之后 `ServerContainer.kt:215` 用 `env["TALEVIA_RECENTS_PATH"]!!.toPath()`，但所有 server test 都传 `env = emptyMap()`；线上 `Main.kt` 走 `serverEnvWithDefaults()`，tests 不走，于是 `ServerSmokeTest` / `LifecycleTest` / `MetricsEndpointTest` / `InputValidationTest` 15 个测试 NPE 全红（`./gradlew :apps:server:test` 在 clean `main` 上已经红）。**方向：** 要么把 `serverEnvWithDefaults` 默认调用下沉到 `ServerContainer` 自己（`Main.kt` 只传 overrides），要么在测试 harness 里显式用 `serverEnvWithDefaults()`。Rubric §3a-9 / 正确性（顺手记于 debt-fold-set-source-node-body-helpers cycle，非本轮产物）。
- **debt-add-sqldelight-migration-verification** — 本项目的 SqlDelight 迁移（目前 `1.sqm` / `2.sqm` / `3.sqm`）没有对应的自动 verification 测试。`build.gradle.kts :sqldelight` 块没开 `verifyMigrations.set(true)`，也没有手工 `MigrationTest` 用 `JdbcSqliteDriver` 建 v1 DB → 跑 `Schema.migrate` → 断言每一步 target schema 与 `.sq` 文件一致。**方向：** 在 `:core:jvmTest` 加一个 `TaleviaDbMigrationTest`：针对每个已知 prior version，用 1.sqm 手写 v1 初始状态 → 循环跑 `Schema.migrate` 到 current → 断言 `PRAGMA user_version == Schema.version` 且 `Sessions` / `Messages` / `Parts` 表仍有。或更省事地开 gradle 插件的 `verifyMigrations.set(true)` 并维护 `schemas/` 目录。Rubric §3a-9（顺手记于 delete-sqldelight-project-store cycle）。

