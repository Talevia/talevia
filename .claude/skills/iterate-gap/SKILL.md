---
name: iterate-gap
description: 从 docs/BACKLOG.md 挑最高优先级任务，plan → 实现 → 归档决策 → 推 main。backlog 空了再按 rubric 一次性补 20 条。参数 "<count> [parallel]"，例：/iterate-gap、/iterate-gap 3、/iterate-gap 4 parallel。执行期间零提问。
---

# iterate-gap — backlog-driven 补齐愿景 gap 的循环

挑当前仓库与北极星之间优先级最高的 gap，plan → 在 `main` 上实现 → 归档决策 → 推送。**执行期间不向用户提任何问题** —— 每一个决策都按 `docs/VISION.md` 与业界共识自主做出，理由作为**一个新文件**落到 `docs/decisions/`（一次 iteration 一个文件，绝不 append 到其它文件），由用户事后异步审阅。

**任务源是 `docs/BACKLOG.md`**（P0 → P1 → P2，同档内按出现顺序取第一个）。只有当 backlog 被清空时，才 fallback 到 rubric 分析，一次性生成 20 条新任务写回 backlog，并在同一 cycle 里继续挑新生成的第 1 条动手。

## 参数

按 `<count> [parallel]` 解析 skill 的参数字符串：

- 无参数 → `count=1`，顺序执行
- `<N>`（如 `3`）→ `count=N`，顺序执行（一个 cycle 做完再做下一个，每个开始前 rebase 一次）
- `<N> parallel`（如 `3 parallel`）→ `count=N`，用 git worktree 并行，等全部跑完后依次合回 `main`

上限：顺序模式 `count ∈ [1, 8]`，并行模式 `count ∈ [2, 4]`。参数格式有误时**默认退回 `1` 顺序执行**，不要问用户澄清。

## 操作约束（两种模式都适用）

- **分支目标**：`main`。不开 feature branch，不开 PR。（并行模式内部用临时 worktree 分支，但本次调用内部必须通过 merge+push 落回 `main`。）
- **提问**：零次。遇到决策点按 VISION + 业界共识自行决定，理由写成一份 `docs/decisions/<yyyy-mm-dd>-<slug>.md` 新文件。如果真的卡在只有用户能回答的问题（专有 key、产品抉择、品牌偏好），**换一个 gap**（从 backlog 的下一条取），不要干等，也不要问。
- **Backlog 是唯一任务源**：所有候选 gap 都来自 `docs/BACKLOG.md`。空 backlog → 先 rubric-repopulate（见下文 R 小节），再继续。不要凭空脑补"临时 gap"绕过 backlog。
- **先 plan 再实现**：每个 gap 必须有独立的 plan 步骤之后再动代码。
- **决策强制归档**：每一轮只做**一次 commit**，message 用 `feat(...)` / `fix(...)` / `refactor(...)` / `docs(...)` 之类按内容贴合的前缀，单句 subject 讲清本轮改了什么。这一个 commit 同时包含：① 代码改动；② 新建的 `docs/decisions/<yyyy-mm-dd>-<slug>.md` 决策文件（**新建**，不得 append / 编辑已有 decisions）；③ `docs/BACKLOG.md` 里对应 bullet 的删除；④ 可选的 "顺手记 debt" P2 append。决策文件不需要引用 commit hash —— 决策 + 代码都在同一个 commit 里，`git log --all -S '<slug>'` 天然能对齐。

---

## 顺序模式（默认）

把下面的 cycle 重复 `count` 次。每次迭代之间 `git pull --rebase origin main`，确保下一轮的 backlog 读取能看到自己刚推送的产出（本轮从 backlog 删掉的 bullet）以及同事期间的新提交。如果本地 backlog 已空且 rubric 也找不出任何有效 gap（Core rubric 全部"有"、OpenCode 没有明显缺口、当前代码离 VISION 没有可闭环差距），**提前停止**，不要制造凑数任务。最后报告诚实地写 `完成 N / 请求 M`。

### 1. 同步 main

```
git fetch origin
git pull --rebase origin main
```

工作树脏或 rebase 失败 → **停下来报告**，不要用丢弃修改的方式「抢救」。

### 2. 读 backlog，挑一个

读 `docs/BACKLOG.md`。分三种情况：

- **P0 有未完成项** → 挑 P0 最靠上的一条作为本轮 gap。
- **P0 空、P1 有** → 挑 P1 最靠上的一条。P2 同理。
- **三档全空（或文件不存在）** → 跳转到下面的 **R. Backlog repopulate**，产出 20 条新任务写回 `docs/BACKLOG.md` 并 commit，然后**在本 cycle 内继续**按 "P0 最靠上" 规则从新列表里挑第 1 条接着做。

挑出后，记录：

- bullet 的标题（`**<slug>**` 那一段，用作 `<slug>` 和 decision 文件名种子）。
- bullet 的 Gap / 方向 / Rubric 轴原文（plan 阶段会用到）。
- 同档内的第二条（亚军候选）留给最后报告。

**不再**做"自由式 rubric 分析 + 3-5 候选 + 排序"；backlog 已经把优先级写死了。backlog 里每条都是上一次 rubric 分析过、通过平台优先级过滤过的，重复排序只会浪费 token。

**Skip-tag 过滤**：bullet 行末若带 ` · skipped <YYYY-MM-DD>: <reason>` 尾注（见 §3），且**原因仍成立**（同一平台优先级窗口未打开、同一 trigger 未触发、同一用户输入仍未给出）→ 继续跳到下一条。原因已失效（trigger 已触发、平台窗口已开） → 清掉尾注恢复为可挑状态再 plan。连续被 skip ≥ 3 次的 bullet 由下次 repopulate 专门处理（见 §R）。

### 2.5 Liveness pre-check（挑中 bullet 后、plan 前，≤ 60 秒）

历史数据：4/11 ≈ 36% 的 bullet 在真正 dispatch 时才发现症状已被先前 refactor 默默解决（见 `docs/decisions/` 里多个 `*-stale-no-op.md`）。为避免每次都交"走一遍代码 → 写 no-op decision"的 tax，**挑中 bullet 后先花 ≤ 60 秒**尝试复现它声称的症状：

