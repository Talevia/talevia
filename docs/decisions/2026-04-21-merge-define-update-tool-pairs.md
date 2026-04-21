## 2026-04-21 — merge-define-update-tool-pairs：6 个 Define/Update 合并为 3 个 Set upsert（VISION §5.2 rubric）

Commit: `1ba3b84` (pair with `docs(decisions): record choices for merge-define-update-tool-pairs`).

**Context.** `core/tool/builtin/source/` 里三对兄弟工具成对存在：`DefineCharacterRefTool` / `UpdateCharacterRefTool`、`DefineStyleBibleTool` / `UpdateStyleBibleTool`、`DefineBrandPaletteTool` / `UpdateBrandPaletteTool`。Define 在不存在时创建（对一个 id 幂等替换语义），Update 在已存在时打补丁。对 LLM 来说这是两条互斥分支（"先 list_source_nodes 看存不存在，再决定调哪个"），每 turn 的 system context 里多挤了 3 条几乎重复的 tool spec，LLM 还得学会什么时候切换。Backlog 的 P1 #1 条 `merge-define-update-tool-pairs` 把这个模式标为需要合并的信号。同一批 consistency 工具已经经过三次迭代（2026-04-19 加的 Define、同月的 Update、`parentIds` 扩展），每加一点功能就要改两份。

业界参照：REST/HTTP 的 `PUT` 语义（create-or-replace）、SQL `INSERT ... ON CONFLICT UPDATE`（upsert）、MongoDB `findOneAndUpdate({upsert: true})`。三者的共识：读路径里 "存在不存在" 的判断应该藏在工具内部而不是暴露给调用方，只要语义清晰（哪些字段是 required-on-create vs optional-on-patch）就是正确的一等抽象。

VISION §5.2 rubric "一等抽象 > patch" 对本改动尤其契合：当前 Define + Update 的拆分本质是在"提供两个 patch 工具"，而"工具 = 想动一个 node"这一层统一的抽象被埋在了下面。

**Decision.** 引入 `SetCharacterRefTool`（`set_character_ref`）/ `SetStyleBibleTool`（`set_style_bible`）/ `SetBrandPaletteTool`（`set_brand_palette`）三个 upsert-with-patch 工具，删除 6 个旧 Define / Update 工具。每个新工具：

- **Create path**（node 在 `nodeId` 或 slug-of-name 下不存在）：essentials 必填（`set_character_ref` 要 `name` + `visualDescription`、`set_style_bible` 要 `name` + `description`、`set_brand_palette` 要 `name` + `hexColors`）。`nodeId` 未显式传时沿用原 Define 的 slug 规则（`character-mei` / `style-warm` / `brand-acme`）。
- **Patch path**（node 已存在）：所有 body 字段都可选，至少一个非空（`nothing-to-update` fail-loud）。`null` = 保留当前值；显式 `""` 清空可选 string 字段（`voiceId`、`negativePrompt`、`lutReferenceAssetId`）；`[]` 清空可选 list 字段（`referenceAssetIds`、`typographyHints`、`parentIds`、`moodKeywords`）。`hexColors` 不能清空（空调色板是数据模型错误；走 `remove_source_node` 删整个节点才对）。
- **Kind-collision guard**：同 id 已存在但 kind 不匹配 → fail loud，同原 Define / Update 的行为。
- **LoRA 专用哨兵**：`set_character_ref` 保留 `clearLoraPin: Boolean` 和 `loraPin: LoraPinInput?` 互斥（同时传两个报错）。
- **Output shape**：`{nodeId, created: Boolean, updatedFields: List<String>}`。`created=true` 表示走了创建分支，`false` 表示 patch。`updatedFields` 列出调用方真正传了值的 body 字段（去重），方便 LLM 验证 intent 是否 round-trip。
- 每次调用 `replaceNode`（patch）或 `addNode`（create）都会 bump `contentHash`，和原工具保持一致，下游 `find_stale_clips` / `regenerate_stale_clips` 的链路零变动。

Net tool count: **-6 old + 3 new = -3**。满足 §3a#1 "工具数量不净增（新增必须同时删至少一个近似工具）"。

**Alternatives considered.**

