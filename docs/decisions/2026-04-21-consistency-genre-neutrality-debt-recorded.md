## 2026-04-21 — consistency-genre-neutrality-debt：显式记录 Core 里已一等化的 consistency kind（VISION §5.1 rubric / VISION §2 genre neutrality 承诺）

Commit: `9db2f4b` (this decision has no feat pair — docs-only debt recording).

**Context.** VISION §2 承诺 Core 不硬编码任何一套 genre 的 source schema（原话："任务要求在 Core 里硬编码某一个 genre 的 source schema" 是冲突信号；"新 genre 要能通过扩展 source schema 支持，而不是改 Core"）。但 `core/domain/source/consistency/` 已经把 `core.consistency.character_ref` / `core.consistency.style_bible` / `core.consistency.brand_palette` 作为 Core 一等概念。具体一等化点：

- `ConsistencyKinds.kt`: 明确枚举三个 kind 常量——`CHARACTER_REF = "core.consistency.character_ref"`、`STYLE_BIBLE = "core.consistency.style_bible"`、`BRAND_PALETTE = "core.consistency.brand_palette"`，外加 `ALL: Set<String>` 集合。注释自述声明这是"跨 genre 的共享概念"（narrative 的 character == MV 的 performer == vlog 的 subject）——这个论证当前只覆盖 character_ref，没有为 style_bible / brand_palette 做同样强度的跨 genre 构造论证。
- `ConsistencyBodies.kt`: 具体的 `@Serializable` 数据类 `CharacterRefBody`（含 `visualDescription` / `referenceAssetIds` / `loraPin: LoraPin?` / `voiceId: String?`）、`StyleBibleBody`（含 `description` / `lutReference` / `negativePrompt` / `moodKeywords`）、`BrandPaletteBody`（含 `hexColors` / `typographyHints`）、`LoraPin`（`adapterId` / `weight` / `triggerTokens`）。这些字段 shape 是针对叙事 AIGC 生成场景设计的。
- `PromptFolding.kt`: 硬编码了折叠顺序 "[style bibles] [brand palette] [characters] + base prompt"，以及三个 kind 各自的拼装格式（`"Style: <name> — <desc> [mood: ...]"` / `"Brand: <name> palette #hex / #hex typography ..."` / `"Character \"<name>\": <visualDescription>"`）。遇到未知 kind 是 `else -> Unit` 静默跳过。
- `VoiceFolding.kt`: 只扫 `CharacterRefBody.voiceId`，style_bible / brand_palette 显式声明"TTS 没有 style 轴来绑它们所以静默忽略"；并且"多个 character_ref 都有 voiceId 就抛错"——这套语义直接预设了 character_ref 是 TTS voice 的唯一合法承载体。
- `ConsistencySourceExt.kt`: `addCharacterRef` / `addStyleBible` / `addBrandPalette` 是 Source 层的专用 extension（不是通用 `addNode`），读侧有 `asCharacterRef` / `asStyleBible` / `asBrandPalette` 三个 kind-tagged accessor，`consistencyNodes()` / `resolveConsistencyBindings()` 也按 `ConsistencyKinds.ALL` 过滤。

**Why it's debt, not bug.** 当前唯一已接入的 genre 是叙事 (narrative)，广告和教程还未真接入。一等化这三个 kind 等于为唯一 genre 做了合理的默认；真正的风险在于**第二个 genre 接入时如果有不同构的 consistency 需求**——例如教程 genre 可能要"narrator_style"（和 character_ref 不同构，没有视觉 referenceAssetIds，只有 voice preset + 语速 + 停顿风格），或广告 genre 的 "product_shot_ref"（视觉 reference 类似 character_ref，但没有 voiceId，且有"角度约束"/"legal-safe zones"字段 character 没有）。那时我们会被迫要么改坏 Core 的一等类型（给 `CharacterRefBody` 加一堆 nullable 字段），要么在 `SourceNode.body` 里塞一个 "special-cased second implementation"（新 kind 复用旧 accessor）——两条路都会放大债务。VISION §5 rubric §5.1 问题 "新 genre 需要改 Core 还是只需扩展？"的答案此刻是"character_ref/style_bible/brand_palette 的 shape 改了就得改 Core"。