- **bug-fix bullet** — grep bullet 里点名的测试 / 文件 / 类符号；`git grep @Ignore <testName>` 看测试是否还被 skip；跑 bullet 声称坏的 gradle target 看是否仍然红。
- **refactor bullet** — grep bullet 里的反模式症状（"MediaStorage.import"、"nested row data class"、"inline okio source→sink copy" 之类）是否还存在。
- **新功能 bullet** — grep 声称缺失的符号 / 文件 / 接口是否已经被同名或相邻命名的实现覆盖。

**症状无法复现** → 走 **skip-close**：本 cycle **不写代码**，直接归档为"已被先前 refactor 解决"：

- 新建 `docs/decisions/<yyyy-mm-dd>-<slug>-stale-no-op.md`。Body 说明"症状无法复现；`<shorthash>` commit 的 refactor 已解决"，引用那次 commit 的 shorthash + 一句话 refactor 摘要。
- 同 commit 删 BACKLOG 对应 bullet。
- Commit message：`docs(backlog): skip-close <slug> — already resolved by <shorthash>`。
- **算本 cycle 的 1 个 gap**（保持 count 语义，让 `count=N` 的调用仍然推进 N 步）。

**症状能复现** → 正常进入 §3 plan。

### R. Backlog repopulate（只在 backlog 空时执行）

触发条件：第 2 步发现 `docs/BACKLOG.md` 不存在或三档全空。

依次读：

1. `docs/VISION.md` §5（Gap-finding rubric）—— 7 个 rubric 小节（§5.1–§5.7）就是打分轴。§5.6 系统健康 / 技术债、§5.7 性能 / 成本预算都与前 5 节 feature 轴一视同仁竞争优先级窗口。
2. `CLAUDE.md` 的「Platform priority — 当前阶段」小节定优先级；「Known incomplete」小节列出已承认的非回归项，别把它们当缺口重复报。
3. `docs/decisions/` 最近 ~20 个文件（`ls docs/decisions | sort -r | head -20`）—— 近期已做决策约束了不该再做一遍的内容。
4. `git log --oneline -30` —— 看最近落地了什么。

然后走读 `core/domain`、`core/tool/builtin`、`core/agent`、`core/session`、`core/compaction`、`core/permission`、`core/provider`、`core/bus` 以及各 app 的装配点，对每个 rubric 轴打分为 有 / 部分 / 无。候选来自两处：

- VISION §5 中打分为「部分」/「无」的轴。
- OpenCode 行为对照差距，按 CLAUDE.md 「OpenCode as a 'runnable spec'」里列出的映射文件比，**只抽取行为，绝不抄 Effect.js 结构**。

产出**恰好 20 条**新任务，按以下硬性顺序分档：

1. **平台优先级** —— Core 先。Core rubric 轴打分为"部分 / 无"的全部进入 P0 / P1。只有 Core 每个轴都是"有"之后，才允许非 Core 的 gap 进入 P1 或 P2。
2. **一等抽象 > patch** —— 可复用抽象归 P0 / P1；单 genre / 单特效的 patch 归 P2 或直接舍去。
3. **短周期能闭环** —— 同一档内，能闭环的排在前面。

**Debt 占比硬性要求**：20 条里**至少 6 条（30%）**必须是从下面 **R.5 技术债扫描**里出来的 `debt-*` 任务。没有做 debt 扫描 / 扫描出来不到 6 条 → 必须把 debt 档按扫描结果如实加，宁可 feature gap 减少也不能砍 debt 名额。这是防止系统因为只做新功能而持续劣化的硬闸门。

分档建议比例（非硬性）：P0 约 3-5 条（含 1-2 条 debt）、P1 约 8-10 条（含 3-4 条 debt）、P2 余数。如果真找不出 20 条值得做的 gap，可以少写（最少 8 条，其中 debt 至少 3 条），在报告里说明。

### R.5 技术债扫描（repopulate 必做）

技术债**信号类别**和"债与 feature gap 竞争同一优先级窗口"的原则由 VISION §5.6 定义；本节给出 skill 层的**扫描命令 + 严重度阈值 + 配额强制 + 监控曲线约定**。

**Debt 占比硬性要求**：20 条 repopulate 里**至少 6 条（30%）**来自本节扫描。没做扫描 / 扫出来不到 6 条 → 按实际结果如实加，宁可 feature gap 减少也不能砍 debt 名额。这是防止系统只做新功能而持续劣化的硬闸门。

**扫描命令**（每个信号产出一条 slug 以 `debt-` 开头的 backlog 任务；净增长指标与 `main` 前一次 `docs(backlog)` commit 快照对比）：

1. **Tool 数量净增长** —
   ```
   find core/src/commonMain/kotlin/io/talevia/core/tool/builtin -name "*Tool.kt" | wc -l
   ```
   对比 `git show <prev-backlog-commit>:core/.../tool/builtin`。增幅 > 5% 或绝对增长 ≥ 5 → `debt-tool-consolidation-<area>`（area 用增长最快的子目录名）。**最容易劣化的地方**，每次必扫。

2. **近似工具群** —
   逐个扫 `core/tool/builtin/<area>/`，同一 area 下 2+ 个同前缀工具（`list_X*` / `find_X*` / `describe_X*` / `Define* + Update*` 成对）→ `debt-consolidate-<area>-queries`。过去几个月最频繁的劣化信号。

3. **Project 字段膨胀** —
   ```
   grep -cE '^\s*val ' core/src/commonMain/kotlin/io/talevia/core/domain/Project.kt
   ```
   和上次快照对比。新增类型为 `List<X>` / `Map<K, V>`（append-only 语义）的字段 → `debt-extract-<field>-table`（拆到独立 SQLDelight 表）。

4. **长文件** —
   ```
   find core/src/commonMain/kotlin -name "*.kt" -exec wc -l {} + | sort -rn | head -10
   ```
   > 500 行 → `debt-split-<filename>`。超 800 行强制 P0 / P1。

