---
name: iterate-gap
description: 自主挑选仓库与 VISION.md 之间优先级最高的 gap，plan → 实现 → 归档决策 → 推 main。参数 "<count> [parallel]"，例：/iterate-gap、/iterate-gap 3、/iterate-gap 4 parallel。执行期间零提问。
---

# iterate-gap — 自主补齐愿景 gap 的循环

挑当前仓库与北极星之间优先级最高的 gap，plan → 在 `main` 上实现 → 归档决策 → 推送。**执行期间不向用户提任何问题** —— 每一个决策都按 `docs/VISION.md` 与业界共识自主做出，理由落到 `docs/DECISIONS.md`，由用户事后异步审阅。

## 参数

按 `<count> [parallel]` 解析 skill 的参数字符串：

- 无参数 → `count=1`，顺序执行
- `<N>`（如 `3`）→ `count=N`，顺序执行（一个 cycle 做完再做下一个，每个开始前 rebase 一次）
- `<N> parallel`（如 `3 parallel`）→ `count=N`，用 git worktree 并行，等全部跑完后依次合回 `main`

上限：顺序模式 `count ∈ [1, 8]`，并行模式 `count ∈ [2, 4]`。参数格式有误时**默认退回 `1` 顺序执行**，不要问用户澄清。

## 操作约束（两种模式都适用）

- **分支目标**：`main`。不开 feature branch，不开 PR。（并行模式内部用临时 worktree 分支，但本次调用内部必须通过 merge+push 落回 `main`。）
- **提问**：零次。遇到决策点按 VISION + 业界共识自行决定，理由写进 `docs/DECISIONS.md`。如果真的卡在只有用户能回答的问题（专有 key、产品抉择、品牌偏好），**换一个 gap**，不要干等，也不要问。
- **先 plan 再实现**：每个 gap 必须有独立的 plan 步骤之后再动代码。
- **决策强制归档**：每次推送的 commit pair 都是 `feat(...)` + `docs(decisions): record choices for <feature> (<feat-hash>)`。

---

## 顺序模式（默认）

把下面的 cycle 重复 `count` 次。每次迭代之间 `git pull --rebase origin main`，确保下一轮的 gap 分析能看到自己刚推送的产出以及同事期间的新提交。如果某一轮分析出**没有合法候选**（例如 Core rubric 全部 "有"、OpenCode 也没有明显缺口），**提前停止**，不要制造凑数任务。最后报告诚实地写 `完成 N / 请求 M`。

### 1. 同步 main

```
git fetch origin
git pull --rebase origin main
```

工作树脏或 rebase 失败 → **停下来报告**，不要用丢弃修改的方式「抢救」。

### 2. Gap 分析

依次读：

1. `docs/VISION.md` §5（Gap-finding rubric）—— 5 个 rubric 小节就是打分轴。
2. `CLAUDE.md` 的「Platform priority — 当前阶段」小节定优先级；「Known incomplete」小节列出已承认的非回归项，别把它们当缺口重复报。
3. `docs/DECISIONS.md` 最近 ~15 条 —— 近期已做决策约束了不该再做一遍的内容。
4. `git log --oneline -20` —— 看最近落地了什么。

然后走读 `core/domain`、`core/tool/builtin`、`core/agent`、`core/session`、`core/compaction`、`core/permission`、`core/provider`、`core/bus` 以及各 app 的装配点，对每个 rubric 轴打分为 有 / 部分 / 无。

候选 gap 来自两处：

- VISION §5 中打分为「部分」/「无」的轴。
- OpenCode 行为对照差距，按 CLAUDE.md 「OpenCode as a 'runnable spec'」里列出的映射文件比，**只抽取行为，绝不抄 Effect.js 结构**。

产出 3-5 个具体候选 gap，每个一句话描述「diff 里会出现什么」。

### 3. 排优先级

按这个硬性顺序过滤（不可谈判）：

