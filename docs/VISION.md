# Talevia — 愿景与北极星

## 本文档定位

**这是 Talevia 的第一性原理。**

项目里其他文档——`CLAUDE.md`（`AGENTS.md` 是它的 symlink）、各 `*_INTEGRATION.md`——都是**服务于本文档**的下游约束。冲突时以本文档为准；其他文档要调整以贴合本文档，而不是反过来。`CLAUDE.md` 里的架构规则与反需求清单记录的是为了实现本文档的愿景在**当前阶段**接受的工程边界，本身不是第一性原理。

这是北极星文档——要去哪里、为什么去。不是操作手册（见 `CLAUDE.md`）。

读这篇是为了在面对「这个能力要不要加 / 怎么设计」的判断时，能回到方向上对齐，而不是靠猜。

对 coding agent：在「找缺口 → 补能力 → 迭代」的循环里，**先读本文判断方向**，再读 `CLAUDE.md` 判断边界与现状，最后读代码找差距。

---

## 发现不符 → 必须 challenge

**如果手头的任务会把系统引向和本文档不一致的方向，必须 challenge，不要沉默推进。**

这条对**所有读到这里的人**生效——用户、coding agent、reviewer 都一样。

典型冲突信号：

- 任务要求在 Core 里硬编码某一个 genre 的 source schema
- 任务要求把 Timeline 做成各端各自维护的副本
- 任务要求把 AIGC 做成一次性 prompt 调用，seed / 模型版本不落盘
- 任务要求给小白 / 专业用户做两套独立管道
- 任务要求把「全量重渲染」作为唯一路径，绕过 DAG / 增量编译
- 加新效果要改 Core 而不是注册一个 `Tool`
- 任务要求把工程状态藏进数据库 / 二进制 blob / 不可读的格式（外部 git 看不懂改动），或把机器本地缓存混进可入库内容里

发现这类冲突时，**先停下来把问题显式提出来**——在对话里、在 PR 描述里、在代码注释里——而不是按任务字面意思默默执行。

结果只有两种：要么任务调整贴近愿景，要么愿景修正容纳新现实。两者都必须被显式讨论，不能被绕过。**沉默推进 = 系统悄悄偏离北极星，这是最贵的一种 bug**。

---

## 1. 北极星

**像用 Claude Code 写代码一样做音视频。**

用户用自然语言描述创作意图，agent 像 coding agent 那样读懂当前 project 状态、调度工具、迭代产物。工具集既包括传统剪辑与特效渲染（cut / stitch / filter / transition / OpenGL shader / 合成），也包括 AIGC（图 / 视 / 乐 / 声生成）和 ML 加工（字幕 / 抠像 / 调色 / 超分）。

**任何用户想要的效果，系统都能用最合适的手段达成**——传统引擎够用就用传统引擎，AIGC 才能做就调 AIGC，不教条地偏袒任一条路径。

**UX 主张**：用户只表达「想要什么」，系统负责「怎么做」。

- "把这段调得更戏剧化" → agent 全自动编排
- "第 3 秒第 2 帧的 LUT 强度降到 0.4" → 像 review diff 一样精修
- 同一份底层状态，两种操作深度，之间无缝过渡

---

## 2. 构建系统模型

把音视频创作建模成一个**构建系统**：

```
Source                         Compiler                        Artifact
────────                       ──────────                      ────────
结构化创作素材       ──────→   剪辑引擎 + AIGC + ML    ──────→   成片 / 片段
```

### Source 是 genre-dependent 的

系统**不硬编码任何一套 source schema**。不同创作类型有不同的「源」：

| Genre | Source 典型结构 |
|---|---|
| 叙事短片 | 人设 / 世界观 / 故事线 / 分镜 / 对白 |
| Vlog / 日常 | 原始素材库 + 编辑意图 + 风格预设 |
| 音乐 MV | 曲目 + 视觉概念 + 表演素材 |
| 教学 / 口播 | 脚本 + B-roll + 品牌规范 |
| 广告 / 营销 | 品牌手册 + 产品规格 + 目标受众（可批量产出变体） |