- **Option A（chosen）: upsert + patch semantics** — 不存在就按 create 的 required 字段检查、存在就按 patch 语义合并。单工具表达了原来两条分支的全部语义，LLM 再不用选分支。
- **Option B: upsert + full-replace** — `set_*` 永远要求完整 body，类似 HTTP `PUT`。拒绝：失去 Update 带来的 "surgical patch" 体验（"把 Mei 的头发改成红色" 一字段改动），LLM 每次都要把整个 body 原样发过来，token 成本反增；partial-patch 是原 Update 存在的核心理由，不应倒退。
- **Option C: 保留 Define，新增一个统一的 upsert 做内部 dispatch 到 Define-or-Update（deprecation 窗口）** — 拒绝：净 tool 数量还是 7（> 6），§3a#1 直接否决。项目当前阶段没有对外稳定面（CLAUDE.md "Platform priority" 小节里明确），deprecation 窗口零价值、纯烧 context。
- **Option D: 把 Define 的语义直接扩展成 upsert（不删 Update）** — 拒绝：虽然 Define 原本就是"对同 id 幂等替换"，已经有一半 upsert 语义，但 Define 的 required-on-every-call 约束（总要 name + visualDescription）和 Update 的 optional-per-field 约束是相互排斥的；硬合成一个工具要在 schema 上标"required 字段在 node 存在时其实 optional"，schema 文档就变得自相矛盾，模型理解更差。选 "全新的 Set tool 把两种语义都吸收" 而不是"把 Define 改大"。

**Semantics table — required vs optional on create vs patch.**

| 字段 | `set_character_ref` | `set_style_bible` | `set_brand_palette` |
| --- | --- | --- | --- |
| essentials on create | `name`, `visualDescription` | `name`, `description` | `name`, `hexColors` |
| 字符 clear sentinel | `voiceId=""` | `negativePrompt=""`, `lutReferenceAssetId=""` | （无） |
| list clear sentinel | `referenceAssetIds=[]`, `parentIds=[]` | `moodKeywords=[]`, `parentIds=[]` | `typographyHints=[]`, `parentIds=[]` |
| 不能清空的字段 | —（loraPin 有专用 `clearLoraPin=true` 哨兵） | — | `hexColors`（空 palette 是数据错误，走 `remove_source_node`） |
| 空白 blank 策略 | `name` / `visualDescription` 非空时不能 blank | `name` / `description` 同 | `name` 同 |

**Nodeid 解析.**

- 显式 `nodeId`（非空）→ 直接用它作为 selector。
- 否则 `name` 非空 → slug 出候选 id（`character-mei` / `style-warm` / `brand-acme`）。
- 候选 id 匹配已有节点 → patch path；匹配不到 → create path。
- 都没传（`nodeId` 和 `name` 都空）→ 只可能是 patch path 但没有 selector，tool 会落到 create 分支里撞 "create 要 name" 的检查，fail loud。

这让 "只传 `name=Mei` + `visualDescription=red hair`" 这种再次调用的场景仍然幂等地 patch 同一个节点，保留了原 Define 的 idempotent-on-slug 行为。

**Coverage.** 两套测试：

1. **新增 `core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/source/SetConsistencyToolsTest.kt`**（19 tests） — 针对三个 Set 工具：
   - create path reports `created=true`、patch path reports `created=false`。
   - 每个 tool 的 create-required-fields fail-loud（缺 `name` 缺 `visualDescription`/`description`/`hexColors`）。
   - 每个 tool 的 patch-with-no-fields fail-loud。
   - 边界语义：`""` 清 `voiceId` / `negativePrompt`；`[]` 清 list；`hexColors=[]` 仍被拒绝（patch 路径上也不能清）。
   - `set_character_ref` 的 `loraPin` + `clearLoraPin=true` 互斥。
   - 替换 `referenceAssetIds`；清 `referenceAssetIds`；kind-collision 报错。
   - patch 调用会 bump `contentHash`。
2. **重写 `SourceToolsTest.kt`** — 把原测试里的 Define 调用全部改为 Set 调用。覆盖保留：slug-default id、kind-collision、list filter by prefix、body round-trip、parents thread-through、parent cascades contentHash、missing parent fail-loud、self-parent fail-loud、patch updates parents too。测试总数不变，语义等价。
3. **删除 `UpdateConsistencyToolsTest.kt`** — 全部 case 已被 `SetConsistencyToolsTest` 吸收。
4. **更新 `core/src/jvmTest/kotlin/io/talevia/core/e2e/RefactorLoopE2ETest.kt`** — import + registry 调用从 `UpdateCharacterRefTool` / `"update_character_ref"` 切到 `SetCharacterRefTool` / `"set_character_ref"`。E2E "edit character → regenerate → export" 闭环保持红线绿。
5. **更新 `ImportSourceNodeToolTest.kt`** 的错误消息断言从 `"define_character_ref"` 到 `"set_character_ref"`。
6. **更新 `TaleviaSystemPromptTest.kt`** 的 key phrase 断言从 `"define_character_ref"` 到 `"set_character_ref"`。

**Registration.** 5 个 `AppContainer` 全改：
- `apps/cli/src/main/kotlin/io/talevia/cli/CliContainer.kt`
- `apps/desktop/src/main/kotlin/io/talevia/desktop/AppContainer.kt`
- `apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt`
- `apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt`
- `apps/ios/Talevia/Platform/AppContainer.swift`

