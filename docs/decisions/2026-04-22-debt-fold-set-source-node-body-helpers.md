## 2026-04-22 — Fold set_character_ref / set_style_bible / set_brand_palette into add_source_node + update_source_node_body (VISION §5.5 + §3a-1/§3a-5 rubric)

**Context.** Backlog bullet `debt-fold-set-source-node-body-helpers`
指出 `SetCharacterRefTool` / `SetStyleBibleTool` / `SetBrandPaletteTool` 作为 3
个独立 tool id 存在，而 kind-agnostic `add_source_node` + `update_source_node_body`
已经覆盖全部 create / edit 写路径。硬编码 `character_ref` / `style_bible` /
`brand_palette` genre 名到 tool registry 违反 §3a-5；每次 turn 都要付这 3 个 tool
spec 的 token 成本（§3a-1 / §3a-10）。Rubric delta：§5.5 source write-path
"部分 → 有（kind-agnostic primitives only）"。上游参考：
`docs/decisions/2026-04-21-merge-define-update-tool-pairs.md` 把 6 个
Define / Update 工具合并成 3 个 upsert 工具，就是为 gruntwork 清路；本轮是那条线的
终点站，把 upsert 工具也合进 kind-agnostic 对。

**Decision.** 直接删除 3 个 Set* 工具（无 deprecated stub，per user
memory `feedback_no_compat_clean_cuts`），所有写路径统一到：

- **Create** → `add_source_node(projectId, nodeId, kind, body, parentIds)`。
  nodeId 按约定 `character-<slug>` / `style-<slug>` / `brand-<slug>`；Desktop UI
  (`SourcePanel`) 和 e2e 测试都复用新 public `slugifyId(name, prefix)` 计算。
- **Edit** → `update_source_node_body(projectId, nodeId, body)`（whole-body
  replacement）。UI 层（SourcePanel `dispatchBodyUpdate`）把现有 `node.body`
  JsonObject copy 一份、overlay 编辑过的字段再送入 tool，所以 callers 仍只需声明
  "变了什么"。
- **Parents** → `set_source_node_parents` 已存在，不动。

Registration 变动：5 个 `AppContainer` (CLI / Desktop / Server / Android /
iOS Swift) 的 3 行 `register(Set*Tool(...))` + imports 全部删除。`slugifyId`
从 `internal` 提到 `public`，因为 Desktop UI 需要跟 LLM 保持 id 约定一致。

典型对照（RefactorLoopE2ETest 的 §6 "rename Mei's hair" 步骤）：
```
// before
registry["set_character_ref"]!!.dispatch(buildJsonObject {
    put("projectId", pid.value); put("nodeId", "mei")
    put("visualDescription", "red hair")
}, ctx())

// after
registry["update_source_node_body"]!!.dispatch(buildJsonObject {
    put("projectId", pid.value); put("nodeId", "mei")
    put("body", buildJsonObject {
        put("name", "Mei"); put("visualDescription", "red hair")
    })
}, ctx())
```

Partial-patch 语义消失是刻意的：`update_source_node_body` 的 helpText 明说是 full
replacement；客户端负责 `describe_source_node → overlay → write back`。之所以
值得放弃 partial-patch 的便利，是因为 3 个 set_* 的便利只对 3 种 kind 生效，而这
项目每加一种 genre 就又要加一对 define/update 小工具 —— 那条路径恰好是
`debt-merge-define-update-tool-pairs` 想砍掉的增长。kind-agnostic pair 的便利
对任何 kind 都成立，且对 LLM 的 per-turn spec 成本恒定。

**Alternatives considered.**

1. **Deprecate-as-stub**（被拒）：在 3 个 Set* 工具保留 shell class，内部 delegate
   到 `UpdateSourceNodeBodyTool`。优点：passive migration，旧系统 prompt / 用户
   教程不会挂。缺点：tool spec 仍占 token，§3a-1 净增长 = 0 不算胜利；§3a-5
   硬编码 genre 名到 registry 仍然存在；项目用户规则
   `feedback_no_compat_clean_cuts.md` 明确默认 clean cut。本项目还没有外部稳定
   用户合约，deprecated 阶段零价值。
2. **Split set_*_body 和 set_*_parents** 为两组 orthogonal（被拒）：把三个 Set*
   拆成 6 个更小工具。方向错 —— §3a-1 "tool 数量不净增" 的硬规则禁止从 3 涨到
   6；而且现有 `update_source_node_body` + `set_source_node_parents` 已经完成
   orthogonal split。