上表是例证，不是穷举。新 genre 要能通过扩展 source schema 支持，而不是改 Core。

### Compiler 是可插拔的能力集合

所有「把 source 变成 artifact」的手段，对 agent 来说都是同构的 `Tool<I, O>`：

- **传统引擎**：FFmpeg / AVFoundation / Media3 的 cut / stitch / filter / transition
- **特效渲染**：OpenGL / Metal shader、合成、粒子、遮罩
- **AIGC**：文生图 / 图生视频 / 音乐生成 / TTS / 声音克隆
- **ML 加工**：字幕识别、抠像、超分、自动调色、去噪

Agent 根据意图和约束选出一条执行路径——这和 coding agent 把「给我加个登录」拆成读文件、改代码、跑测试是一回事。

### Artifact 是可复现的确定性产物

相同 source + 相同 toolchain 版本 → 相同 artifact。这是下一节要解决的核心问题。

---

## 3. 核心工程赌注

### 3.1 把 AIGC 驯服成「随机编译器」

AIGC 的默认行为是**不可复现**：两次同样的 prompt 产物不同，模型升级一次视觉基线就漂了。要让音视频创作服从工程学，这件事必须当一等问题解决：

- **seed 显式**，不是默认随机。
- **模型与版本锁定**——类似 `package-lock.json`，Project 里记录本次编译用了哪个模型、哪个 checkpoint、哪套参数。
- **产物可 pin**：关键镜头的 AIGC 产物能固化为 artifact，避免每次编译重抽卡；只有 source 或 toolchain 变化时才失效。
- **随机性有边界**：哪些节点允许不确定（"多来几个选项"），哪些节点要求严格复现（"保持主角长相一致"），由 source 显式标注。

### 3.2 依赖 DAG 和增量编译

音视频项目的自然结构是个 DAG：成片依赖场景，场景依赖镜头，镜头依赖角色 / 素材 / 特效，角色依赖设定。任何 source 改动，要能算出下游哪些 artifact stale、哪些仍有效——**只重编译必要的部分**，而不是每次从头渲染。

这直接决定：

- 改人设要能传导到所有引用该人设的镜头（"refactor"）
- 切换 LUT 只重编译调色 pass，不重新抽卡 AIGC 素材
- 产物缓存按 source hash + toolchain version 索引

### 3.3 跨镜头一致性必须在 source 层表达

AIGC 最硬的问题是同一个角色在 50 个镜头里长得不像同一个人。指望 compiler（模型本身）自己保持一致，现阶段做不到。所以**一致性必须在 source 里显式表达**——character reference、style bible、LoRA 绑定、color palette 等——让 compiler 有足够约束可参考。

这一层 source 语言的设计是 Talevia 最硬的护城河。设计得好，"改一下主角发型"是一次 refactor；设计得不好，就退化成一个包了壳的 prompt 管理器。

### 3.4 Project / Timeline 是 codebase

所有「工程学能力」都要求 Project / Timeline 具备 codebase 性质：

- **可读**：agent 能 query 当前状态（有哪些 track、哪个 clip 用了哪个 asset、哪个节点 stale）
- **可 diff**：两个版本之间的变化可精确表达 —— 不只是系统内部能算 diff，**外部工具（git diff / PR review / 文本编辑器）也能直接看懂**。工程文件持久化为人类可读、行级稳定的文本格式，落到磁盘上像源代码一样能入版本控制
- **可版本化**：历史可追溯、可回滚、可分支 —— 既包括系统内的 snapshot / fork，也包括**用 git 等外部 VCS 直接管理工程文件**（`git log`、`git checkout <commit>`、`git revert` 都该 just work）
- **可协作**：多人能在同一个 project 上分别推进、merge 改动 —— 这要求工程目录天然 git-friendly：机器本地状态（缓存 / 中间渲染产物 / 个人 settings）从可入库内容里隔离出去，AIGC 产物随工程文件走（git push 给协作者就能 reproduce export，不需要重跑 provider 烧钱），合并冲突可读
- **可组合**：片段 / 模板 / 特效 / 角色可跨 project 复用