每个 container：删 6 条旧 import + 6 条 `register(...)`、加 3 条 `Set*Tool` import + 3 条 `register(Set*Tool(projects))`。

**Upstream fix-ups.** 引用旧工具 id 的地方同步更新：

- `core/agent/TaleviaSystemPrompt.kt` — "Consistency bindings" 段落完全重写：从 "用 Define 创建 / 用 Update 打补丁" 的二分叙述变为单工具 upsert 的统一叙述，保留 clear sentinel（`""`/`[]`/`clearLoraPin`）、kind-collision 守护、contentHash bump + `find_stale_clips` 联动这些关键点。其他位置的 `define_character_ref` / `define_style_bible` / `update_*` 引用全部改为 `set_*`。
- `core/tool/builtin/video/ApplyLutTool.kt` 的错误消息 `"set one via define_style_bible first"` → `"set one via set_style_bible first"`。
- `core/tool/builtin/source/ImportSourceNodeTool.kt` 的 within-project 提示。
- `core/tool/builtin/source/AddSourceNodeTool.kt` 的 KDoc + helpText。
- `core/tool/builtin/source/UpdateSourceNodeBodyTool.kt` 的 KDoc + helpText。
- `core/tool/builtin/source/DiffSourceNodesTool.kt` 的 KDoc。
- `core/tool/builtin/source/SetSourceNodeParentsTool.kt` 的 KDoc permission tier 类比。
- `core/tool/builtin/video/EditTextClipTool.kt` 的 KDoc patch-semantics 类比。
- `core/tool/builtin/project/CreateProjectFromTemplateTool.kt` 的 KDoc + helpText。
- `core/tool/builtin/project/FindStaleClipsTool.kt` 的 KDoc 场景示例。
- `core/tool/builtin/ml/DescribeAssetTool.kt` 的 KDoc auto-scaffold 示例。
- `apps/desktop/src/main/kotlin/io/talevia/desktop/SourcePanel.kt` — UI 调用从 `define_character_ref` / `update_character_ref` 等六个 id 切到三个 `set_*` id，UI 逻辑不变（新旧工具 input 形状兼容：传了 name + description 走 create，只传 nodeId + 字段走 patch）。
- `apps/desktop/src/main/kotlin/io/talevia/desktop/Main.kt` 的 `resolveOpenablePath` KDoc 例子。

**Session-project-binding 注记（§3a#6）.** 三个 Set tool 的 `projectId: String` 还是明传，等 `session-project-binding`（P1 backlog 第 3 条）落地后按统一迁移到 `ToolContext.currentProjectId`。本 cycle 不改，保持和所有既有 project 工具的 input 形状一致。

**Serialization / persistence compat（§3a#7）.** `CharacterRefBody` / `StyleBibleBody` / `BrandPaletteBody` 的 `@Serializable` 字段没有任何变化；Set 工具内部仍然调 `addCharacterRef` / `replaceNode` 等同样的 Source 扩展。已经落盘的项目（`TALEVIA_DB_PATH` / `TALEVIA_MEDIA_DIR` 里的 `data` 列 JSON）继续 round-trip 干净，没有 schema migration 需求。

**LLM context 成本（§3a#10）.** 6 个旧工具的 spec 平均每条约 400-500 token（JSON schema + helpText + KDoc-derived description），合计约 2500-3000 token/turn。3 个新工具的 spec 比原单个大约多 100-150 token（多了 create-vs-patch 的 KDoc 叙述、多了 `created` 输出字段描述），合计约 1500-1800 token/turn。**净减约 -1000 到 -1200 token/turn**。再叠加 LLM 不再需要"先判断 exist 再选分支"的 reasoning overhead，实际上下文更省。

**Non-goals / 后续切片.**

- `set_*` 族不吸收 `add_source_node` / `update_source_node_body` —— 那两个是 kind-agnostic 的 opaque-body 工具，面向 genre 节点（narrative.shot、vlog.raw_footage 等），和 typed-body 的三 consistency kinds 不同构。这条红线在 `UpdateSourceNodeBodyTool` 的 KDoc 里有说明。
- `set_source_node_parents` 继续单独存在 —— patch parents 是跨所有 kind 通用的操作，已经由独立工具承担。本次保留三个 Set 工具里的 `parentIds` 参数是因为 consistency 节点创建场景里经常需要一步把 parents 钉好，拆两步反而费 turn。

定期 review：如果 "`set_*` + `set_source_node_parents` 的 parents 重叠" 变成频繁问题，再考虑把 parents 从 Set 工具拿掉。当前 `SetSourceNodeParentsTool` 的 KDoc 已经说明 "`parents` 非 update 工具的主职责，单独工具" 的分工边界。