5. **被跳过的测试** —
   ```
   grep -rnE '@Ignore|@Disabled|\.skip\(' core/src/*Test/kotlin platform-impls apps
   ```
   每条 → `debt-unskip-<test-name>`（要么修要么删）。**跨 cycle 升级**：和上次 repopulate 的 commit body 里的扫描结果对比，同一 `@Ignore` 出现 ≥ 2 次（即存活跨 ≥ 1 次 repopulate 周期）→ 强制 P0。永久存活的 `@Ignore` = silently-lying gate，承诺未来覆盖但从不兑现，是多个历史 bullet 描述失真的主要来源（`fork-project-tool-trim-stats-bug` 案例：bug 已修但测试还 `@Ignore` 挂着，描述烂掉一整轮 cycle）。

6. **TODO / FIXME / HACK 净增长** —
   ```
   grep -rnE 'TODO|FIXME|HACK|XXX' core/src/commonMain/kotlin | wc -l
   ```
   和上次快照对比。净增长 > 0 → `debt-clean-todos`，decision 里列新增行号让下轮有据可查。

7. **@Deprecated 不清理** —
   ```
   grep -rn '@Deprecated' core/src/commonMain/kotlin
   ```
   存在 > 1 轮 repopulate 周期仍未移除 → `debt-remove-deprecated-<symbol>`。永不清理的 Deprecated = 代码里有两份实现，比保留还差。

8. **Dead code** —
   查最近一次大重构（`git log --oneline -30` 找 `feat(core):` / `refactor(core):` 主题重构）之后，schema / 类 / 表 / 字段是否还挂在代码里但没人消费。参考写法：
   ```
   # 找到可疑符号后，grep 引用方验证
   grep -rn '<symbol>' core apps platform-impls --include='*.kt'
   ```
   只剩定义没有消费方 → `debt-remove-dead-<area>`。典型例子：`baad43f` 文件化 ProjectStore 后遗留的 4 张 SQLDelight 项目表。

9. **Gradle test suite 健康** —
   ```
   ./gradlew :core:jvmTest :apps:server:test :apps:cli:test --continue
   ```
   任一 suite 预存在红（非本轮改动造成） → 修复 bullet **强制 P0**。历史上 `apps/server:test` 红 7+ cycle 每轮都在交 "stash + 跑 + 确认红是预存在的" externality 税，30 行就能修；红 test suite 在 main 上永远不是 P2（见硬规则 §14）。

10. **关键路径 runtime 未测** —
    ```
    # prod 符号出现地
    grep -rnE 'Schema\.migrate|\.migrate\(driver|AgentLoop|ExportTool|FileProjectStore\.openAt' core apps --include='*.kt' | grep -v '/src/.*Test/'
    # 同名在测试里是否有 exercise
    grep -rnE '<symbol>' core apps --include='*.kt' | grep '/src/.*Test/'
    ```
    第一个 grep 非空 + 第二个空 = runtime-untested critical path → `debt-add-runtime-test-<path>`。关键路径（`Schema.migrate` / agent loop / `ExportTool` / `FileProjectStore.openAt`）强制 P0；其他 P1。"编译过"不等于"运行时正确"，SqlDelight migration 类场景被 `:apps:server:test` 红遮盖了几个月才被 prophylactic 补上。

**严重度分档**：
- 强制 P0：长文件 ≥ 800 行、被跳过的测试 ≥ 3 条或任一 `@Ignore` 跨 cycle 存活、tool 绝对增长 ≥ 10、确认的 dead code（有具体符号 + 零消费方证据）、任一 `:apps:*:test` 在 main 上预红、关键路径 runtime 未测（`Schema.migrate` / agent loop / `ExportTool` / `FileProjectStore.openAt`）。
- 默认 P1：长文件 500–800、近似工具群、Project 字段膨胀、存在 > 1 轮的 @Deprecated、非关键路径 runtime 未测。
- 默认 P2：TODO 净增长、小幅 tool 增长。

**监控曲线**：扫描结果写进 `docs(backlog)` 这次 commit 的 message body（简洁列出各指标对比数字），`git log` 本身就是劣化监控曲线。跳过扫描 / debt 占比不足的 repopulate commit 不合法（见硬规则 13）。

### R.6 性能 / 成本 scan（repopulate 必做）

VISION §5.7 定义了运行时预算轴；本节给出 skill 层的**轻量静态 scan**（不实跑系统）。动态度量（实测 export 计时、实测 session token）归 benchmark 基础设施，不属于每轮 repopulate 的机械动作。

**Perf 不设 30% 配额**（量少时某些轴需要新基础设施，不是每轮都有可挑），但 scan 必须跑，结果和 R.5 一样写进 `docs(backlog)` commit body。跳过 = 违反硬规则 13。

**Scan 条目**：

1. **Tool spec 的 per-turn 成本** — 复用 R.5 #1 的 tool 计数，但判定轴改为 LLM context 负担。如果可以跑 `core.metrics` 暴露的 `tool_spec_budget` 最新值 > 15k token，或 `*Tool.kt` 总数 ≥ 40 → `debt-shrink-tool-spec-surface`。和 R.5 的 `debt-tool-consolidation-*` 区别：前者按**领域重复**合并，后者按**token 预算**削减（例如合并近似 query 工具、把 tool 参数做成 enum 减少 top-level tool 数）。两条都出不算重复 —— 同一 tool 膨胀从不同角度发力。

2. **Unbounded 增长结构** —
   ```
   grep -rn 'CounterRegistry\|AtomicLong\|AtomicInteger\|mutableListOf\|mutableMapOf' core/src/commonMain/kotlin
   ```
   每处都要能证明有上界或周期性回收。无法证明 → `debt-bound-<field>`。EventBus 的 CounterRegistry 本身 ok（它是度量基础设施），但**基于 CounterRegistry 堆的业务字段**要审。

3. **Session / compaction 上界** —
   ```
   grep -rnE 'maxMessages|windowSize|tokenBudget|budget|compact' core/src/commonMain/kotlin/io/talevia/core/compaction core/src/commonMain/kotlin/io/talevia/core/session
   ```
   找不到任何硬上界 / 预算配置 → 强制 P0 `debt-bound-session-history`。**session history 无上界 = 系统一定会炸**，属于不能延后的 P0。

