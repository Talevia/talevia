## 2026-04-23 — iterate-gap skill v2 rule distillation（skill-level meta-change, VISION §5.6）

**Context.** 这是一次 **skill-level meta-change**（不走 `/iterate-gap`，因为循环自身的规则被修改）。触发源：用户对 `docs/PAIN_POINTS.md` 的角色做了明确窄化 —— "主要作用是收集 feedback 以便定期评估当前硬性规定的合理性，做出适当调整，其他不触犯硬规则的痛点可以直接落入 backlog"。

对照 2026-04-23 上午整理的 PAIN_POINTS bucket-B（11 条"对规则的反馈"观察），其中**只有 1 条是真正在挑战 `CLAUDE.md` / `VISION.md` 级别的硬规定**（`bundle-cross-process-file-lock #2` —— iOS/Android 无 `flock` 等价 vs 平台优先级的 mobile 假设）。其余 10 条都是 `/iterate-gap` skill 自身可以优化的步骤 friction：stale bullet 36% 回收率、红 test suite 在 P2 挂 7+ cycle、`@Ignore` 幽灵门、ktlintFormat 顺序、axis 命名、bullet path 误导、runtime-untested 扫描等。

新 scope 下的分流规则：
- **硬规则 / VISION-level 反馈** → `docs/PAIN_POINTS.md`（评审规定合理性的输入）
- **skill tweak 建议** → `docs/BACKLOG.md` 的 `debt-skill-*`（下次 skill-level 改动的队列）
- **工程 gotcha / 正面范式** → `docs/ENGINEERING_NOTES.md`（跨项目可复用的知识库）

一次性把 10 条 skill tweak 折进 `.claude/skills/iterate-gap/SKILL.md`，同步裁剪 PAIN_POINTS 到只剩 cross-process #2 一条，让 PAIN_POINTS 真正变成"硬规则合理性评审"的定期触发器而不是 catch-all 日志。

**Decision.** 三个文件，一次 commit：

**`.claude/skills/iterate-gap/SKILL.md` 10 条规则变更**：

| 标记 | 变更 | 位置 | 解决的 PAIN_POINTS 观察 |
|---|---|---|---|
| (A) | 红 test suite 永远不进 P2；§R.5 加 #9 gradle test health；硬规则 §14 | §R.5 + Hard rules | registered-tools-contract-test #2、env-defaults #2/#3、migration-verification #2 |
| (B) | 新增 §2.5 Liveness pre-check（≤ 60 秒复现症状，不能复现 → skip-close）；§7 可选 `backlog-sweep:` footer | §2.5 + §7 | import-media #1、extract-frame #1、gemini #1 |
| (C) | §R.5 #5 `@Ignore` 跨 repopulate cycle 存活自动升 P0；§3a 新增 #11 bug-fix 自带验证路径 | §R.5 + §3a | fork-project-tool-trim-stats-bug #1 |
| (D) | §3 skip 流程加 skip-tag append（` · skipped <date>: <reason>`）；§2 挑 bullet 时用 skip-tag 过滤；§R 连续 skip ≥ 3 次转 re-evaluate 元 bullet | §2 + §3 + §R | cross-process-file-lock #1 |
| (E) | §5 验证：先 `ktlintFormat` 再 `ktlintCheck` | §5 | source-query #1、asset-relink #3、cross-process-file-lock #3 |
| (F) | §6 decision 模板加 **Axis.** 必填段 | §6 | debt-resplit-project-query-tool #2、fork-project-split #1 |
| (G) | §R bullet 格式明确"方向 = 不变量，不写死文件路径" | §R | bundle-cross-machine-export-smoke #2 |
| (H) | §R.5 新增 #10 runtime-untested critical path（`Schema.migrate` / agent loop / `ExportTool` / `FileProjectStore.openAt`） | §R.5 | migration-verification #2 |
| (I) | §3a 新增 #12 架构税阈值检查（命中就升档已有 follow-up bullet，不换 backlog） | §3a | bundle-source-footage #2、asset-relink #3、source-query #2、timeline-diff #2 |
| (J) | §6 新增"观察日志（分三类走向）"子步骤；§7 commit 组成扩为 6 项含可选 PAIN_POINTS / ENGINEERING_NOTES append；硬规则 §5 扩写 append-only 约定 + 允许的两种 in-place 编辑（skip-tag、debt-append） | §6 + §7 + Hard rules | extract-frame #2（+ 固化 PAIN_POINTS 新 scope） |

并行模式 §P2 同步更新：sub-agent 的范围从"复制顺序模式 §3-7"改为"§2.5-7"（含 liveness pre-check）。硬规则 #12 扩写明确 §3a #12 是升档触发而非换 backlog 语义。

**`docs/PAIN_POINTS.md` 裁剪**：

从 20+ 条 observation 减到 **1 条**。保留 `bundle-cross-process-file-lock #2`（iOS/Android 无 `flock` 等价，挑战 `CLAUDE.md` 平台优先级的 mobile 单进程假设），其余 20 条映射到对应的 skill v2 变更或直接删除：

