# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **unify-project-query** — `core/tool/builtin/` 里已经有 127 个 builtin tool，其中 20+ 个是 `list_*` / `find_*` 专用变体（`list_tracks` / `list_transitions` / `list_timeline_clips` / `list_assets` / `list_clips_bound_to_asset` / `list_clips_for_source` / `find_unreferenced_assets` / `find_pinned_clips` / `find_stale_clips` …）。每个 LLM turn 都会把全部 spec 塞进 context，最近几个 commit 都在给这些工具加 `onlyX + sortBy + limit` 参数 —— 增长模式是"每个维度一个工具"而不是"少量 query 原语 + filter"。**方向：** 引入类 codebase grep 的 `project_query(select, filter, sort, limit)` 原语，吸收绝大多数纯只读的 list/find 工具；保留真正有副作用或聚合语义的（比如 `find_stale_clips` 背后是 DAG 推导而非纯 query）。Rubric §5.2 / §5.4。

- **split-project-json-blob** — `SqlDelightProjectStore.mutate()` 每次都把整个 `Project`（`timeline` + `source` DAG + `lockfile.entries` + `renderCache` + `snapshots`）序列化写回一列。`add_clip` 一次调用 → 重新 encode 全部历史。`snapshots` 和 `lockfile.entries` 是 append-only 语义，跟 timeline 的热路径绑在一起没道理。**方向：** 把 `snapshots` 和 `lockfile.entries` 拆到独立 SQLDelight 表（按 projectId 外键），Project blob 只保留 `id / timeline / assets / source / outputProfile / renderCache` 等"每次都会整读整写"的字段。`ProjectStore.mutate` 签名保持不变，内部读写分发到两张表。Rubric §5.3 / §3.4「可版本化 / 可 diff」。

- **unbound-clip-stale-semantics** — `ProjectStaleness.staleClips()` 里 `sourceBinding.isEmpty() → 恒 stale` —— 手工 clip 被标成 "永远 stale"，但又无法 `regenerate_stale_clips`（它们没有 `baseInputs` 可以重放），stale 信号被污染。小白路径尤其不友好。**方向：** 要么语义翻转为 "unbound → 恒 fresh（用户明示放弃增量）"，要么引入三态 `Fresh / Stale / Unknown`，让 UI 和 `regenerate_stale_clips` 区分"真 stale"和"无法判断"。写 decision 时明确选择背后的权衡。Rubric §5.3 / §5.5。

## P1 — 中优，做完 P0 再排

- **merge-define-update-tool-pairs** — `DefineCharacterRefTool` / `UpdateCharacterRefTool`、`DefineStyleBibleTool` / `UpdateStyleBibleTool`、`DefineBrandPaletteTool` / `UpdateBrandPaletteTool` 成对存在。对 LLM 是两个互斥分支（"存在就 update，不存在就 define"），徒增 spec 数量。**方向：** 合并为单一 `set_character_ref` / `set_style_bible` / `set_brand_palette`，上游用 upsert 语义；旧工具保留一次 release 的 deprecation 窗口或直接删（项目当前阶段可以直接删 —— 没有对外稳定面）。Rubric §5.2 一等抽象 > patch。

- **lockfile-byhash-index** — `Lockfile.findByInputHash` / `findByAssetId` 都是对 `entries` 的 O(n) `lastOrNull { … }`。注释承认 "~100s of entries OK, if we blow that we add a byHash index" —— 现在把这一步做了，成本极低：在 `Lockfile` 里加 `@Transient val byInputHash: Map<String, LockfileEntry>` / `byAssetId`，跟 `Source.byId` 一样在反序列化后自动重建。顺便给 `findByInputHash` 的 "lastOrNull" 语义写个 property 测试，防未来回归。Rubric §5.3。

- **session-project-binding** — 当前 `Session` 和 `Project` 解耦，agent 靠对话上下文记住当前在操作哪个 project id（tool 参数一个个手传）。多项目并行时（用户同时剪 vlog + 叙事片）体验会崩。**方向：** 在 `Session` 里加一等字段 `currentProjectId: ProjectId?`，`Agent.run` 把它注入到 system prompt 里作为 cwd-analogue；提供 `switch_project` tool 让 agent / 用户显式切换。ToolContext 暴露 `currentProjectId` 便于工具默认注入。Rubric §5.4。

## P2 — 记债/观望

- **consistency-genre-neutrality-debt** — `core/domain/source/consistency/` 把 `character_ref` / `style_bible` / `brand_palette` 假设成普适一等概念（`ConsistencyKinds.kt`、`PromptFolding.kt`、`VoiceFolding.kt`），对广告 / 教程 genre 不一定合适。VISION §2 承诺 "Core 不硬编码 genre schema" —— 这是已经出现的裂缝。**方向：** 这一轮不动代码，写一篇 `docs/decisions/<today>-consistency-genre-neutrality-debt-recorded.md`，显式记录：哪些 kind 当前在 Core 被一等化、下次第二 genre 真接入时的拆分方案（比如 consistency folding 下沉为 per-genre extension）、触发重构的硬信号（例："第二个 genre 的 folding 逻辑和 character_ref 不同构时"）。Rubric §5.1。

- **process-level-project-mutex** — `SqlDelightProjectStore` 用进程内 `Mutex` 保护 `mutate`。Desktop / CLI / 单副本 server 够用，多副本 server 或多进程共享 DB 会丢写。当前 server 是"可选 headless"，**暂不修**，但在本文件里记一笔，等 server 真要走生产路径时提前搜索此条。

- **server-auth-multiuser-isolation** — `apps/server` 从环境变量读 API key，`TALEVIA_MEDIA_DIR` 是单全局目录，catalog 全局共享。当前是"可选 headless"单用户，**暂不修**。升级到真多用户前在 server 模块里加显式"assumes single-tenant"的注释 + 在此条登记触发条件。

- **platform-priority-doc-sync** — `CLAUDE.md` 写 iOS/Android "维持不退化、不主动扩新特性"，但实际 Android 有完整 `Media3VideoEngine`、subtitle/filter/transition 三平台对齐、"every target" 命令里 Android APK 必编。文档和现实已经不一致。**方向：** 一次文档修订 —— 要么把优先级表更新为"Core > CLI > desktop ≈ iOS ≈ Android > server"反映真实投入，要么把这些已完成的三平台对齐显式标为"已达不退化底线"并冻结新特性。归档到 decisions。Rubric 外（纯流程 / 文档）。