4. **核心路径 benchmark 守护** —
   ```
   find core platform-impls apps -type f \( -name "*Benchmark*.kt" -o -name "*Perf*.kt" -o -name "*Latency*.kt" \)
   ```
   `core/agent`、`core/tool` dispatch、`ExportTool`、`FileProjectStore` 有没有 wall-time / token-count 回归测试？关键路径零 benchmark → `debt-add-benchmark-<path>`。没有 benchmark = 没有回归守护 = perf 劣化无人发现。

5. **Perf decision 停更** —
   ```
   ls docs/decisions | grep -iE 'perf|latency|cost|token|budget|benchmark|incremental'
   ```
   若最近 3 个月零相关 decision → 提示本轮 repopulate 至少考虑一条 perf gap（**非硬性**，但连续零 perf 工作本身就是隐性劣化信号）。

**严重度分档**：
- 强制 P0：session / compaction 零上界、`tool_spec_budget` > 20k token、核心路径（agent loop / ExportTool）零 benchmark。
- 默认 P1：`tool_spec_budget` 15–20k、Project I/O 经分析为 O(size) 而非 O(delta)、次核心路径零 benchmark、可疑 unbounded 增长结构。
- 默认 P2：perf decision 停更 > 3 个月、单个 unbounded 字段待审。

扫描结果写进 `docs(backlog)` commit body（`perf scan:` 开头，与 `debt scan:` 并列），`git log` 就是 perf 劣化曲线。

写入 `docs/BACKLOG.md` 的格式与当前文件保持一致：

- 文件顶部的操作说明段落原样保留（这次 repopulate 不是重写文件说明，只是刷新任务列表）。
- 每条 bullet 格式：`- **<slug>** — <Gap：现状 / 痛点>。**方向：** <期望动的东西>。Rubric §5.x。`
- `<slug>` 用 kebab-case，一眼看出改什么（例 `unify-project-query`、`split-project-json-blob`）。避免和 `docs/decisions/` 已有文件名撞 —— 重名就换一个角度命名。
- **"方向" 描述不变量，不写死文件路径。** 例：写 "guard 跨机器打开同一 bundle 时 assetId→path 保持稳定" 而不是 "在 `apps/cli` 或 `apps/server` 加测试"；写 "暴露 `writeBlobStreaming(source: okio.Source)` 让大文件不入内存" 而不是 "改 `FileBundleBlobWriter.kt`"。路径是 guidance —— 让实现的 cycle 按 invariant 自己找最自然的层（test 放在被测契约所在层、helper 放到 caller 同包）。误导到错误 layer 的 bullet 会浪费一整个 cycle。
- **连续 skip 处理**：repopulate 开始前扫全 BACKLOG，把行末带 ` · skipped <date>:` 且连续被 skip ≥ 3 次的 bullet 替换为一条 `re-evaluate-<slug>` 元 bullet（保留原 slug 的核心描述 + 列出历次 skip 原因），交给用户在下一次 repopulate 批次里决定 promote / demote / delete。这防止老 bullet 在 queue 里无限期挂着复读 skip-check。

**Repopulate 本身就是一次独立 commit**（不走 "feat + docs pair"）：

```
docs(backlog): repopulate <N> tasks from rubric analysis
```

只改 `docs/BACKLOG.md` 一个文件。push 之后**不要把 repopulate 本身算作本轮的 "1 个 gap"**，继续回到第 2 步挑新列表里的第 1 条，照常走 plan → 实现 → 归档 → 删 bullet → 推送的完整 cycle。换句话说：repopulate 是"补库存"，不是"交付"。

### 3. Plan

内部 plan（不要用 ExitPlanMode —— 用户说了不提问）。Plan 必须包含：

- 对应的 rubric 轴（直接复制 backlog bullet 里已经标的 `§5.x`）。
- 会改哪些文件、新增哪些文件、在哪几个 `AppContainer` 注册新 tool。
- 这次改动必须守住哪些 CLAUDE.md 架构规则（`core/commonMain` 零平台依赖、Timeline 归 Core、`Tool<I, O>` 带 serializer + JSON Schema、`MediaPathResolver` 管路径、provider 中立的 `LlmEvent`、禁止 Effect.js 模式）。
- 跑哪个 `./gradlew` target 证明正确性（从 CLAUDE.md 的 Build & run 表里选最贴的那一个）。
- **反需求核查** —— 扫一遍 CLAUDE.md 的「Anti-requirements」清单。如果 plan 踩红线，**丢掉这个 plan 换下一条 backlog**，不要反过来 challenge 用户。
- **设计约束自查** —— 过一遍下面 **3a 清单**的 10 条。任意一条命中"是 / 可能"，要么在 plan 里显式说明怎么避开，要么**换下一条 backlog**（优先这个，"这次就例外一下"是系统劣化的主要路径）。

如果 plan 需要只有用户能给的信息（专有 key、产品抉择等）或 §3a 命中红线，**换下一条 backlog bullet**。被跳过的 bullet 保留在文件里，但**行末追加一个 skip-tag**：

```
- **<slug>** — ... Rubric §5.x。 · skipped <YYYY-MM-DD>: <一句话原因>
```

原因示例："平台优先级窗口未开（mobile non-regression only）"、"trigger 条件未触发：5-container 占比当前 60% < 10 连续阈值"、"需要专有 API key（REPLICATE_API_TOKEN），待用户决定"、"§3a #3 命中：会让 `Project` blob 再膨胀一个 `List<X>` 字段"。

Skip-tag append 是 **BACKLOG 允许的两种 in-place 编辑之一**（另一条是 §6 的"顺手记 debt" P2 append）；除此之外仍然是"只删不改"。下次 §2 挑 bullet 时用 skip-tag 过滤已跳条目（原因仍成立继续跳，原因失效清掉尾注恢复可挑）；连续被 skip ≥ 3 次的 bullet 下次 repopulate 专门处理（见 §R）。

### 3a. 设计约束自查清单（Plan 必跑）

这 10 条是从 Talevia 至今已经交过"学费"的设计反模式里提取出来的。每条都对应仓库里真实出现过的劣化信号。Plan 阶段必须逐条过，任何一条命中都是换 backlog 的充分理由。