**Trigger signals for refactor.** 触发重构的硬信号（按优先级 / 出现即停）：
1. **第二个 genre 新增 consistency kind** — 例如 `ad.product_shot` 或 `tutorial.narrator_style` 这种新 kind 进来。一旦有第二个非-character_ref 的 consistency 节点，`PromptFolding.kt` 的"style → brand → character"硬编码顺序就站不住了（新 kind 插哪儿？），必须先拆。
2. **Folding 需要 genre 条件分支** — 如果发现 `PromptFolding` / `VoiceFolding` 里开始写 `if (kind.startsWith("ad.")) {...}` 或 `when (node.kind)` 对特定 genre 特判，立即停下来做拆分。Core 的 folding 函数出现 genre 名字就是 VISION §2 被违反的瞬间。
3. **VoiceFolding 的 character_ref 独占假设被质疑** — 广告 VO 可能不 care character identity，教程 VO 可能强制用 narrator preset 而非角色声线。只要有任何一个新 consistency kind 也想绑 `voiceId`（且不是 character），当前 `nodes.mapNotNull { node -> node.asCharacterRef()?.voiceId … }` 的窄扫描就得改。
4. **Consistency body 之间的引用关系超出 parents DAG 能表达的范围** — 例如某个新 kind 需要"引用两个 character_ref 做 weighted mix"（双人合影），或"style_bible 继承自另一个 style_bible"带权重差。单 DAG 边不够，就得引入 kind-specific 引用语义。
5. **`StyleBibleBody` 字段或 `BrandPaletteBody` 字段被新 genre 要求扩字段** — 如果广告 genre 要求 `BrandPaletteBody.hexColors` 之外还能带 "legal compliance regions"，那 "brand_palette 是跨 genre 一等概念" 的论点就破了，这个 kind 应该下沉到广告 genre extension。

**Proposed refactor shape when triggered.**
- `core/domain/source/consistency/` 下沉为**接口 + registry**：Core 只保留 `interface ConsistencyKind`（"这个 kind 叫什么、body 怎么 decode"）+ `interface ConsistencyFolder`（"给我一组 SourceNode，给我 folded prompt fragment + 负 prompt + loras + refs"）+ `interface VoiceFolder`（同理 for TTS）。`foldConsistencyIntoPrompt` 退化为"按注册顺序调用每个 folder，合并结果"。
- 把当前 `CharacterRefBody` + `PromptFolding` 里的 character 分支 + `VoiceFolding` 整个文件搬到 `core/domain/source/narrative/consistency/`（或独立 `genre-narrative/` 模块），通过 registry 注册为 narrative 专用的一组 folder 实现。
- `StyleBibleBody` / `BrandPaletteBody` 按实际归属分家：如果发现它们本质上是广告 / 品牌内容的概念，搬到 `genre-ad/consistency/`；如果是跨 genre 共享的视觉风格约束（narrative 和 ad 都用），保留为 Core 但改名 `VisualStyleHint` 让语义更诚实。
- Foldings 变成 composable——每个 genre extension 注册自己的 folder，顺序由 registry 按注册 / 显式优先级决定。`ConsistencyKinds.ALL` 变成 registry 上的查询（`ConsistencyRegistry.allKinds()`），不再是 Core 里的硬编码集合。
- `ConsistencySourceExt.addCharacterRef` 等 typed builder 跟随 body 搬到对应 genre extension；Core 层只保留通用 `addNode(kind, body, parents)` 原语。

**Non-goals for this decision.** 这次**不做重构**。只记录现状 + 触发条件，让未来第二个 genre 来接时能 `grep 'consistency-genre-neutrality-debt' docs/decisions/` 立刻找到这个 note 和重构蓝图，不需要再重新侦察一次 Core 里这三个 kind 的散布。

**Alternatives considered.** 至少两个：
- Option A (chosen): 记录债务 + 明确触发信号，等第二个 genre 来了再动。理由：未证伪的抽象是债务（抽象错了更难撤）；当前 genre 单一，一等化这三个 kind 是合理默认，提前拆会 over-engineer。
- Option B: 现在就拆成 registry 模式。拒绝：VISION §5 rubric 明确说 "优先补**一等抽象**，不要为某个具体 genre / 效果 patch——patch 不会沉淀成系统能力"，同时也警告 anti-requirement "Designing for hypothetical future needs … without a concrete driver"。第二个 genre 尚未接入、没有真实负载来验证 registry 接口形状，预先抽象 → 接口很可能设错（第二 genre 真接入时发现接口不 fit，照样改 Core），纯浪费。

**Coverage.** 无代码改动——本决议是观察记录。下次 repopulate 时，iterate-gap skill 的 R.5 debt 扫描应当能通过 `grep -r 'ConsistencyKinds\.' core/` / `grep -r 'core\.consistency\.' core/` 追踪一等化点的增减；若扫描结果超出本文件枚举范围，说明债务在无声扩大，需要把这条 bullet 重新加回 BACKLOG.md。

**Registration.** 无需注册——pure docs note. 删除对应 BACKLOG.md 里的 P2 bullet `consistency-genre-neutrality-debt`。