| 观察 | 对应的 skill v2 变更或其他去处 | 动作 |
|---|---|---|
| debt-resplit-project-query-tool #1 / #2 | (F) Axis 命名 | 删 |
| fork-project-trim #1 | (C) `@Ignore` 升级 | 删 |
| bundle-cross-machine-export-smoke #2 | (G) Bullet = invariant | 删 |
| import-media #1 | (B) Liveness + backlog-sweep | 删 |
| extract-frame #1 / #2 | (B) + (J) | 删 |
| debt-split-fork-project-tool #1 | (F) | 删 |
| debt-registered-tools-contract-test #2 | (A) + Hard rule §14 | 删 |
| source-query #1 / #2 | (E) + (I) | 删 |
| timeline-diff #2 | (I) | 删 |
| gemini-provider-stub #1 | (B) | 删 |
| bundle-source-footage #1 / #2 | BACKLOG `debt-streaming-bundle-blob-writer` / (I) | 删 |
| bundle-asset-relink-ux #3 | (I) | 删 |
| bundle-cross-process-file-lock #1 / #3 | (D) / (E) | 删 |
| **bundle-cross-process-file-lock #2** | **VISION-level，未处理** | **保留** |
| debt-server-container-env-defaults #1 / #2 | (A) | 删 |
| debt-add-sqldelight-migration-verification #1 | (A) + (H) | 删 |

PAIN_POINTS header 重写为 "Scope filter" + "格式" + "History" 三段，明确"硬规则 / VISION-level 反馈" 的分流判别。

**Axis.** `n/a` —— 这是 skill-level meta-change，不是代码 split / extract。

**Alternatives considered.**

- *走 `/iterate-gap` 循环，一条变更一个 cycle*。Rejected：循环本身的规则被修改，每一步 cycle 都要重新对照新规则验证；10 个 cycle 串行动的 churn 远高于一次集中 distil。再者 skill v2 §2.5 / §3a 新条目之间有语义依赖（skip-close 和 skip-tag 互为 complement；§R.5 的强制 P0 档依赖 §14 硬规则），拆开改会出现中间状态不自洽。
- *保留 PAIN_POINTS 现有条目作为历史证据，不裁剪*。Rejected：user 明确说"改完 skill 后直接把那些相关 painpoints 删掉"。历史证据 + 证据与规则变更的映射由本 decision 文件 + `git log -p docs/PAIN_POINTS.md` 保留，不需要文件本身承担归档功能。
- *只改 skill 不裁剪 PAIN_POINTS*。Rejected：不裁剪的话 PAIN_POINTS 下次被读取时还会夹带 20+ 条混合 scope 的低信噪观察，新 scope 的判别力会被稀释。规则变更和观察清理同 commit 才能让 git blame 对齐"这条观察导致了这条规则"。
- *先做 skill v2，PAIN_POINTS 裁剪等下一轮*。Rejected：留一个中间状态（新 skill + 旧 PAIN_POINTS）是典型的 "stale bullet" 反模式，skill v2 §2.5 刚明文反对。一次性完成分流才诚实。

**Coverage.** Skill 文件没有可自动化的测试。验证通过**干跑**：

1. 读新 skill 一遍，确认 10 条变更之间不矛盾（§2.5 skip-close 和 §6 归档决策的路径、§3 skip-tag 和 §R 连续 skip 处理、硬规则 §5 和 §12 / §14 的分层）。
2. 对 BACKLOG 当前 top-1 (`debt-query-dispatcher-abstraction`) 心跑一遍：§1 → §2 → §2.5 → §3 → §3a (12 条) → §4 → §5 format-then-check → §6 (含 Axis + 观察分流) → §7 (含可选 footer)，确认没有步骤会在新规则下卡死。
3. 三个历史场景重跑验证：
   - `fork-project-trim-stats-bug` → 新 §2.5 应当 skip-close（bug 已被 prior refactor 解决）。
   - `bundle-cross-machine-export-smoke` → 新 §R bullet phrasing 应该让 bullet 以 invariant 表述，不再误导到 CLI 层。
   - `debt-server-container-env-defaults` → 新 §R.5 "gradle test health" 扫描应该在第一次 `apps/server:test` 变红时就强制 P0，而不是等 7 cycle。

**预期复发率指标**（下次 PAIN_POINTS / BACKLOG 检查对照）：
- Stale bullet rate：当前 4/11 ≈ 36% → 目标下 10 cycle 内 ≤ 10%（§2.5 应在 dispatch 前就挡住）。
- 红 test suite 在 main 上存活：当前 7+ cycle → 目标 ≤ 1 cycle（硬规则 §14 + §R.5 #9）。
- `@Ignore` 存活 > 1 repopulate 周期：当前 `variantSpecDropsTailClipsAndTruncatesStraddlers` 等多处 → 目标 0（§R.5 #5 自动升 P0）。
- 5-container tax 复述：当前 PAIN_POINTS 每 2 cycle 复述一次 → 目标转为 BACKLOG trigger 触发，不在 PAIN_POINTS 复述。
- skill-tweak vs 硬规则反馈 误分类率：当前 PAIN_POINTS 混进了 ~90% skill tweak → 目标新 scope 下 PAIN_POINTS 条目 100% 挑战 CLAUDE.md / VISION.md 规定（判别准则在 §6 观察日志 + PAIN_POINTS header 两处描述一致）。

**Registration.** 无需注册 —— 本轮动的是 skill 文件 + 文档 + 本 decision 文件，没有 `Tool<I, O>` / `AppContainer` / `core.platform` 接触。

**Follow-up（不在本轮 scope）：** auto-memory `feedback_iterate_gap_pain_points_log.md`（2026-04-22 用户明确要求写入）的内容在新 scope 下已与 skill v2 §6 "三岔分流"矛盾（它说"任何实现过程观察都 append 到 PAIN_POINTS"）。下次用户显式允许动 memory 时，要么把这条 memory 更新为指向 skill v2 §6 / PAIN_POINTS 新 scope，要么删除让 skill 成为唯一真相源。本轮不动 memory（用户未明示授权）。