1. **平台优先级** —— Core 先。只有当 Core 的每个 rubric 轴都是「有」时，才考虑非 Core 的 gap。
2. **一等抽象 > patch**（VISION §5 第 3 步）—— 可复用抽象优于单 genre / 单特效的打补丁。
3. **短周期能闭环**（VISION §5 第 2 步）—— 同一优先级档内，选这轮就能闭环的那个。

选**恰好一个**。记录亚军候选给最后的报告用。

### 4. Plan

内部 plan（不要用 ExitPlanMode —— 用户说了不提问）。Plan 必须包含：

- 对应的 rubric 轴（例：「§5.2 —— 新效果接入成本」）。
- 会改哪些文件、新增哪些文件、在哪几个 `AppContainer` 注册新 tool。
- 这次改动必须守住哪些 CLAUDE.md 架构规则（`core/commonMain` 零平台依赖、Timeline 归 Core、`Tool<I, O>` 带 serializer + JSON Schema、`MediaPathResolver` 管路径、provider 中立的 `LlmEvent`、禁止 Effect.js 模式）。
- 跑哪个 `./gradlew` target 证明正确性（从 CLAUDE.md 的 Build & run 表里选最贴的那一个）。
- **反需求核查** —— 扫一遍 CLAUDE.md 的「Anti-requirements」清单。如果 plan 踩红线，**丢掉这个 plan 换一个 gap**，不要反过来 challenge 用户。

如果 plan 需要只有用户能给的信息，**换一个 gap**。

### 5. 实现

- `kotlinx.serialization` + `JsonConfig.default`（不要自定义 `Duration` serializer，不要 ad-hoc `Json` 实例）。
- `core/commonMain` 零平台依赖。
- 新 tool 必须在**全部 5 个** `AppContainer`（CLI / Desktop / Server / Android / iOS）都注册 —— 一个不能漏。
- SQLDelight 迁移设置 `PRAGMA user_version`；降级拒绝。

### 6. 验证

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

### 7. 归档决策

按仓库既有格式（最新的放最顶）把新条目 prepend 到 `docs/DECISIONS.md`：

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

### 8. Commit + push

- 按具体文件名 stage（严禁 `git add -A`，CLAUDE.md 红线）。
- Commit 前缀按 `git log --oneline -20` 的惯例（当前：`feat(core):`、`docs(decisions):`、`fix(...)`、`refactor(...)`）。
- 两条 commit：
  1. `feat(...): <内容>`（或 `fix` / `refactor`）—— 代码改动。
  2. `docs(decisions): record choices for <feature> (<feat-commit-shorthash>)` —— DECISIONS.md 条目。
- `git push origin main`。
- 推送被拒（有人同时推过）→ `git pull --rebase origin main` → 如果 rebase 动了你的文件就再跑一遍验证 → 再推。rebase 冲突解不开 → **停下来报告**。绝不 `--force`、绝不对已推送的 commit `--amend`。

### 9. 继续或收尾

还有剩余 iteration → 回到第 1 步。否则输出报告：

- 本次处理的 gap（每个一行：rubric 轴 + 摘要）。
- 推送的 commit（shorthash pair）。
- 跑了哪些测试 + 结果。
- 实现过程中意料之外的事。
- 最近一轮分析的亚军候选，用户可据此决定是否再次触发。

---

## 并行模式（`<N> parallel`，N ∈ [2, 4]）

在隔离的 git worktree 里并发跑 N 个 cycle，跑完后在**本次调用内部**依次合回 `main`。

### P1. 同步 + 分析 + 选出 N 个「互不重叠」的 gap

同顺序模式第 1-2 步，但第 3 步挑 **N 个互不重叠的 gap** 而非 1 个：

- 全部必须通过平台优先级过滤（第一个非 Core gap 进入批次的前提是前面每一个 Core 轴都已经「有」）。
- 「互不重叠」的定义：两个 gap 预期改动的文件集合除了 DECISIONS 以外不相交。主调度器在第 4 步 plan 里枚举每个 gap 的预期改动文件集，两两核查不相交。互不重叠的数量 < N → **静默缩减 N** 到最大的不重叠子集，最后报告提到缩减。（DECISIONS.md 会重叠，合理冲突会在 P4 机械化解决。）
- 如果只有 1 个互不重叠的 gap，**剩余名额 fallback 到顺序模式**。