1. **工具数量不净增** — 本轮会新增几个 `Tool.kt`？先问：能在现有工具上加 filter / sort / limit 参数覆盖吗？能预留给未来 `project_query` 原语吗？如果仍要新增，**必须同时删/合至少一个近似工具**。净增长 ≥ +2 要在 decision 里显式辩护。LLM 每轮都要付 tool spec 的 token，这是累积成本。

2. **Define* / Update* 不成对** — 如果是"创建 + 编辑某种 source 概念"，只做一个 `set_<concept>` upsert 工具。对 LLM 来说两个互斥分支等于两倍 spec 成本换零收益。

3. **Project blob 不膨胀** — 本轮会在 `Project` data class 上加新字段吗？如果是 append-only 语义（历史 / 日志 / 每次生成追一条），**必须独立 SQLDelight 表**，外键 projectId。直接加到 `Project` 上每次 tool 调用都要整块 re-encode，写放大会随项目寿命非线性恶化。

4. **状态字段不做二元** — 引入新的 `stale` / `fresh` / `pinned` / `dirty` / `bound` 标志位前，先想清楚有没有第三态 `Unknown` / `NotApplicable`。二元默认会惩罚不参与该机制的用户（历史教训：`Clip.sourceBinding` 空 → 恒 stale）。

5. **Core 不硬编码 genre 概念** — 新 tool / 新类型 / 新 helper 里如果出现 `character_ref` / `style_bible` / `brand_palette` / `product_shot` / `script` 这类 genre-specific 名词作为一等类型或工具 id 的一部分，停下来：这是 `SourceNode.body` 的不透明内容，还是 Core 的一等概念？默认前者。新增 folding / 一致性传播逻辑默认放 `source/<genre>/consistency/`，不往 `source/consistency/` 里加新 kind。

6. **Session ↔ Project 绑定隐含** — 新 tool 的 input 里如果有 `projectId: ProjectId`，检查：等 `session-project-binding` 落地后这个参数应当从 session context 拿。现在可以暂接参数，但要在 decision 里标一行"待 session-project-binding 后切 context"。

7. **序列化向前兼容** — 新加 `@Serializable` 字段必须有 default 值（否则旧 JSON / SQLite blob 解不出来，迁移成本高得离谱）。删字段前 `grep` `docs/decisions/` 看有没有依赖。`kotlin.time.Duration` 不加自定义 serializer（历史名字碰撞事故）。

8. **五端装配不能漏** — 新 tool 必须在 CLI / Desktop / Server / Android / iOS 五个 `AppContainer` 都注册。一个漏掉 = 该平台默默丢失功能，用户很难发现。Plan 里列全这 5 个文件。

9. **测试覆盖语义而非 happy path** — 本轮如果引入条件分支 / 状态机 / 缓存失效 / 增量计算，测试里要至少一个"反直觉边界"case：空输入、重复调用、并发、版本漂移、stale 穿透。只跑 happy path 的测试对系统保护约等于 0。

10. **LLM context 成本可见** — 本轮加的 tool spec + helpText + system prompt 片段，给每一次 turn 加多少 token？粗估 ≥ 500 token 需要在 decision 里说明必要性、以及后续是否会并入更大的 query primitive 被吸收掉。

11. **Bug-fix bullet 自带验证路径** — 本轮若是 bug-fix（bullet 描述某个行为错了），plan 里必须先写出**一行验证指令**证明 bug 还活着：典型 `git grep @Ignore <testName>` 返回非空、`./gradlew <testTarget> --tests '<FQN>'` 红色、或 `grep <symbol>` 返回具体行号。跑完这一行再动代码。若验证返回空（bug 已被先前 refactor 默默修掉），走 §2.5 skip-close 路径，不要继续写代码再发现症状不在（历史上 `fork-project-tool-trim-stats-bug` 就花了半轮 cycle 才意识到描述的 bug 已经死了）。

12. **架构税阈值检查** — plan 阶段扫一眼 BACKLOG 里已有的 trigger-gated bullet，看本轮动作是否让它触发：
    - `debt-register-tool-script`：本轮新注册 tool 到 5 个 `AppContainer` 吗？若是，这是连续第几轮？≥ 10 连续或最近 15 cycle 里占比 ≥ 60% → 本 cycle 在 §6 "顺手记 debt" append 里写一句"升 `debt-register-tool-script` 为 P1"（实际升档在 BACKLOG 里挪位置）。
    - `debt-unified-dispatcher-select-plugin-shape`：本轮给 `project_query` / `session_query` / `source_query` 加 select 吗？加完后目标 dispatcher 的 select 数 ≥ 20 或 `rejectIncompatibleFilters` 规则数 ≥ 30 → 同上升档。
    - 未来新增的 trigger-gated bullet 也在此罗列。
    本条不是"命中就换 backlog"语义（和前 11 条不同），是"命中就升档已有的 follow-up bullet"。

**命中 1-11 任一条不是"想办法绕过"的信号，是"换下一条 backlog"的信号**。本项目已经踩过的坑不会自动绕第二次 —— 必须显式拒绝。命中 #12 只触发升档，不换 backlog。

### 4. 实现

- `kotlinx.serialization` + `JsonConfig.default`（不要自定义 `Duration` serializer，不要 ad-hoc `Json` 实例）。
- `core/commonMain` 零平台依赖。
- 新 tool 必须在**全部 5 个** `AppContainer`（CLI / Desktop / Server / Android / iOS）都注册 —— 一个不能漏。
- SQLDelight 迁移设置 `PRAGMA user_version`；降级拒绝。

### 5. 验证

跑最贴的 gradle 测试：

| 改动区域 | 最低测试 |
|---|---|
| `core/**` | `./gradlew :core:jvmTest` |
| `platform-impls/video-ffmpeg-jvm/**` | `./gradlew :platform-impls:video-ffmpeg-jvm:test` |
| `apps/server/**` | `./gradlew :apps:server:test` |
| iOS framework 暴露面 | `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :core:compileKotlinIosSimulatorArm64` |
| Android | `./gradlew :apps:android:assembleDebug` |
| Desktop | `./gradlew :apps:desktop:assemble` |

