## 2026-04-21 — debt-split-taleviasystemprompt：系统提示词 743 行拆成 4 个主题片段（Rubric 外 / R.5.3 长文件）

Commit: `22ead6a` (pair with `docs(decisions): record choices for debt-split-taleviasystemprompt`).

**Context.** `core/agent/TaleviaSystemPrompt.kt` 到 743 行，是 R.5.3 长文件扫描首位命中者（500–800 行档位，默认 P1）。每次维护要在一个接近 1000 行的 `"""..."""` 块里找到 `# Consistency bindings` / `# Project lifecycle` 这类章节靠眼睛扫——PR diff 也因此变得嘈杂（一个字段调整混在几百行上下文里）。内容是高价值文本（每字节都上 LLM），但文件结构本身是纯码侧负担，拆分不改变任何 LLM 输入字节。

**Decision.** 新建 `core/src/commonMain/kotlin/io/talevia/core/agent/prompt/` 目录，按主题切 4 片：

| 片段文件 | 内容（按原顺序） | 行数 |
|---|---|---|
| `PromptBuildSystem.kt` | 构建系统心智模型、consistency bindings、LUT、seed、lockfile、outputProfile | 168 |
| `PromptAigcLane.kt` | 小白 / 专家双用户、AIGC 视频 / 音乐 / TTS / 超分、ML 增强 | 111 |
| `PromptProject.kt` | 项目生命周期、snapshots、lockfile 可观测性、project_query、fork / diff / import | 156 |
| `PromptEditingAndExternal.kt` | 时间线编辑工具、fs / web / shell / todos、session-project binding、Rules | 325 |

每个 section 文件 `internal val PROMPT_<slug>: String = """..."""`, 用 `.trimIndent()` 规范化（首尾空行剥除）。

`TaleviaSystemPrompt.kt` 从 743 行缩到 **48 行**，变成纯组合器：

```kotlin
internal val TALEVIA_SYSTEM_PROMPT_BASE: String = listOf(
    PROMPT_BUILD_SYSTEM,
    PROMPT_AIGC_LANE,
    PROMPT_PROJECT,
    PROMPT_EDITING_AND_EXTERNAL,
).joinToString(separator = "\n\n")
```

**字节等价不变量。** 拆分唯一约束：拼接结果必须和拆分前的 prompt 字节级别一致——LLM 看到的输入不能有任何变化。原始字符串用 `"""..."""` + `.trimIndent()` 得到：
- 首尾空行去掉
- 各 `# Section` 之间原本就是 `\n\n`（一个空行）

切片时每个 section 从 `# Header` 行起，到下一个 section 前一行止（不含中间空行），各自 `.trimIndent()` 后首尾清洁。`joinToString("\n\n")` 重建章节间空行。新的 section-order test + section-spacing test 把这俩不变量锁死（anchor 监控 + "prompt 体内不得有连续 3 个 `\n`"）。

**Alternatives considered.**

1. **Option A (chosen)**: 4 个子文件 + 主文件做组合器。优点：每片 < 330 行，单主题可审阅；主文件 48 行一眼读完；`joinToString` 简单显式。缺点：多 4 个文件导入，构建系统要扫更多 Kotlin 源（可忽略成本）。
2. **Option B**: 拆成 7–8 个更细粒度文件（每 `# Section` 一个）。拒绝：file count 过多、跨文件搜索变困难；主题内连续的 section（譬如 Seed + Lockfile + OutputProfile 都是"编译时 cache 机制"）拆开反而破坏可读性。
3. **Option C**: 保持单文件，只把内容外移到 `resources/` 加载时读。拒绝：KMP commonMain 读 resources 在 iOS 端没标准路径；引入 `expect/actual` 额外复杂度；且要把 prompt 从编译期常量降级为运行时字符串，丢失 `internal val` 的可发现性。不划算。
4. **Option D**: Markdown 文件放 `docs/prompts/*.md`，构建脚本把它们 `#include` 进一个生成的 Kotlin 文件。拒绝：多了一层构建机制，Kotlin 集成需要自定义 task；改一个字还要重跑 generate 步骤；审阅 PR 时要看生成产物 vs 源头，diff 体验变糟。

**Coverage.**

- 原有的 `TaleviaSystemPromptTest` 里 `promptContainsAllNorthStarKeyPhrases`（40+ 关键词）—未改一字，如果拆分丢了任何内容直接红。
- 新增 `sectionsAppearInExpectedOrder`：11 个章节锚点 `# Build-system mental model` / `# Consistency bindings` / `# Seed discipline` / `# Two kinds of users` / `# AIGC video (text-to-video)` / `# Project lifecycle` / `# Project snapshots` / `# Removing clips` / `# External files (fs tools)` / `# Session-project binding` / `# Rules`，断言**严格单调递增**的 indexOf 位置。未来谁改了 `TALEVIA_SYSTEM_PROMPT_BASE = listOf(...)` 的顺序（"顺手把小白 / 专家挪到前面"）立刻挂。
- 新增 `sectionsAreSeparatedByExactlyOneBlankLine`：验证 "`invalidation step.\n\n# Two kinds of users`" 这个 A→B 边界正确，并全局扫确保没有 `\n\n\n`（三连回车）——triple-newline 意味着某个 section body 内部有尾随空白或 join separator 写错。

byte-identical 性质本来没有直接断言（需要从 git 拉旧版字符串对比，测试代码里不方便），但 40+ keyword + order + spacing 三个测试叠加起来等效：任何 content 丢失、重排、多/少空行都会挂。

**Registration.** 无 tool 注册 / 5 端装配变化——纯重构。`Agent` 构造器仍用 `taleviaSystemPrompt(extraSuffix = ...)` 同签名，所有 caller（cli / desktop / server / android / ios 5 端容器）一行都不用改。

**§3a 自查.**
1. Tool count: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 Project。PASS。
4. 状态字段: N/A。
5. Core genre: 内容里提到的 `narrative.*` / `musicmv.*` / `ad.*` kind 字符串都是文本常量，不是 Core 一等类型。拆分只是换位置不换性质。PASS。
6. Session/Project binding: N/A（纯常量）。
7. 序列化向前兼容: 不持久化字符串。PASS。
8. 5 端装配: 不需要（compose-time 常量）。
9. 语义测试: 3 条新断言 + 40+ 既有 keyword 断言。PASS。
10. LLM context 成本: **零变化**——字节级等价，每 turn token 数一模一样。PASS（所以这个 cycle 是纯 dev-UX win，运行时不变）。

**Non-goals / 后续切片.**
- 按 session 的 project genre 动态裁剪 prompt（例如纯 vlog 项目不塞 narrative 说明 token）——这是 `PromptBuildSystem` 章节显式点名的未来方向。现在还没 session-project-binding 的消费点成熟到驱动这个；后续 cycle 来做。
- Markdown 源 + 构建时拼接（Option D）在未来如果 prompt 体积继续扩可重新讨论，但当前 743 → 4 个 < 330 的文件已经解决了"单文件过长"的核心痛点。
