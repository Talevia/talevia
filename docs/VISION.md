# Talevia — 愿景与北极星

## 本文档定位

这是 Talevia 的**北极星文档**——要去哪里、为什么去。不是原则（见 `REQUIREMENTS.md`），不是架构（见 `ARCHITECTURE.md`）。

读这篇是为了在面对"这个能力要不要加 / 怎么设计"的判断时，能回到方向上对齐，而不是靠猜。

对 coding agent：在「找缺口 → 补能力 → 迭代」的循环里，**先读本文判断方向**，再读 `REQUIREMENTS.md` 判断边界，再读 `ARCHITECTURE.md` 判断现状，最后读代码找差距。

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
- **可 diff**：两个版本之间的变化可精确表达
- **可版本化**：历史可追溯、可回滚、可分支
- **可组合**：片段 / 模板 / 特效 / 角色可跨 project 复用

这是 Core 层（`core/domain`, `core/tool`, `core/agent`, `core/session`）必须兜底提供的基础设施。

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

### 5.1 Source 层

- 当前有没有「结构化 source」概念，还是只有一个被直接编辑的 Timeline？
- 改一个 source 节点（比如角色设定），下游哪些 clip / scene / artifact 会被标为 stale？这个关系是显式的吗？
- Source 能不能序列化、版本化、跨 project 复用？
- 新 genre（例如从叙事片扩到 MV）要加 source schema，需要改 Core 还是只需扩展？

### 5.2 Compiler 层

- 工具集覆盖面：传统引擎 / AIGC / ML / 特效渲染，各有多少工具？agent 能不能在一次意图下混合调度？
- 新效果接入成本：加一个新特效（例如粒子烟雾）需要改几个文件？够不够像「注册一个 Tool」那样低？
- AIGC 产物是否可 pin？seed 和模型版本是否被记录到 Project 状态里？
- 相同 source + 相同 toolchain 重跑一次，产物是否 bit-identical 或感知一致？

### 5.3 Artifact / 编译过程

- 增量编译：只改一处 source，有没有只重算下游？还是每次全量渲染？
- 产物缓存：cache key 是什么（source hash？toolchain version？环境？）？
- Lockfile：Project 里有没有文件记录本次编译绑定的模型版本、seed、参数？

### 5.4 Agent / UX

- 小白路径：给 agent 一句高层意图（"做个毕业 vlog"），它能不能自主推断 source、调度工具、产出可看的初稿？
- 专家路径：用户能不能直接编辑 source 的每个字段、override 某一步编译、在 agent 的某个决策点接管？
- 两条路径共享的是同一份底层状态吗？还是各自维护了一套？

### 5.5 跨镜头 / 跨片段一致性

- Source 层有没有 character reference / style bible / brand palette 这类跨节点约束的一等表达？
- 这些约束有没有真的传导到 AIGC 调用的 prompt / 参数 / LoRA 里？
- 改一次全局风格（冷色调 → 暖色调），下游传导成本是多少？

### 怎么用这份 rubric

每轮迭代的流程：

1. 对照每一节的判断题，读当前代码（`core/domain`, `core/tool/builtin`, `core/agent`, 各 app 装配点）给每项打分（有 / 部分 / 无）。
2. 找出离北极星最远、又能在短周期内闭环的 2-3 项，作为下一轮实现任务。
3. 优先补**一等抽象**，不要为某个具体 genre / 效果 patch——patch 不会沉淀成系统能力。
4. 每轮结束，回到 §1 北极星重读一遍，校准方向没跑偏。

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
- `REQUIREMENTS.md`：**不能做什么**——原则、硬约束、反需求
- `ARCHITECTURE.md`：**现在长什么样**——模块划分、接口设计、实现路线
- `CLAUDE.md`：**怎么在这个仓库里干活**——构建命令、架构规则快查、已知残缺

读序：VISION → REQUIREMENTS → ARCHITECTURE → 代码。