**先跑 `./gradlew ktlintFormat` 再跑 `./gradlew ktlintCheck`**。我们的 hygiene-only profile（unused imports / final newline / import order）下 format 是机械且安全的；format 在前省掉"check 红 → format → 再 check → 绿"的重复来回（历史上 9/15 ≈ 60% 的 cycle 都因为新插入 import 的字母序错位吃这份税）。check 仍是红线 gate —— **红色不准 commit**。

### 6. 归档决策 + 删 backlog bullet

在 `docs/decisions/` 下**新建**一个文件：`docs/decisions/<YYYY-MM-DD>-<slug>.md`。**不要** 编辑或 prepend 到已有文件。

- 日期用当天（UTC 本地即可），与 commit 日期对齐。
- `<slug>` 直接复用 backlog bullet 的 `<slug>`（即 `**<slug>**` 里那段）。落不下来时按"`[^a-zA-Z0-9]+` 替换成 `-`、小写、截断到 ~60 字符"规则兜底。
- 命名冲突（同日同 slug）时加后缀 `-2`、`-3`。可用 `ls docs/decisions | grep <yyyy-mm-dd>-<slug>` 确认唯一。

同一步把本轮处理的 bullet 从 `docs/BACKLOG.md` 删掉：

- 只删这一条 bullet（整行，含前导 `- ` 和可能的紧跟空行），**不要**重写整个文件、不要修改顶部操作说明段落、不要调整剩余 bullet 的顺序。
- 被跳过的 bullet（plan 阶段判定踩红线或缺用户输入）保留不动。
- 用 `git diff docs/BACKLOG.md` 确认只减不增：diff 里只有被删那几行，没有 reorder / 重排 —— **除了**下面这种情况：

**顺手记 debt（本 cycle 唯一允许的 bullet 新增）**：实现 / 验证过程里如果发现一条与本任务无关的技术债（某个文件突然长到 600 行、测试里发现一个 skip、撞上两个近似 tool），**不要修**（会把本 PR 变大 / 偏离 plan），而是在 `docs/BACKLOG.md` 的 **P2 档末尾 append** 一条：

```
- **debt-<slug>** — <发现了什么>。**方向：** <建议的修法>。Rubric 外 / 顺手记录。
```

规则：
- 只能 append 到 P2 末尾，不能插队到 P0 / P1，不能重排既有 bullet。
- 一次 cycle 最多 append 2 条（发现更多说明跑偏了，用报告提醒用户）。
- 和本轮的代码改动 + 决策文件 + bullet 删除一起进**同一次 commit**（见 §7）。
- **只记不修**。把它当成"观察笔记"，下次 repopulate 或专门调度时处理。这是让系统持续自省而不打乱本轮节奏的机制。

文件内容保持既有 decisions 格式（内部 `## YYYY-MM-DD — 短标题` 头一仍要写，便于跨文件 grep / 聚合视图）：

```markdown
## YYYY-MM-DD — 短标题（VISION §X.Y rubric 轴）

**Context.** 这个 gap 为何是本轮第一。对应的 rubric 轴 + 当前代码里观察到了什么 + 本轮 rubric delta（例："§5.1 source-layer 序列化 无 → 部分"）—— delta 是 VISION §5 "怎么用" 要求的系统演进曲线，必须留。
如果参考了 OpenCode，引用具体文件。

**Decision.** 落地了什么。关键类型名、tool 名、文件。

**Axis.** 仅当本轮是 split / extract / dedup / refactor 时必填。一句话回答
"什么东西增长时会重新把这个文件 / 类 / 模块吹回阈值"。例：
「new select rows」（ProjectQueryTool 过去两次 resplit）、「variant-spec
reshape 分支」（ForkProjectTool）、「新 transition kind」。非结构改动填 `n/a`。
轴命名是为了防止下一次 long-file / consolidation bullet 被同一 axis 二次
触发时又沿错轴切（历史教训：第一次 split 沿 handler 切，保留 row 嵌套，
一个月后 row 又长回来）。

**Alternatives considered.** 至少两个。每个：做了什么 + 为何被拒。
「业界共识」要具体到名字才算数（如「kotlinx.serialization 约定」、
「OpenCode tool-dispatch 形态」、「SemVer」）。

**Coverage.** 哪些测试覆盖了这个改动。

**Registration.** 动了哪几个装配点（或「无需注册 —— 纯 schema / 纯内部重构」）。
```

**观察日志（分三类走向）**：实现 / 验证过程中观察到的信号按**类型**分流到**不同文件**，不要全倒进一个 catch-all：

- **硬规则 / VISION-level 反馈** —— "这条 CLAUDE.md 红线让场景 X 失灵"、"VISION 的平台优先级约束在 Y 驱动下需要重评估"、"某个不变量在某类场景下站不住" → append 一段到 `docs/PAIN_POINTS.md` 底部，格式 `## <YYYY-MM-DD> — <slug> (<short-sha>)` + 3–10 行观察。这些条目是**定期硬规则合理性评审**的输入。
- **iterate-gap skill tweak 建议** —— "这个 skill 步骤有 friction"、"R.5 这条 scan 漏了轴"、"§3a 某条太严 / 太松" → **不走 PAIN_POINTS**，直接在 §6 的"顺手记 debt" P2 append 里加一条 `- **debt-skill-<slug>** — <观察>。**方向：** <建议修法>。Rubric 外 / 顺手记录。`。skill-level tweak 走 BACKLOG 的好处：下次专门修 skill 时一眼看见，不和硬规则反馈混。
- **工程 gotcha / 正面范式** —— Kotlin 语言陷阱、协程 / Flow 测试坑、库边界 `assertFailsWith` 脆弱、build / lint 细节、refactor judgement 范式 → append 一段到 `docs/ENGINEERING_NOTES.md` 对应主题小节。这些是项目无关的知识库条目。

**关键判别**：观察在挑战某条写进 `CLAUDE.md` 硬规则 / `docs/VISION.md` 不变量 / 平台优先级的规定吗？是 → PAIN_POINTS；否 → BACKLOG 或 ENGINEERING_NOTES。

没有观察也完全 ok —— 不要为了凑数编观察。只有本轮实现真的碰到某一类信号时才写。

### 7. Commit + push

