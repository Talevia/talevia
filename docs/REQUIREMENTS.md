# Talevia — 需求与原则

## 本文档定位

这是项目启动的输入。本文档只定义**原则和边界**——具体的技术方案、接口设计、数据结构、工具清单、库选型、目录结构，全部由你在阅读 OpenCode 源码后自己起草。

如果你在本文档里找不到某个具体答案，**默认去读 OpenCode**（见第 5 节），不要向文档要答案。

---

## 1. 产品

Talevia 是一个跨平台的 AI 驱动视频编辑工具。用户用自然语言描述想要的编辑效果（剪辑、拼接、滤镜、调色、字幕、转场、配乐等），Agent 理解意图并调度原生视频处理能力完成。

目标用户是不会用专业视频软件、但想产出高质量短视频内容的人。

---

## 2. 目标平台

必须同时支持：**iOS、Android、Desktop（macOS/Windows/Linux）、Server**。

- Agent 核心逻辑**主要在终端设备本地运行**，不强制依赖 server
- 同时支持部署为 server（用于云端协作、多端同步等未来场景）
- UI 全部走**各端原生**，不追求跨端 UI 一致性

---

## 3. 架构原则

### 3.1 三层分层（硬约束）

1. **UI 层** — 各端原生，不共享
2. **Agent Core 层** — 跨平台共享代码，**不得依赖任何平台 API**
3. **领域能力层**（视频处理等）— 各端原生实现

### 3.2 接口反转

Agent Core 只定义**抽象接口**，各端实现并在启动时注入。Core 不 import AVFoundation / MediaCodec / FFmpeg。

### 3.3 UI 与 Core 解耦

Core 对外发布**数据流与事件流**，UI 消费并渲染。Core 不知道 UI 存在。

### 3.4 Provider-Agnostic

LLM provider 必须是抽象的，多家可替换，**从第一天起就支持多 provider**。不要为任何单一 provider 的特性牺牲抽象。

### 3.5 领域与通用的分野

视频编辑领域（Timeline、Clip、Track、MediaAsset 等）的数据模型是 Talevia 的**核心差异化**——这部分你需要自己设计，不要照搬 OpenCode 的"文件系统 + 代码"隐喻。视频工作单元不是文件，是时间轴上的片段。

Agent loop、Provider 抽象、Tool Registry、Session 管理、Permission、Compaction 这些**通用能力**，参照 OpenCode。

---

## 4. 技术选型（已决策，不要重新评估）

- **Agent Core 语言与跨平台**：Kotlin Multiplatform (KMP)
- **iOS 互操作辅助**：SKIE (Touchlab)
- **视频处理**：各端原生（iOS AVFoundation、Android MediaCodec/Media3、Desktop/Server FFmpeg）

以上是硬约束。其他所有技术选择（HTTP、DB、DI、序列化、日志、测试等）由你在技术方案里自主决定，给出理由即可。

---

## 5. 与 OpenCode 的关系

**OpenCode 是参考实现，不是依赖。**

- 不 fork、不翻译、不 port 代码
- 用 Kotlin 独立实现，在**协议与行为层面**与 OpenCode 对齐
- 把 OpenCode 当作"可运行的规范文档"——要理解一个能力该怎么工作，去读它的代码

### 阅读建议
OpenCode 仓库：`/Volumes/Code/CodingAgent/opencode`

建议的阅读顺序：agent loop → provider 抽象 → tool 系统 → session/message 模型 → compaction → permission。

### 忽略什么
OpenCode 正在做大规模 Effect.js 架构迁移（Service/Layer/namespace 重构）。**所有"代码组织方式"的东西忽略**——你只抽取**行为语义**。具体说：
- `refactor: unwrap X namespace` / `effectify X service` 等 commit 忽略
- Effect.js 的 Service/Layer/Context 模式不要照搬
- OpenCode 的 TUI、Web UI、基础设施、SaaS 后台都不看

### 持续跟进
后续 OpenCode 有新版本时，只关心**协议和行为语义**的变化（新 provider 接入模式、LLM 协议升级、message part 协议、compaction 策略调整）。结构性重构一律忽略。

---

## 6. 反需求（不要做）

- ❌ 不要引入 Effect.js 风格的 Service/Layer/依赖管理——Kotlin 有自己的惯用法
- ❌ 不要把 UI 代码放进 KMP shared module
- ❌ 不要让 Agent Core 依赖任何平台 API
- ❌ 不要在 Agent Core 做视频编解码
- ❌ 不要用 Electron / WebView 做桌面端
- ❌ 不要 fork OpenCode 直接翻译成 Kotlin
- ❌ 不要为假设的未来需求设计（multi-agent coordinator、IDE bridge、插件市场等）除非有明确需求
- ❌ 不要为单一 LLM provider 优化到牺牲抽象

---

## 7. 你的任务

阅读本文档 + OpenCode 源码后，产出一份**技术方案**，内容由你决定——典型应该包括：模块划分、核心接口的设计思路、Timeline 等领域模型的建模思路、跨端集成方式、最小可行路径。

写技术方案时，每一个具体决策都应该能追溯到：
- 本文档的某条原则，或
- OpenCode 的某处实现的借鉴，或
- 视频编辑领域的特性要求

如果三者都不沾，那这个决策大概不需要现在做。