3. **仅保留 `set_character_ref`**（character_ref 是真正高频的）（被拒）：
   character_ref 看起来确实比 style_bible / brand_palette 用得多，但留 1 个 就
   等于留 3 个的 §3a-5 违规（registry 里仍有"character"这个 genre 词）；而且
   character_ref 的"ergonomic win"很小，主要是 `clearLoraPin` 哨兵 + `voiceId=""`
   clear 语义，两者在 full-body-replacement 下自然消失（caller 重写 body 时
   直接不塞这些字段）。部分保留 = 半吊子，不符合 clean cut 原则。

业界共识对照：
- **OpenCode** tool 设计（`packages/opencode/src/tool/tool.ts` + 具体 builtin）
  几乎全是 kind-agnostic primitives（`read`、`write`、`edit`、`grep`）。类似的
  "为每种 FileType 提供 typed upsert" 的工具在 OpenCode 里是不存在的。
- **kotlinx.serialization** 约定：所有 `CharacterRefBody` / `StyleBibleBody` /
  `BrandPaletteBody` 的字段都有 default value（除 required essentials），所以
  LLM 给出最小 body `{"name":"Mei","visualDescription":"teal"}` 就能 round-trip
  through `asCharacterRef()` —— 这是 `add_source_node` 能干掉 Set* 工具的根本
  前提。
- **SemVer**：本项目尚未发布稳定 schema，bundle 里保存的 JSON 是 CharacterRefBody
  serializer 的产物，删工具不影响已有 bundle 的反序列化（`asCharacterRef` / domain
  helpers 都保留）。

**Coverage.**
- 核心单元：重写后的 `SourceToolsTest.kt` 覆盖 create / body update / voice 字段
  round-trip / kind collision / parent threading / parent cascades stale / parent
  validation / parent edits via `set_source_node_parents`，全部通过
  `add_source_node` + `update_source_node_body` + `set_source_node_parents` 驱
  动。
- E2E：`RefactorLoopE2ETest.editCharacterThenRegenerateThenExport` 是 VISION §6
  旗舰闭环（edit character → find_stale_clips → regenerate → export），现在用
  `update_source_node_body` 做 character 编辑，闭环完整通过。
- Error message：`ImportSourceNodeToolTest.selfImportIsRejected` 断言从
  `set_character_ref` 改到 `add_source_node`，和 `ImportSourceNodeTool` 的
  error message 同步。
- System prompt：`TaleviaSystemPromptTest` 必出现短语从 `set_character_ref`
  改到 `add_source_node` + `update_source_node_body`。
- 删除：`SetConsistencyToolsTest.kt`（229 行）整体删除 —— 测的是 Set* 专有
  partial-patch 语义（`""` clear / `clearLoraPin` / hex normalise / slug default），
  这些特性随工具消失。

**Registration.** CLI / Desktop / Server / Android / iOS Swift 五个
AppContainer 的 imports + 3 行 register calls 全部移除。净减 3 tool registrations
× 5 containers = 15 行 register + 15 行 imports 清掉。LLM per-turn tool spec
节省约 750 token（3 tool × 3 个 typed Input + 3 个 JSON schema 长 helpText）。

**Follow-ups surfaced.**
- `baad43f`（file-bundle ProjectStore）引入的 `ServerContainer` 测试回归：
  `ServerContainer(env = emptyMap())` 在测试里被所有 server test 使用，但
  line 215 `env["TALEVIA_RECENTS_PATH"]!!` 假设 env 已被 `serverEnvWithDefaults()`
  填好（只有 `Main.kt` 走那条路）。15 个 server test 全部失败，pre-dates
  本轮。已作为 P2 bullet `debt-server-container-env-defaults` append 到
  `docs/BACKLOG.md`。
- `set_character_ref` etc. 在 `docs/decisions/` 历史文件里仍有字面引用，刻意
  不动 —— 决策档案是历史切片，不倒追写。新 decision（本文件）是唯一权威。

**Process note.** 本轮起 `/iterate-gap` 改为单 commit：decision 文档 + 代码
改动 + backlog bullet 删除 + 顺手记 debt 全放同一次 commit，不再走
`feat + docs(decisions)` pair。skill 文件同步更新（`.claude/skills/iterate-gap/SKILL.md`）。