- 按具体文件名 stage（严禁 `git add -A`，CLAUDE.md 红线）。
- **一次 commit**，同时包含：
  ① 代码改动；
  ② 新建的 `docs/decisions/<yyyy-mm-dd>-<slug>.md`；
  ③ `docs/BACKLOG.md` 里对应 bullet 的删除（+ 本轮若跳过其他 bullet 产生的 skip-tag append，见 §3）；
  ④ **可选**"顺手记 debt" P2 append（含 `debt-skill-<slug>` 若观察是 skill tweak；以及 §3a #12 触发的架构税升档条目）；
  ⑤ **可选** `docs/PAIN_POINTS.md` append（仅硬规则 / VISION-level 反馈，见 §6 观察日志）；
  ⑥ **可选** `docs/ENGINEERING_NOTES.md` append（工程 gotcha / 正面范式）。
- Commit message 前缀按 `git log --oneline -20` 的惯例选最贴合内容的一个（当前：`feat(core):`、`fix(...)`、`refactor(...)`、`docs(...)`）。Subject 一句话讲本轮核心改动；body 可放决策摘要 + 测试结论。决策文件里**不要**写 `Commit: <hash>` —— 决策和代码同 commit，`git log -S '<slug>'` 就能对齐。
- **可选 `backlog-sweep:` footer**：若本轮的 refactor / consolidation / delete 在实现过程中意外解决了 BACKLOG 里另外几条 bullet 描述的症状（典型场景：删掉一个旧 API 的同时废掉了三条"围绕旧 API 的 debt bullet"），在 commit message body 末尾加一行 `backlog-sweep: slug1, slug2, slug3`，并**在本 commit 里同步把这些 bullet 从 BACKLOG 删掉**。这是 opt-in 的"refactor 作者声明自己顺手扫了队列"机制；没写 footer 不扫也完全合法。历史上 stale bullet 挂在 P1 队列里 4/11 次被 cycle 走到才发现已死，backlog-sweep 是把那份发现前移到 refactor commit 本身。
- `git push origin main`。
- 推送被拒（有人同时推过）→ `git pull --rebase origin main` → 如果 rebase 动了你的文件就再跑一遍验证 → 再推。rebase 冲突解不开 → **停下来报告**。绝不 `--force`、绝不对已推送的 commit `--amend`。

Backlog repopulate 那次的 commit 是独立的 `docs(backlog): …`（见 R 小节），不跟任何 feat commit 绑定 —— 它只改 `docs/BACKLOG.md` 一个文件，没有对应的决策文件需要归档。

### 8. 继续或收尾

还有剩余 iteration → 回到第 1 步。否则输出报告：

- 本次处理的 gap（每个一行：rubric 轴 + slug + 摘要）。
- 推送的 commit（shorthash pair；如果本轮触发了 repopulate，也列出 `docs(backlog)` 那条）。
- 跑了哪些测试 + 结果。
- 实现过程中意料之外的事。
- 本轮是否触发了 backlog repopulate；当前 backlog 剩余各档条数（P0 / P1 / P2）。
- 下次可挑的候选（就是 backlog 新的 top-1，用户可据此决定是否再次触发）。

---

## 并行模式（`<N> parallel`，N ∈ [2, 4]）

在隔离的 git worktree 里并发跑 N 个 cycle，跑完后在**本次调用内部**依次合回 `main`。

### P1. 同步 + 读 backlog + 选出 N 个「互不重叠」的 gap

同顺序模式第 1 步（`git fetch` + `git pull --rebase`）。

读 `docs/BACKLOG.md`，从头往下扫（P0 → P1 → P2，同档内按出现顺序），**挑 N 个互不重叠的 bullet**：

- **互不重叠**的定义：两个 gap 预期改动的文件集合两两不相交。主调度器为每个候选 bullet 预判改动文件集（依据 bullet 里的"方向"文字 + 走一遍相关源码），两两核查不相交。冲突时保留更靠上的那条（优先级高）。
- 互不重叠的数量 < N → **静默缩减 N** 到最大不重叠子集，最后报告提到缩减。（`docs/decisions/<slug>.md` 和 `docs/BACKLOG.md` 的 bullet 删除天然不冲突 —— 每个 sub-agent 写自己 slug 的新 decision，删自己那条 bullet。）
- 如果只有 1 个互不重叠的 bullet，**剩余名额 fallback 到顺序模式**。
- Backlog 空了（P0/P1/P2 全清）→ 先做一次顺序模式的 **R. Backlog repopulate**（不能并行做 repopulate，文件会冲突），commit + push，再回到本步读刚生成的 backlog。

### P2. 派发 N 个并行 agent

用 Agent 工具调用，`isolation: "worktree"`，**所有 N 次调用放在同一条消息里**，确保并发。

每个 sub-agent 的 prompt 自包含（它看不到本次对话上下文）。必须包含：

1. 分配给它的具体 backlog bullet —— 完整 bullet 原文（含 slug / Gap / 方向 / rubric 轴） + P1 plan 里给出的预期改动文件清单。
2. 复制顺序模式第 2.5–7 步（liveness pre-check → plan → 实现 → 验证 → 归档决策 + 删 bullet → commit）。§2.5 对分配给自己的 bullet 仍然适用 —— 症状无法复现就走 skip-close 路径（纯 decision + bullet delete 的 worktree commit，主调度器合进来时也不冲突）。仅一处调整：
   - **分支名**：在自动创建的 worktree 分支上 commit —— 不要改名、不要 checkout `main`、**不要 push**。分支保持未推送状态，由主调度器来合。
   - 决策文件照常写进 `docs/decisions/<yyyy-mm-dd>-<slug>.md`（**新文件**，不编辑已有文件）。
   - `docs/BACKLOG.md` 只删**自己这条** bullet（整行，含前导 `- ` 和可能的紧跟空行），其他 bullet 原样保留。各 sub-agent 删的是不同行，git merge 会把每段删除当独立 hunk 合并，不会互相冲突；但**不得**顺手重排剩余 bullet。
3. 它必须跑的 gradle 测试（从第 5 步的表选）。
4. 输出契约：最终消息必须包含 commit SHA（feat + docs pair）、决策文件名、被删除的 bullet slug、一句话结果摘要、测试是否绿。失败时不要 commit，把错误返回。