### P2. 派发 N 个并行 agent

用 Agent 工具调用，`isolation: "worktree"`，**所有 N 次调用放在同一条消息里**，确保并发。

每个 sub-agent 的 prompt 自包含（它看不到本次对话上下文）。必须包含：

1. 分配给它的具体 gap —— rubric 轴 + 一句话 diff 摘要 + P1 plan 里给出的预期改动文件清单。
2. 复制顺序模式第 4-8 步（plan → 实现 → 验证 → 归档决策 → commit），并做两处调整：
   - **分支名**：在自动创建的 worktree 分支上 commit —— 不要改名、不要 checkout `main`、**不要 push**。分支保持未推送状态，由主调度器来合。
   - **决策文件路径**：sub-agent 把决策条目写到 staging 文件 `docs/decisions-pending/<yyyy-mm-dd>-<slug>.md`（目录不存在就创建），而不是直接编辑 `docs/DECISIONS.md`。这是消除并行分支之间唯一系统性冲突的关键。主调度器在 P4 步把这些 staging 折回 `DECISIONS.md`。
3. 它必须跑的 gradle 测试（从第 6 步的表选）。
4. 输出契约：最终消息必须包含 commit SHA、staging 决策文件名、一句话结果摘要、测试是否绿。失败时不要 commit，把错误返回。

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
3. `git rebase main <branch>` —— 有代码冲突在这里解决。
4. **把该分支的 staging 决策文件内联折进 DECISIONS.md**。把 `docs/decisions-pending/<yyyy-mm-dd>-<slug>.md` 的内容 prepend 到 `docs/DECISIONS.md` 顶部，删掉 staging 文件，然后在已 rebase 好的分支上追加一条 `docs(decisions): record choices for <feature> (<feat-hash>)` commit。（short-hash 指的是刚刚 rebase 到 main 的那条 feature commit。）
5. Fast-forward 合入 main：`git checkout main && git merge --ff-only <branch>`。
6. **立刻 `git push origin main`** —— 开始下一个分支之前就推。每个功能的 feat+decision commit pair 在下一次合并开始前已经在远端。
7. 冲突处理：
   - `docs/decisions-pending/*.md` —— 每个文件名唯一，理论上不冲突；真冲突就两个都留。
   - 代码冲突 —— **停下来报告**。不强行解。剩余没合的分支保留原状让用户检查。
8. 推送被拒 → `git pull --rebase origin main` → 再推。解不开 → **停下来报告**。
9. 下一个分支：回到第 1 步。直到队列空。

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

1. **一个 cycle 内零提问**。卡住 → 换 gap 或停下。
2. 最终状态落在 `main`。并行模式的中间分支要么合入、要么作为遗留项报告，绝不静默丢弃。
3. **Commit → push，永远配对**。本地 `main` 一出现新 commit（顺序模式的 commit pair / 并行模式合并完的一个分支），**立刻** `git push origin main` 再开下一个 cycle / 下一个合并。绝不能让本次调用结束时本地 `main` 有未推送 commit。
4. 绝不跳过 `./gradlew ktlintCheck`。lint profile 只做 hygiene，报错就是真有问题。
5. 不带 DECISIONS.md 条目不准 commit（顺序模式直接写进去，并行模式写 staging）—— 极少数纯 typo 修复除外，而这类改动几乎不会成为「本轮第一 gap」。
6. 绝不用 `--no-verify`，绝不用 `--force`，已推送的 commit 绝不 `--amend`，绝不用 `git add -A`。
7. 绝不绕过 CLAUDE.md 架构规则或反需求清单。如果一个 gap 必须绕才能做，**换一个 gap**。
8. 绝不把 OpenCode 的 Effect.js 结构搬过来。只抽行为。
9. 并行模式并发度永远 ≤ 4。超了静默 clamp。
10. 并行模式只选**互不重叠**的 gap。重叠就静默缩减 N 并在报告里说明。