这是 Core 层（`core/domain`, `core/tool`, `core/agent`, `core/session`）必须兜底提供的基础设施。**外部 VCS 兼容是硬约束**，不是 nice-to-have —— 一旦 project 落到磁盘上别的工具看不懂、改一行触发千行 diff、或本地状态污染了 commit，"codebase 性质"就退化成了系统内部的私事，多人协作和长期维护立刻塌方。

---

## 4. 双用户张力

Talevia 同时服务两类用户，**他们对 agent 自主度和可控粒度的要求相反**：

| | 小白用户 | 专业剪辑师 |
|---|---|---|
| 诉求 | "给我做一个毕业 vlog" | "把第 12 秒的高光压 0.3 dB" |
| 对 agent 期待 | 高度自主，替我决策 | 精准执行，不越权猜意图 |
| 对 source 期待 | 能自动推断 / 隐藏 | 可直接编辑每一个字段 |
| 对 compiler 期待 | 透明、选最合适的 | 可指定 / 可替换 / 可调参 |

**不做两套系统。** 同一个 Project / Timeline / Tool Registry，通过**操作深度**区分：

- 小白路径：用户说高层意图，agent 填充 source、编排 compiler、呈现 artifact，用户 review 成片
- 专家路径：用户直接编辑 source、override 单步编译、介入 agent 决策
- 两条路径无缝过渡：小白做了一半不满意可以进到专家模式精修；专家也可以把某些环节交还给 agent

**这条会被频繁挑战。** 每次遇到「这个功能小白根本用不上 / 专家不屑用」的冲突，回到这里判断。如果发现在为某一类用户做第二套管道，那是系统分裂的信号。

---

## 5. Gap-finding rubric（给自主迭代的 agent）

这不是 TODO 列表——TODO 会过时。这是一套**判断题**，在「找缺口 → 补能力」循环里用来自查当前代码离北极星多远。每轮迭代跑一遍。

**两条 gap-source 并行、互不替代**：

1. **本节 §5.1–§5.6 判断题** —— 视频编辑器领域的缺口（source schema / compiler 覆盖 / artifact 复现 / agent UX / 跨镜头一致性 / 系统健康）。
2. **OpenCode 行为对照** —— agent 基础设施的缺口（streaming tool 输出、compaction 细节、permission 粒度、bus 事件覆盖等）。映射文件见 `CLAUDE.md` 的 "OpenCode as a 'runnable spec'" 一节。

两条都要跑，不要用其中一个替代另一个。领域能力和 agent infra 同步推进才能避免"功能很全但 agent 跑不顺"或"agent 很顺但剪辑能力空洞"的失衡。

### 5.1 Source 层

- 当前有没有「结构化 source」概念，还是只有一个被直接编辑的 Timeline？
- 改一个 source 节点（比如角色设定），下游哪些 clip / scene / artifact 会被标为 stale？这个关系是显式的吗？
- Source 能不能序列化、版本化、跨 project 复用？
- 新 genre（例如从叙事片扩到 MV）要加 source schema，需要改 Core 还是只需扩展？

### 5.2 Compiler 层

- 工具集覆盖面：传统引擎 / AIGC / ML / 特效渲染，各有多少工具？agent 能不能在一次意图下混合调度？
- 新效果接入成本：加一个新特效（例如粒子烟雾）要**跨几层抽象**？只注册一个 `Tool`？还是要改 engine 契约 / `core.domain` / 5 个 `AppContainer`？"改几个文件"是误导指标（文件可以少但耦合很深），"要跨几层"才是。够不够像注册 Tool 那样低？
- AIGC 产物是否可 pin？seed 和模型版本是否被记录到 Project 状态里？
- 相同 source + 相同 toolchain 重跑一次，产物是否 bit-identical 或感知一致？

### 5.3 Artifact / 编译过程

