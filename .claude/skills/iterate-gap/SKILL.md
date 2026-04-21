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
- **决策强制归档**：每次推送的 commit pair 都是 `feat(...)` + `docs(decisions): record choices for <feature> (<feat-hash>)`；后者**新建**一个 `docs/decisions/<yyyy-mm-dd>-<slug>.md` 文件，**不得** append 或编辑已有 decisions 文件。`docs/BACKLOG.md` 里对应 bullet 的删除也合并进这次 docs commit（同属"本轮书面记录"）。

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

### R. Backlog repopulate（只在 backlog 空时执行）

触发条件：第 2 步发现 `docs/BACKLOG.md` 不存在或三档全空。

依次读：

1. `docs/VISION.md` §5（Gap-finding rubric）—— 5 个 rubric 小节就是打分轴。
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

分档建议比例（非硬性，按真实分布调整）：P0 约 3-5 条，P1 约 8-10 条，P2 余数。如果真找不出 20 条值得做的 gap，可以少写（最少 8 条），在报告里说明。

写入 `docs/BACKLOG.md` 的格式与当前文件保持一致：

- 文件顶部的操作说明段落原样保留（这次 repopulate 不是重写文件说明，只是刷新任务列表）。
- 每条 bullet 格式：`- **<slug>** — <Gap：现状 / 痛点>。**方向：** <期望动的东西>。Rubric §5.x。`
- `<slug>` 用 kebab-case，一眼看出改什么（例 `unify-project-query`、`split-project-json-blob`）。避免和 `docs/decisions/` 已有文件名撞 —— 重名就换一个角度命名。

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

如果 plan 需要只有用户能给的信息（专有 key、产品抉择等），**换下一条 backlog bullet**。被跳过的 bullet 保留在文件里不动，留给用户决定。

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

再加 `./gradlew ktlintCheck`（挂了用 `ktlintFormat` 自动修）。**红色不准 commit**。

### 6. 归档决策 + 删 backlog bullet

在 `docs/decisions/` 下**新建**一个文件：`docs/decisions/<YYYY-MM-DD>-<slug>.md`。**不要** 编辑或 prepend 到已有文件。

- 日期用当天（UTC 本地即可），与 commit 日期对齐。
- `<slug>` 直接复用 backlog bullet 的 `<slug>`（即 `**<slug>**` 里那段）。落不下来时按"`[^a-zA-Z0-9]+` 替换成 `-`、小写、截断到 ~60 字符"规则兜底。
- 命名冲突（同日同 slug）时加后缀 `-2`、`-3`。可用 `ls docs/decisions | grep <yyyy-mm-dd>-<slug>` 确认唯一。

同一步把本轮处理的 bullet 从 `docs/BACKLOG.md` 删掉：

- 只删这一条 bullet（整行，含前导 `- ` 和可能的紧跟空行），**不要**重写整个文件、不要修改顶部操作说明段落、不要调整剩余 bullet 的顺序。
- 被跳过的 bullet（plan 阶段判定踩红线或缺用户输入）保留不动。
- 用 `git diff docs/BACKLOG.md` 确认只减不增：diff 里只有被删那几行，没有 reorder / 重排。

文件内容保持既有 decisions 格式（内部 `## YYYY-MM-DD — 短标题` 头一仍要写，便于跨文件 grep / 聚合视图）：

```markdown
## YYYY-MM-DD — 短标题（VISION §X.Y rubric 轴）

Commit: `<shorthash>`

**Context.** 这个 gap 为何是本轮第一。对应的 rubric 轴 + 当前代码里观察到了什么。
如果参考了 OpenCode，引用具体文件。

**Decision.** 落地了什么。关键类型名、tool 名、文件。

**Alternatives considered.** 至少两个。每个：做了什么 + 为何被拒。
「业界共识」要具体到名字才算数（如「kotlinx.serialization 约定」、
「OpenCode tool-dispatch 形态」、「SemVer」）。

**Coverage.** 哪些测试覆盖了这个改动。

**Registration.** 动了哪几个装配点（或「无需注册 —— 纯 schema / 纯内部重构」）。
```

### 7. Commit + push

- 按具体文件名 stage（严禁 `git add -A`，CLAUDE.md 红线）。
- Commit 前缀按 `git log --oneline -20` 的惯例（当前：`feat(core):`、`docs(decisions):`、`fix(...)`、`refactor(...)`）。
- 两条 commit：
  1. `feat(...): <内容>`（或 `fix` / `refactor`）—— 代码改动。
  2. `docs(decisions): record choices for <feature> (<feat-commit-shorthash>)` —— **同一条 commit 同时包含**：新建的 `docs/decisions/<yyyy-mm-dd>-<slug>.md` 文件 **和** `docs/BACKLOG.md` 里那个 bullet 的删除。两个文件一起 stage、一起提交。
- `git push origin main`。
- 推送被拒（有人同时推过）→ `git pull --rebase origin main` → 如果 rebase 动了你的文件就再跑一遍验证 → 再推。rebase 冲突解不开 → **停下来报告**。绝不 `--force`、绝不对已推送的 commit `--amend`。

Backlog repopulate 那次的 commit 是独立的 `docs(backlog): …`（见 R 小节），不跟任何 feat/docs(decisions) pair 绑定。

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
2. 复制顺序模式第 3-7 步（plan → 实现 → 验证 → 归档决策 + 删 bullet → commit），仅一处调整：
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
5. 不带 `docs/decisions/<yyyy-mm-dd>-<slug>.md` 新文件 **+** `docs/BACKLOG.md` bullet 删除的 pair 不准 commit docs commit —— decision 文件**新建**，不得 append / 编辑已有条目；BACKLOG 只删不改。Repopulate 的 `docs(backlog)` commit 是例外（它重建 BACKLOG，不需要 decision pair）。
6. 绝不用 `--no-verify`，绝不用 `--force`，已推送的 commit 绝不 `--amend`，绝不用 `git add -A`。
7. 绝不绕过 CLAUDE.md 架构规则或反需求清单。如果一个 backlog bullet 必须绕才能做，**跳过它取下一条**（bullet 原样保留给用户裁决）。
8. 绝不把 OpenCode 的 Effect.js 结构搬过来。只抽行为。
9. 并行模式并发度永远 ≤ 4。超了静默 clamp。
10. 并行模式只选**互不重叠**的 bullet。重叠就静默缩减 N 并在报告里说明。
11. **Backlog 是权威任务源**。不凭空发明临时任务，不跳过 P0 直接做 P2。空 backlog → repopulate，不要绕过。
