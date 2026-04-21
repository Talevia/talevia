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

- **session-project-binding** — 当前 `Session` 和 `Project` 解耦，agent 靠对话上下文记住当前在操作哪个 project id（tool 参数一个个手传）。多项目并行时（用户同时剪 vlog + 叙事片）体验会崩。**方向：** 在 `Session` 里加一等字段 `currentProjectId: ProjectId?`，`Agent.run` 把它注入到 system prompt 里作为 cwd-analogue；提供 `switch_project` tool 让 agent / 用户显式切换。ToolContext 暴露 `currentProjectId` 便于工具默认注入。Rubric §5.4。

## P2 — 记债/观望

- **process-level-project-mutex** — `SqlDelightProjectStore` 用进程内 `Mutex` 保护 `mutate`。Desktop / CLI / 单副本 server 够用，多副本 server 或多进程共享 DB 会丢写。当前 server 是"可选 headless"，**暂不修**，但在本文件里记一笔，等 server 真要走生产路径时提前搜索此条。

- **server-auth-multiuser-isolation** — `apps/server` 从环境变量读 API key，`TALEVIA_MEDIA_DIR` 是单全局目录，catalog 全局共享。当前是"可选 headless"单用户，**暂不修**。升级到真多用户前在 server 模块里加显式"assumes single-tenant"的注释 + 在此条登记触发条件。