- 增量编译：只改一处 source，有没有只重算下游？还是每次全量渲染？
- 产物缓存：cache key 是什么（source hash？toolchain version？环境？）？
- Lockfile：Project 里有没有文件记录本次编译绑定的模型版本、seed、参数？
- 跨机复现：把工程文件 + 用户原始素材拷到另一台机器，重跑 export 是不是 bit-identical / 至少感知一致？AIGC 产物随工程文件走（不需要重跑 provider 烧钱），还是只能在生成它的那台机器上 reproduce？

### 5.4 Agent / UX

- 小白路径：给 agent 一句高层意图（"做个毕业 vlog"），它能不能自主推断 source、调度工具、产出可看的初稿？
- 专家路径：用户能不能直接编辑 source 的每个字段、override 某一步编译、在 agent 的某个决策点接管？
- 两条路径共享的是同一份底层状态吗？还是各自维护了一套？
- 多人协作：两个用户在同一个 project 上分别推进，工程文件天然 git-friendly 吗？`git diff` 显示的是行级可读改动还是一坨整文件重写？合并冲突（同时改了相邻 clip / 同一段 source 节点）能不能可读地呈现、能不能用通用 merge 工具解？机器本地状态（缓存 / 中间渲染）有没有从可入库内容里隔离开？

### 5.5 跨镜头 / 跨片段一致性

（与 §5.1 重叠：character reference / style bible 同时是 source 层的一等概念 + 一致性传播约束。别拆成两个独立任务，按"源层能不能表达 + 约束能不能真正传导"合并评估。）

- Source 层有没有 character reference / style bible / brand palette 这类跨节点约束的一等表达？
- 这些约束有没有真的传导到 AIGC 调用的 prompt / 参数 / LoRA 里？
- 改一次全局风格（冷色调 → 暖色调），下游传导成本是多少？

### 5.6 系统健康 / 技术债

rubric 前 5 节驱动**新增**，这一节驱动**收敛**。Core 只做加法不做减法的系统必然劣化 —— 工具集膨胀、LLM 每 turn 付的 spec token 非线性累积、近似工具挤占 context、dead code 让新人读代码时踩空。这些信号必须和 feature gap **竞争同一优先级窗口**，不做额外打折 —— 一个已经过热的系统继续加新功能只会加速塌方。

- 工具数量：`core/tool/builtin/*` 是否净增长？同一 area 下有没有近似工具（`list_X*` / `find_X*` / `describe_X*` / `Define* + Update*` 成对）该合并成一个带 filter / sort / limit 的 query primitive？
- Project 字段膨胀：`Project.kt` 是否在追加 `List<X>` / `Map<K, V>` 这类 append-only 语义字段？（append-only 的内容应拆独立 SQLDelight 表，不该让每次 tool 调用都整块 re-encode。）
- 长文件：`core/src/commonMain/kotlin` 下有没有超 500 行的 Kotlin 文件？超 800 行是强制拆分信号。
- 被跳过的测试：`@Ignore` / `@Disabled` / `.skip(` 的数量 —— 每一条都是"要么修要么删"的债。
- TODO / FIXME / HACK 净增长 > 0 就要警觉，不是正常迭代残留。
- `@Deprecated` 超过 1 轮迭代仍未移除 —— 代码里有两份实现比保留还差。
- Dead code：大重构之后（如 `baad43f` 文件化 ProjectStore）仍挂在 schema 里但没人消费的表 / 字段 / 类，应主动删除而不是等"以后再说"。

具体扫描脚本由下游 loop（如 `iterate-gap` skill）负责；本节定义的是**判断轴本身**，保证无论用哪个循环框架，"收敛"都是第一性要求而不是可选的工具函数。

### 怎么用这份 rubric

每轮迭代的流程：

1. **先校准"现状"再打分**：读最近 ~20 个 `docs/decisions/` 文件（`ls docs/decisions | sort -r | head -20`）+ `git log --oneline -30` + `CLAUDE.md` 的 "Known incomplete" 小节。这一步是为了避免把**刚合入的能力**或**已承认的非回归项**再次列为 gap —— 系统最贵的误报就是"反复发现同一个已经解决的缺口"。

