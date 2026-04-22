## 2026-04-21 — debt-split-create-project-from-template：提取 5 个 genre seed 到 `project/template/<genre>.kt`（§R.5.3 preemptive）

Commit: `43faf60`

**Context.** `CreateProjectFromTemplateTool.kt` 416 行，接近 500 行 debt 阈值（§R.5.3 preemptive split 档）。文件结构是"一个 Tool class + 5 个 `seed<Genre>` private 函数"，5 个 genre seed 加起来是主体（200+ 行）。新加一个 genre 要在已有 5 个 helper 的中间插入一个 40-50 行的块，相近 genre 的修改也要 scroll 来回，近似 "fat dispatcher" 反模式。Rubric §R.5.3：主动拆分、避免超过 500 行后被迫拆。Backlog bullet 原文："把每个 genre template 提取到 `project/template/<genre>.kt`，主文件变 dispatcher。"

**Decision.** 保持 `CreateProjectFromTemplateTool.kt` 作为 dispatcher，把每个 genre 的 seed 函数提取到 `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/template/<Genre>Template.kt`：

- `NarrativeTemplate.kt`（73 行）— `seedNarrativeTemplate()`
- `VlogTemplate.kt`（42 行）— `seedVlogTemplate()`
- `AdTemplate.kt`（59 行）— `seedAdTemplate()`
- `MusicMvTemplate.kt`（54 行）— `seedMusicMvTemplate()`
- `TutorialTemplate.kt`（55 行）— `seedTutorialTemplate()`

每个文件是一个 `internal fun seed<Genre>Template(): Pair<Source, List<String>>` —— 签名统一，只引入该 genre 相关的 body 类型和 ext helpers，和 `core/domain/source/genre/<genre>/` 的模块边界严格对齐。

主文件缩到 **187 行**（从 416 行 −55%），内部 dispatcher 提取成 `private fun dispatchTemplate(template: String): Pair<Source, List<String>>` 方法，只保留 `when { ... } → seed<Genre>Template()` 5 行分支 + 错误默认分支。`parseResolution` / `parseFrameRate` 这类 generic helpers 保留在主文件。

Genre 概念本身**不变**：seed functions 还是和之前一样在 Core 里引用 `core.domain.source.genre.<genre>.*`；这次 refactor 不引入新 genre-specific 名词到 Core 一等类型。§3a.5 要求的是 "不要在 Core 里新引入 genre 概念"，既有使用方式保留、仅文件级重组 —— 实际上更清晰地把每个 genre 的实现封装在独立文件里（比"5 个 genre 的代码挤在一个 file 里"对 §3a.5 **更合规**：单文件添加新 genre 不必碰别人的代码）。

**删除 + 重组 summary**：
- 主文件（`CreateProjectFromTemplateTool.kt`）：416 → 187 行（-55%）。删除 `seedNarrative` / `seedVlog` / `seedAd` / `seedMusicMv` / `seedTutorial` 5 个 private 函数 + 删除它们引入的所有 genre-specific imports（~30 个 import 行，现在各自落在对应 template 文件里）。新增 5 个 `io.talevia.core.tool.builtin.project.template.seed<Genre>Template` import。
- 新增目录：`core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/template/` 放 5 个 `<Genre>Template.kt` 文件（总 283 行）。
- 每个 template 文件的 KDoc 独立描述 genre skeleton（从主文件 KDoc 里拆 5 段），主文件 KDoc 缩减为"5 templates, 详见每个子文件"的索引。

**Alternatives considered.**

