## 2026-04-21 — bulk-apply-tool-input-default-projectid：把 10 个 timeline mutation tool 的 projectId 转成可选（VISION §5.4）

Commit: `5daaa49`

**Context.** 上一轮 `tool-input-default-projectid-from-context`（decision `2026-04-21-tool-input-default-projectid-from-context.md`）给 `ToolContext` 加了 `resolveProjectId(String?): ProjectId` 辅助方法 + 给 `project_query` / `add_clip` / `describe_project` 3 个 tool 把 `projectId` 改成可选（null → 走 session binding）。但只切了 3 个，还有 10 多个 timeline / AIGC / source 突变工具保留 required `projectId: String` —— 对 LLM 而言"绑了 session 后每次仍要 echo projectId"的 schema 约束既多余又容易触发幻觉。Backlog 原文点名：`AddTrackTool` / `AddSubtitleTool` / `AddTransitionTool` / `RemoveClipTool` / `ReplaceClipTool` / `TrimClipTool` / `SplitClipTool` / `MoveClipTool` / `SetClipVolumeTool` / `EditTextClipTool` —— "至少这 10 个 clipId-scoped timeline write tools"。Rubric §5.4（dual-user path：让 LLM 一旦 session 绑定就不用重复提供 projectId）。

**Decision.** 对这 10 个 tool 做完全相同的 5 步改动：

1. `Input.projectId: String` → `Input.projectId: String? = null`，加 KDoc "Optional — omit to default to the session's current project binding (`ToolContext.currentProjectId`). Required when the session is unbound; fail loud points the agent at `switch_project`."（和 `AddClipTool` 一字不差）。
2. JSON Schema `properties.projectId` 增加 `description: "Optional — omit to use the session's current project (set via switch_project)."`。
3. JSON Schema `required` array 中删除 `JsonPrimitive("projectId")`（其他 required 字段保留原样）。
4. `execute` 顶部加一行 `val pid = ctx.resolveProjectId(input.projectId)`；原来 `store.mutate(ProjectId(input.projectId)) { ... }` 改成 `store.mutate(pid) { ... }`。
5. 错误消息里的 `${input.projectId}` → `${pid.value}`（`input.projectId` 现在是 nullable，直接插值会打印 "null" 反而误导）。`AddTrackTool` 的 `Output.projectId = input.projectId` 同改成 `pid.value`。

并同步删除各文件里不再使用的 `import io.talevia.core.ProjectId`（`ReplaceClipTool` / `TrimClipTool` 之前同时使用 `ProjectId` 与 `AssetId` 等，只删 `ProjectId`）。

**Alternatives considered.**

1. **Option A (chosen)**: 10 个 tool 同一套 5 步改动，完全对齐 `AddClipTool` 的形态。优点：读者/LLM 看到 10 个 tool 一致的行为意味着"所有 timeline mutation 都能从 session binding 推导"；无 special case；`ctx.resolveProjectId` 的复用进一步验证上轮 decision 的抽象力。缺点：每个 tool 文件 diff 小但要改的文件数多；通过 grep + 机械 edit 降低手误风险（验证：4 端编译 + `:core:jvmTest` 全绿）。
2. **Option B**: 批量跑 regex / sed 一次性改完 10 个文件。拒绝：这 10 个文件里 `input.projectId` 被用作错误消息 (`"project ${input.projectId}"`) + `ProjectId(input.projectId)` 两种位置，机械替换容易把 string interpolation 替换错或漏掉。用 Read/Edit 逐文件处理 + `./gradlew compileKotlinJvm` 验证更稳。
3. **Option C**: 只挑 5-6 个「最常用」的切，其余留给后续 cycle。拒绝：backlog 明确点名"至少 10 个"，也是 §3a.1 "工具数量不净增"的反面 —— 这里净增 0 个 tool 但删了 10 个 required `projectId` 约束。一次做全 + 一次 push 更清晰。
4. **Option D**: 顺便把 `MoveClipToTrackTool` / `DuplicateClipTool` / `FadeAudioClipTool` / `SetClipTransformTool` / `SetClipSourceBindingTool` 等同家族 tool 一起改。拒绝：backlog 明确点了 10 个，范围越界会让本轮 diff 变大 + 破坏"按 backlog 驱动"的循环纪律。这些留给 follow-up 或下次 repopulate 的时候把它们明确列进 backlog。
5. **Option E**: 对还保留 required `projectId` 的 tool 在系统 prompt 里加一段解释"所有 write tool 都能 omit projectId"。拒绝：prompt 不是规范的载体 —— 要么 tool schema 本身允许 omit，要么就不该承诺 omit。当前改法把真理放进 schema，prompt 不用碰。
6. **Option F**: 顺便把 `ctx.resolveProjectId` 改成在 `ToolContext` 上的 `val projectId: ProjectId?` 属性 + tool 读属性。拒绝：上轮刚落地这个函数 + 3 个消费者；加属性要改 20+ 处（`Tool<I, O>.execute` 接口），破坏本 cycle 的 "只做 projectId 下沉" 聚焦，让回归范围失控。