2. **逐轴打分**：对照 §5.1–§5.6 每一节的判断题，读当前代码（`core/domain`, `core/tool/builtin`, `core/agent`, `core/session`, `core/compaction`, `core/permission`, `core/provider`, `core/bus`, 各 app 装配点）打 有 / 部分 / 无。§5.6 的技术债条目与前 5 节 feature gap 一视同仁进入候选池。另外跑一次 OpenCode 对照（见本节开头"两条 gap-source 并行"）作为独立候选来源。

3. **按顺序过滤出 2–3 条实现任务**：

   1. **平台权重前置** —— Core 轴打"部分 / 无"的 gap 一律进优先档；非 Core 平台 gap 只有在 Core 每个轴都是"有"时才获得同级优先。这是把 `CLAUDE.md` "Platform priority" 提前到**候选产生阶段**而不是末端过滤 —— 否则 top 候选全是 iOS / Android 专属时会让 Core 饥饿。
   2. **离 §3 四大核心赌注最近的优先** —— 同档之内，优先选直接推进 ①AIGC 驯服成随机编译器 ②DAG 与增量编译 ③跨镜头一致性在 source 层表达 ④Project / Timeline 具备 codebase 性质（含外部 VCS 可读可 diff 可协作）的那条。
   3. **一等抽象 > patch** —— 同赌注内，优先补可复用抽象，不要为某个具体 genre / 效果 patch。patch 不沉淀为系统能力。
   4. **反需求硬闸** —— 候选逐条过 `CLAUDE.md` 的 "Anti-requirements" 清单。命中任何一条**换下一条候选**，不允许"这次例外一下"的辩护 —— 历史上系统劣化几乎全部来自连续的单次例外。
   5. **短周期可闭环** —— 单 PR 能闭环 + `CLAUDE.md` Build & run 表的 "Every target + every test" 命令全绿 + `ktlintCheck` 绿。一个 iteration 做不完的拆开重选。

   经过这 5 道过滤后剩下的 2–3 条就是本轮实现任务。

4. **实现并归档 rubric delta**：每条候选对应的决策文件里写明本次动了哪个 rubric 轴、打分从什么变到什么（例："§5.1 source-layer 序列化 无 → 部分"）。rubric delta 是**可 grep 的系统演进曲线** —— 不留这条就没人能看出系统真在朝北极星收敛，还是在原地打转。

5. **回到 §1 北极星重读一遍，校准方向没跑偏**。特别注意 §4 双用户张力 —— 本轮是不是无意中为某一类用户做了第二套管道。

---

## 6. 具体 genre 举例

本节让概念更具体，**不代表系统优先做这些**。

### 叙事短片（source schema 的一个完整实例）

- **Source**：人物设定（外貌 / 性格 / 弧光）、世界观（地理 / 时代 / 规则）、故事线（大纲 / 分场）、镜头脚本（景别 / 运镜 / 对白）、关键帧参考图
- **Compiler**：文生图产 character reference → 图生视频产动作镜头 → 传统剪辑接片 → AIGC 音乐配乐 → TTS 对白 → 字幕
- **Artifact**：成片 + 每个镜头可独立替换 / 重生成的中间产物
- **Refactor 示例**：修改主角发色 → 传导到 character reference → 引用该 reference 的所有镜头标记 stale → 只重编译这些镜头

早期讨论里的「角色 / 世界观 / 故事线」就是这个例子，它是 source schema 的一个 instance，**不是系统的全部**。

### Vlog / 日常（同一套底座，不同 source）

- Source：原始拍摄素材库 + 编辑意图（"记录毕业"）+ 风格预设（温暖 / 快节奏）
- Compiler：ML 选片 → 节奏剪辑 → AIGC 配乐匹配 → 自动字幕 → LUT 调色
- Artifact：1 分钟成片，可重生成变体（30 秒 / 竖版）

---

## 附：本文档与其他文档的关系

- `VISION.md`（本文）：**去哪里 / 为什么**——愿景、北极星、工程赌注、判断依据
- `CLAUDE.md`：**怎么在这个仓库里干活**——构建命令、模块布局、架构规则、反需求、已知残缺

读序：VISION → CLAUDE → 代码。
