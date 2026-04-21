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

- **merge-define-update-tool-pairs** — `DefineCharacterRefTool` / `UpdateCharacterRefTool`、`DefineStyleBibleTool` / `UpdateStyleBibleTool`、`DefineBrandPaletteTool` / `UpdateBrandPaletteTool` 成对存在。对 LLM 是两个互斥分支（"存在就 update，不存在就 define"），徒增 spec 数量。**方向：** 合并为单一 `set_character_ref` / `set_style_bible` / `set_brand_palette`，上游用 upsert 语义；旧工具保留一次 release 的 deprecation 窗口或直接删（项目当前阶段可以直接删 —— 没有对外稳定面）。Rubric §5.2 一等抽象 > patch。

- **lockfile-byhash-index** — `Lockfile.findByInputHash` / `findByAssetId` 都是对 `entries` 的 O(n) `lastOrNull { … }`。注释承认 "~100s of entries OK, if we blow that we add a byHash index" —— 现在把这一步做了，成本极低：在 `Lockfile` 里加 `@Transient val byInputHash: Map<String, LockfileEntry>` / `byAssetId`，跟 `Source.byId` 一样在反序列化后自动重建。顺便给 `findByInputHash` 的 "lastOrNull" 语义写个 property 测试，防未来回归。Rubric §5.3。

- **session-project-binding** — 当前 `Session` 和 `Project` 解耦，agent 靠对话上下文记住当前在操作哪个 project id（tool 参数一个个手传）。多项目并行时（用户同时剪 vlog + 叙事片）体验会崩。**方向：** 在 `Session` 里加一等字段 `currentProjectId: ProjectId?`，`Agent.run` 把它注入到 system prompt 里作为 cwd-analogue；提供 `switch_project` tool 让 agent / 用户显式切换。ToolContext 暴露 `currentProjectId` 便于工具默认注入。Rubric §5.4。

## P2 — 记债/观望

- **consistency-genre-neutrality-debt** — `core/domain/source/consistency/` 把 `character_ref` / `style_bible` / `brand_palette` 假设成普适一等概念（`ConsistencyKinds.kt`、`PromptFolding.kt`、`VoiceFolding.kt`），对广告 / 教程 genre 不一定合适。VISION §2 承诺 "Core 不硬编码 genre schema" —— 这是已经出现的裂缝。**方向：** 这一轮不动代码，写一篇 `docs/decisions/<today>-consistency-genre-neutrality-debt-recorded.md`，显式记录：哪些 kind 当前在 Core 被一等化、下次第二 genre 真接入时的拆分方案（比如 consistency folding 下沉为 per-genre extension）、触发重构的硬信号（例："第二个 genre 的 folding 逻辑和 character_ref 不同构时"）。Rubric §5.1。

- **process-level-project-mutex** — `SqlDelightProjectStore` 用进程内 `Mutex` 保护 `mutate`。Desktop / CLI / 单副本 server 够用，多副本 server 或多进程共享 DB 会丢写。当前 server 是"可选 headless"，**暂不修**，但在本文件里记一笔，等 server 真要走生产路径时提前搜索此条。

- **server-auth-multiuser-isolation** — `apps/server` 从环境变量读 API key，`TALEVIA_MEDIA_DIR` 是单全局目录，catalog 全局共享。当前是"可选 headless"单用户，**暂不修**。升级到真多用户前在 server 模块里加显式"assumes single-tenant"的注释 + 在此条登记触发条件。

- **platform-priority-doc-sync** — `CLAUDE.md` 写 iOS/Android "维持不退化、不主动扩新特性"，但实际 Android 有完整 `Media3VideoEngine`、subtitle/filter/transition 三平台对齐、"every target" 命令里 Android APK 必编。文档和现实已经不一致。**方向：** 一次文档修订 —— 要么把优先级表更新为"Core > CLI > desktop ≈ iOS ≈ Android > server"反映真实投入，要么把这些已完成的三平台对齐显式标为"已达不退化底线"并冻结新特性。归档到 decisions。Rubric 外（纯流程 / 文档）。
