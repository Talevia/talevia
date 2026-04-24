## 2026-04-23 — iterate-gap 引入 milestone 机制，M1 = 跨镜头一致性 e2e 闭环（VISION §3.3）

**Context.** 本仓库 iterate-gap skill 至今只有两层优先级：`docs/BACKLOG.md`
的 `P0 / P1 / P2` + 每条 bullet 末尾的 `Rubric §5.x` 轴标签。cycles 54–58
的观察：当 P1 全被 skip-tag 填满时，agent 从 P2 里挑"任何非 trigger-gated
的 bullet"，注意力在 §5.1 source / §5.4 agent / §5.6 debt / §5.7 perf 之间
来回切换。5 个 cycle 覆盖了 5 个不同 rubric 轴（bus helper / active run
summary / compaction threshold / fallback tracker / retry tracker），每一
步个体合理，但**没有一条连续的叙事**在推进 VISION §3 的某一个核心赌注。
结果：agent 基础设施稳步收敛，北极星赌注进度不可见。

参照 `/Volumes/Code/media-engine` 的做法：在 `iterate-gap` 同一生态里加
`docs/MILESTONES.md` + 自动 tick + 自动 promotion，把 N 个 cycle 压在同一
个粗粒度赌注上，通过 evidence-based 的 checkbox 收敛。我们的项目面比
media-engine 宽（KMP × 多平台 × agent × AIGC），直接搬原版会把合法的
debt / correctness fix 被 milestone gate 降档，所以做三处**本地化**：
软优先级、grep-able 才自动 tick、不新造分类体系（复用 §3 赌注锚点）。

§5.6 delta n/a——机制引入，不改代码；但显著改善**迭代的可观测性**，让
"系统在朝北极星收敛"的证据从零散 decision 文件变成可聚合的 milestone 曲线。

**Decision.**

1. **新文件 `docs/MILESTONES.md`**：单文件、顶部 `> Current: M<N> —` 指针、
   每个 M 是一个独立 block（goal + exit criteria 复选框 + 亚军候选）。
   Milestone 不装任务；任务仍在 BACKLOG，每条 bullet 用 `Milestone §M<N>`
   或 `Milestone §later` tag 分类（下次 repopulate 自动加，不手动 backfill）。

2. **编辑 `.claude/skills/iterate-gap/SKILL.md`**：
   - §2 "挑 bullet"：加一段 "milestone 软优先级" 说明（同档内 tag 匹配当前
     M 的靠前；不跨档升降）。
   - §R repopulate 格式：bullet 尾部加 `Milestone §M<N> | §later` tag 字段。
     `MILESTONES.md` 缺失则一律 `§later`。
   - **新 §M 步骤**（§7 push 成功后、§8 报告前）：
     * grep-able criterion 自动 tick（evidence = 产品路径命中 + test 覆盖；
       可选 `git log -S` 佐证落地时间窗）。
     * 纯主观 criterion（标 "必须手动 tick"）只能由对应决策文件触发。
     * 全勾 → auto-promote，移动顶部 `Current:` 指针、升格亚军 candidate、
       新建 `milestone-advance-m<N+1>.md` 决策，**和触发 commit 同 commit**
       （保 "commit → push 永远配对" 硬规则）。
     * §7 push 失败本节跳过；push 成功但没勾到 → 本节无输出。
   - §7 commit-bundle：新增第 ⑦ 项 "§M 的 MILESTONES.md tick / promote 产物
     必须和 ①–⑥ 同 commit"。
   - §8 报告：新增一行报告 "当前 milestone + 是否 tick / 是否 promote"。

3. **首个 milestone = M1 跨镜头一致性 e2e 闭环 (§3.3)**，6 条 exit criteria：
   - 物理注入（已 ✓：`AigcPipeline.foldPrompt` 在 `GenerateImage/Video/Music`
     全部落地，cycles ≤ 53 完成）
   - 绑定持久化到 `Clip`（不再靠 tool input 参数）
   - 绑定反查 select（`source_query` / `project_query` 新 select）
   - `ConsistencyKinds.ALL` ≥ 4（证明可扩）
   - e2e 语义回归测试（改字段 → stale → re-export prompt 含新值）
   - 退出决策文件（手动 tick，列剩余 §3.3 gap）