**Coverage.**

- `./gradlew :core:compileKotlinJvm` + `:core:jvmTest` 全绿（1126 tests 不改）。现有 10 个 tool 的 `*ToolTest` 文件都在 jvmTest 下（`RemoveClipToolTest` / `EditTextClipToolTest` / `SetClipVolumeToolTest` / `MoveClipToolTest` / `TrimClipToolTest` / `AddTransitionToolTest` / `ReplaceClipToolTest` / `AddSubtitleToolTest` / `AddTrackToolTest` / `SplitClipToolTest`）。这些测试都传 explicit `projectId`，`projectId: String? = null` 的默认值不影响 —— explicit 仍走 `input != null -> ProjectId(input)` 分支。
- ktlintCheck 全绿。
- 4 端构建：`iosSimulatorArm64` / `androidAssembleDebug` / `desktopAssemble` / `serverCompileKotlin` 全绿。
- "Omit projectId → 走 session binding" 的语义本身已经由上轮的 `tool-input-default-projectid-from-context` decision 里的测试（`ToolContextResolveProjectIdTest` + `AddClipToolResolvesProjectIdFromContextTest`）验证过，`ctx.resolveProjectId` 内部逻辑不变，新消费者只是复用同一辅助函数。**Coverage debt**: 这 10 个 tool 的每一个"omit projectId → use ctx" 独立 case 没有单独测试（复用 AddClipTool 的一个代表性测试）。如果未来发现某个 tool 的默认路径有 bug，需要补测 —— 记为 follow-up。

**Registration.** 无新 tool；5 端 AppContainer 无需改动。

**§3a 自查.**

1. 工具数量: 净 0 增长（**-0**, 减少 required 字段数）。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 `Project`。PASS。
4. 状态字段: `projectId: String?` 是 optional identifier 不是 flag；`ctx.resolveProjectId` 明确 3 个状态（explicit / fallback / error）。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: **这就是本轮的核心目标** —— 通过默认化 projectId，让 session binding 成为 LLM 的一等路径。PASS（收益方向）。
7. 序列化向前兼容: `String` → `String? = null` 是非破坏性改动 —— 旧 JSON 调用（没这个字段）现在依旧解码到 `null`（= 走 session fallback）；带 `projectId: "..."` 的旧调用依旧走 explicit 分支。PASS。
8. 五端装配: 无新 tool，无装配变更。PASS。
9. 测试语义覆盖: 既有 10 个 `*ToolTest` 全绿。omit 路径的语义由上轮 decision 的代表性测试覆盖。**部分 PASS** —— 记为 coverage debt。
10. LLM context 成本: 每个 tool 的 schema `required` 少 1 个字段、`properties.projectId` 多 ~60 tokens（description）。粗估净成本持平偏省：required 字段 spec 本来就比 optional + description 贵；并且 agent 实际调用时可以 omit projectId 节省每 turn ~30 tokens × 10 tools。收益方向。PASS。

**Non-goals / 后续切片.**

- **Follow-up: 剩余 timeline / AIGC / source tool 的 projectId 下沉。** 还有 `MoveClipToTrackTool` / `DuplicateClipTool` / `FadeAudioClipTool` / `SetClipTransformTool` / `SetClipSourceBindingTool` / `ApplyFilterTool` / `ApplyFilterToClipsTool` / `ApplyLutTool` / `AddSubtitlesTool` / `AutoSubtitleClipTool` / `RemoveTransitionTool` / `DuplicateTrackTool` / `RemoveTrackTool` / `ReorderTracksTool` / `RevertTimelineTool` / `ClearTimelineTool` / `RemoveFilterTool` / `ExportTool` / `ExportDryRunTool` / 多个 project write（`SaveProjectSnapshotTool` / `RestoreProjectSnapshotTool` / `DeleteProjectSnapshotTool` / `ForkProjectTool` / ...）以及所有 source tools（`SetCharacterRefTool` / `SetStyleBibleTool` / ... / `ImportSourceNodeTool` / `AddSourceNodeTool` / ...）—— 合计 25+ tool。单独下一 cycle 或分批做，同样 5 步 mechanical transformation。
- **Follow-up: 每个 tool 的 "omit projectId → ctx 推导" 独立测试。** 当前复用上轮一个代表测试。补 10 个 per-tool unit test（每个 ~15 行）需要 ~1 小时。
- **不动 `ToolContext` 接口**: `currentProjectId: ProjectId?` + `resolveProjectId(String?): ProjectId` 的形态是 K.1 级基础设施，不要在这轮扩出新属性 / 新方法。
- **不动 system prompt**: `taleviaSystemPrompt()` 已经在 "Session-project binding" 章节里讲了 `switch_project` 建立会话 → 工具默认读 session；加了本轮的 10 个 tool 后语义更贴合，不需要改 prompt。