并行度上限 4。N > 4 时**静默 clamp 到 4**。

### P3. 收集结果

Agent 工具会返回每个 sub-agent 的分支 + 路径。分流：

- **成功**（有 commit，测试绿）→ 进入合并队列。
- **失败**（没 commit，或测试红）→ 丢掉这个 worktree 分支，不要合，记入最终报告。

零成功 → 报告后停下，不要为了好看 fallback 到顺序模式。

### P4. 依序合回 main —— 每个分支合完立刻 push

对每个成功分支，按确定性顺序（例如 rubric 轴 + 时间戳），**合完一个 push 一个**，绝不把多个功能攒成一次 push：

1. `git checkout main`
2. `git pull --rebase origin main`
3. `git rebase main <branch>` —— 有代码冲突在这里解决。`docs/decisions/<...>.md` 文件本身不会冲突（各 slug 独立新文件），这是拆分到 `docs/decisions/` 的主要收益。
4. Fast-forward 合入 main：`git checkout main && git merge --ff-only <branch>`。
5. **立刻 `git push origin main`** —— 开始下一个分支之前就推。每个功能的 feat+decision commit pair 在下一次合并开始前已经在远端。
6. 冲突处理：
   - `docs/decisions/*.md` —— 预期不冲突，真冲突（同日同 slug）→ 给其中一个加 `-2` 后缀并把 commit 里的文件名改过来，再继续。
   - `docs/BACKLOG.md` —— 预期不冲突（每个 sub-agent 删不同 bullet，git 当独立 hunk 合并）。真冲突 → 基于 `main` 当前状态重建"只删这一条 bullet"的 diff 再 merge。不要因此丢其他 sub-agent 的删除。
   - 代码冲突 —— **停下来报告**。不强行解。剩余没合的分支保留原状让用户检查。
7. 推送被拒 → `git pull --rebase origin main` → 再推。解不开 → **停下来报告**。
8. 下一个分支：回到第 1 步。直到队列空。

### P5. 清理

- 已合并的 worktree 分支删掉。
- Agent runtime 会自动清理**没有改动**的 agent 的 worktree；已合并分支对应的 worktree 路径在该分支落到 `main` 之后自行移除。
- 任何失败的 sub-agent 或被中止的合并 → **保留 worktree + 分支**让用户手动检查，把路径 + 分支名写进报告。

### P6. 报告

- 请求 N，派发 M（不重叠过滤后），成功 K，合入 main K'。
- 推送的 commit（shorthash）。
- 跳过 / 失败的 gap + 原因。
- 待人工检查的遗留 worktree + 分支（给路径）。
- 供下次调用的亚军 gap 候选。

---

## 硬规则（两种模式都不准违反）

1. **一个 cycle 内零提问**。卡住 → 换 backlog 下一条或停下。
2. 最终状态落在 `main`。并行模式的中间分支要么合入、要么作为遗留项报告，绝不静默丢弃。
3. **Commit → push，永远配对**。本地 `main` 一出现新 commit（顺序模式的 commit pair / repopulate 的 `docs(backlog)` 一条 / 并行模式合并完的一个分支），**立刻** `git push origin main` 再开下一个 cycle / 下一个合并。绝不能让本次调用结束时本地 `main` 有未推送 commit。
4. 绝不跳过 `./gradlew ktlintCheck`。lint profile 只做 hygiene，报错就是真有问题。
5. 不带 `docs/decisions/<yyyy-mm-dd>-<slug>.md` 新文件 **+** `docs/BACKLOG.md` bullet 删除的 pair 不准 commit docs commit —— decision 文件**新建**，不得 append / 编辑已有条目；BACKLOG 默认"只删不改"，唯二合法 in-place 编辑是 §3 skip-tag append 与 §6 "顺手记 debt" P2 append。`docs/PAIN_POINTS.md` / `docs/ENGINEERING_NOTES.md` 同样 append-only —— 只新增 cycle-dated section，不得编辑 / 重排历史条目。Repopulate 的 `docs(backlog)` commit 是例外（它重建 BACKLOG，不需要 decision pair）；§2.5 skip-close 也是例外（新 decision + bullet delete 成 pair，但没有代码改动）。
6. 绝不用 `--no-verify`，绝不用 `--force`，已推送的 commit 绝不 `--amend`，绝不用 `git add -A`。
7. 绝不绕过 CLAUDE.md 架构规则或反需求清单。如果一个 backlog bullet 必须绕才能做，**跳过它取下一条**（bullet 原样保留给用户裁决）。
8. 绝不把 OpenCode 的 Effect.js 结构搬过来。只抽行为。
9. 并行模式并发度永远 ≤ 4。超了静默 clamp。
10. 并行模式只选**互不重叠**的 bullet。重叠就静默缩减 N 并在报告里说明。
11. **Backlog 是权威任务源**。不凭空发明临时任务，不跳过 P0 直接做 P2。空 backlog → repopulate，不要绕过。
12. **设计约束 §3a 清单 #1–#11 是硬性否决**。任意一条命中"是 / 可能"→ 换 backlog 下一条。不允许"这次就例外一下"—— 历史上系统劣化几乎全部来自连续的"一次例外"。（§3a #12 "架构税阈值" 不在此列 —— 它只触发已有 bullet 升档，不换 backlog。）
13. **Repopulate 必须 ≥ 30% debt 任务**（详见 R.5）。Debt 扫描是硬性动作不是可选项。跳过 debt 扫描 / debt 占比不足的 repopulate commit 不合法，下一 cycle 发现立刻回滚该 repopulate 并重做。
14. **红 test suite 在 `main` 上永远不是 P2**。任一 `:apps:*:test` / `:core:*Test` 在 `main` 上预存在红（非本轮改动造成）→ 对应修复 bullet 强制 P0（见 §R.5 #9 / 严重度分档）。要么 P0 修，要么删这个 test suite（若它已不服务任何不变量）。"等它自愈"不是选项 —— red main = 每个后续 cycle 都要交 "stash + 跑 + 确认红是预存在的" externality 税，且红模块的新编译错误会被自遮蔽。历史上 `apps/server:test` 红 7+ cycle，修掉只花 30 行。