**Axis.** n/a（机制引入非 refactor）。Milestone 自身的增长轴是"单个 M
的 criteria 数"；超过 8 条是拆 M 的信号。Milestone 总数的增长轴是"未完结
M 的数量"；永远只有 1 个 `Current`，其余只在"亚军候选"列表里待命。

**Alternatives considered.**

- **Media-engine 原版 —— milestone 是硬优先级 gate，非当前 M 一律降 P2。**
  否。我们面宽（KMP + agent infra + AIGC provider 管理），硬 gate 会让
  合法的 debt / correctness fix 被降级，"为走 milestone 打包不干净" 会
  成反模式。软优先级保留"该做的小事不被 milestone 挤掉"。代价是 milestone
  聚焦度略低，但可接受——evidence 驱动的 tick + promote 仍足以把多数
  cycle 拉向当前 M。

- **加第三层文档 `ROADMAP.md` + `MILESTONES.md` + `VISION.md`。** 否。
  我们已有 VISION §3（四赌注）+ §5（七轴）+ `docs/decisions/`（350 条历史）。
  再加一层维护成本高、信号稀。Milestone 复用 §3 赌注作为锚点（每个 M
  对应一个赌注），不新造分类体系。

- **首个 milestone 选 §3.1 AIGC 驯服。** 否。§3.1 的 criteria 多数依赖
  真实 provider 产品化（pin 命中率、多 provider 稳定性），需要外部环境配合；
  §3.3 criteria 更多是内部可控 grep-able 实体（`ConsistencyKinds.ALL` 数量、
  绑定反查 select、prompt 注入 test），更适合做首个 milestone 验证机制。
  §3.1 进 M2 亚军候选。

- **M1 的首条 criterion（物理注入）事后补勾，而不是开箱就 `[x]`。** 否。
  该能力确实已落地（`AigcPipeline.foldPrompt` 在 3 个 AIGC tool 里跑了
  cycles），事实就是事实；pre-tick 反而示范机制能跑通，让首个 auto-tick
  cycle 不用等新能力落地才能触发。同时给其他未勾 criterion 一个可达的
  "已实现长啥样" 参照。

- **自动 tick 门槛也把纯主观 criterion 推给 agent 自动判断。** 否。
  agent 不应该做"这个算不算小白可用"这类主观判断；evidence 硬时自动勾，
  fuzzy 时必须有决策文件可追溯——后者本身就是 evidence。纯主观 auto-tick
  会让 milestone 变成"agent 说勾就勾"的自证体系，失去约束意义。

**Coverage.** 机制本身不需要测试——它是流程文档。落地验证在后续 cycle 里
观察：

- 下次 cycle 的 repopulate 自动给新 bullet 打 milestone tag（§R 已在
  skill 里加）。
- 某个 cycle 落地 M1 某条 grep-able criterion，下次 cycle 的 §M 步骤自动
  勾（sample: 加 4th `ConsistencyKind` → `ALL` 大小 ≥ 4 → auto-tick）。
- 全勾 → 同 cycle 内 auto-promote M2 + 新决策文件；通过 `git log` 可
  追溯"milestone 前进"曲线。

**Registration.** 无代码改动。本 commit 改动面：
1. 新建 `docs/MILESTONES.md`（M1 block + 机制说明顶栏 + 亚军候选列表）。
2. 新建本决策文件。
3. 编辑 `.claude/skills/iterate-gap/SKILL.md`：§2 soft-priority 段、
   §R bullet 格式 + tag 规则、新 §M 步骤、§7 commit-bundle 第 ⑦ 项、§8
   报告新增 milestone 行。

**§3a 架构税自查**（skill 自我引用）：机制引入 ≠ 工具增加；`*Tool.kt`
数量、select 数、AppContainer 数、ToolSpecBudget 全不动。唯一维护成本
是 `MILESTONES.md`（~60 行）和 skill 新增 ~45 行。可接受。后续增长风险：
milestone 描述膨胀（应限定 block ≤ 30 行）、亚军候选列表失控（应限定
≤ 5 条）——两条约束写在本文档里，下次发现违反时把本 decision 引为依据。