1. **Option A (chosen)**: 每个 genre 一个独立 file `<Genre>Template.kt`（camelCase 文件名匹配 `internal fun` 内容），`internal` 可见性。优点：精确对应 `core/domain/source/genre/<genre>/` 的模块结构；grep 一个 genre 的全部代码只看一个文件；新加 genre 是 `+1 file + 1 when branch` 而不是 `+40 行中间插入`；dispatcher 职责单一（~15 行 + 签名）；零工具数量影响。缺点：+5 个文件导致 CLAUDE.md 文件目录增长，但已有 `genre/<genre>/` 层级建立了 precedent。
2. **Option B**: 5 个 seed 都搬到一个 `ProjectTemplateSeeds.kt` 文件（one-file-all-genres）。拒绝：只是把代码从 Tool 文件搬到另一个文件，"新加 genre 在中间插一段"的痛点没解；拆分 genre 按概念分离才是目标。
3. **Option C**: 用 map-of-seeders `val TEMPLATES: Map<String, () -> Pair<Source, List<String>>>` 动态注册。拒绝：把 seed 签名从 `internal fun` 降级成 lambda 值，IDE 导航 / KDoc discoverability 变差；map 字符串 key 对 LLM / agent 零 benefit（dispatcher 已经是单 `when`）；增加无谓的间接层。
4. **Option D**: 把 seed 函数搬到 `core/domain/source/genre/<genre>/<Genre>Template.kt` 和现有 body 定义同目录。拒绝：`core/domain/source/genre/<genre>/` 是领域模型层（body 定义 + ext helpers），template 是**工具层**（Tool 的内部 seed 函数）；混层会让 core domain 被拖进 tool 的 composition style。保持在 `core/tool/builtin/project/template/` 让 template 是 tool-level 的 "fixture factory"。
5. **Option E**: 主文件保留所有 seed 但抽成 `companion object` 常量块（`NARRATIVE_SEED = { ... }`）。拒绝：只是语法糖；行数不变，文件责任还是混合 dispatcher + 5 个 genre。
6. **Option F**: 顺便把 template 外置成 JSON fixture，运行时加载（类似 `templates/narrative.json`）。拒绝：① template body 里有 `SourceRef(paletteId)` 这种类型安全引用，JSON 化会丢类型检查；② MultiPlatform resource loading 需要每个平台的 IO（违反 `core/commonMain` 零平台依赖）；③ 零 runtime flexibility 需求（template set 是编译期固定集合）。

**Coverage.**

- 既有 `CreateProjectFromTemplateToolTest`（5 个 template × happy path）全部绿 —— `internal fun seed<Genre>Template()` 可见性保留，dispatcher 行为 preserved。无需新 test（函数重定位不是新行为）。
- `./gradlew :core:compileKotlinJvm` + `:core:jvmTest` + `:core:ktlintCheck` 全绿。
- `./gradlew :apps:server:test` 全绿。
- 4 端构建：iOS sim / Android debug APK / Desktop / Server 全绿。
- **文件行数验证**：主文件 187 行（阈值 500 的 37%）；每个 `<Genre>Template.kt` 42–73 行（全部远低于阈值）。§R.5.3 preemptive debt 关闭。

**Registration.** 无装配变更 —— 纯文件级 refactor。`CreateProjectFromTemplateTool` 在 5 个 AppContainer 的 register 行保持。

**§3a 自查.**

1. 工具数量: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: 不动。PASS。
4. 状态字段: 无新 flag。PASS。
5. Core genre: 既有 genre 使用方式保留；refactor 不新增 genre 名词到 Core 一等类型。相反，每个 genre 的代码封装在单独文件里，让 "新加 genre = 新文件" 更清晰，比原 fat-dispatcher 更合规。PASS。
6. Session/Project binding: N/A（此 tool 是 project 创建入口，本就不绑 session）。
7. 序列化向前兼容: 无 `@Serializable` 结构变化。PASS。
8. 五端装配: 不动。PASS。
9. 测试语义覆盖: 既有 tests 覆盖面保持（behavior-preserving refactor）。PASS。
10. LLM context 成本: `helpText` / `inputSchema` 完全不变 —— LLM spec 0 tokens 变化。PASS。

**Non-goals / 后续切片.**

- **不扩 genre 集**。新加第 6 个 genre 的 driver 出现时独立 cycle。本轮仅拆分既有 5 个。
- **不动 `core/domain/source/genre/`**。genre body 定义保持在 domain 层，template 层只是 tool 的 composition helper。
- **不改 `update_source_node_body` / `set_*` 等**。template 生成 placeholder TODO 字符串的语义不变；用户仍用现有工具填充。
