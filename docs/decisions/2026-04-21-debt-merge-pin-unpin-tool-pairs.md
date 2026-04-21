## 2026-04-21 — debt-merge-pin-unpin-tool-pairs：4 个 pin/unpin 工具合并为 2 个 set-pinned upsert（§3a.2 成对 → upsert）

Commit: `ea3c657` (pair with `docs(decisions): record choices for debt-merge-pin-unpin-tool-pairs`).

**Context.** Loop-2 先后落了 `pin_lockfile_entry` / `unpin_lockfile_entry`（hash 维度，2026-04-21-pin-lockfile-entry-for-hero-shot-aigc.md）和 `pin_clip_asset` / `unpin_clip_asset`（clip 维度的 ergonomic shortcut，2026-04-21-pin-clip-asset-clip-level-shortcut.md），累计 **4 个工具**覆盖同一个布尔状态翻转。对 LLM 来说每个 pin/unpin 对 = 两份 spec + helpText + inputSchema 吃 token，分成两个互斥分支让模型决策"该调哪个"；而对实际语义来说只是一个 `pinned: Boolean` 的 upsert。这正是 §3a.2（`Define*` / `Update*` 不成对 → `set_<concept>`）的典型学费。

P1 backlog bullet `debt-merge-pin-unpin-tool-pairs` 明确指出："合并为 `set_clip_asset_pinned(clipId, pinned: Boolean)` / `set_lockfile_entry_pinned(inputHash, pinned: Boolean)` upsert 形态（和 `set_character_ref` 同类）"。这个 cycle 直接执行该方向。

**Decision.** 合并结果：4 个工具 → 2 个工具（net **-2**）。

| 新工具 | 替换 | Input | Output |
|---|---|---|---|
| `set_clip_asset_pinned` | `pin_clip_asset` + `unpin_clip_asset` | `{projectId, clipId, pinned: Boolean}` | `{projectId, clipId, assetId, inputHash, toolId, pinnedBefore, pinnedAfter, changed}` |
| `set_lockfile_entry_pinned` | `pin_lockfile_entry` + `unpin_lockfile_entry` | `{projectId, inputHash, pinned: Boolean}` | `{projectId, inputHash, toolId, assetId, pinnedBefore, pinnedAfter, changed}` |

- `pinnedAfter` 永远等于 `Input.pinned`（echo），配合 `pinnedBefore` / `changed` 让调用方精确知道是否发生了真正的 mutation。这比旧 API 的 `alreadyPinned` / `wasUnpinned` 更对称，不需要跨两个工具对比字段语义。
- 两个工具共用同一个 `changed` 语义：`pinnedBefore != pinnedAfter`。再次调用相同 pinned 值是显式幂等 no-op。
- `SetClipAssetPinnedTool.kt` 内部保留了 `assetIdForClip` 私有 extension fn 做 `ClipId → AssetId` resolution，行为与旧 `PinClipAssetTool.kt` 的私有 helper 完全一致（包括针对 text clip 的 "text clip (no asset)" loud failure 和针对 ghost clip 的 "project_query(select=timeline_clips)" 提示）。
- `SetLockfileEntryPinnedTool.kt` 对未知 `inputHash` 继续抛 `IllegalStateException` + `list_lockfile_entries` 提示，保持原有 loud-fail 契约。

**文件动作**：
- 新增 2 个文件：`SetClipAssetPinnedTool.kt`、`SetLockfileEntryPinnedTool.kt`。
- 删除 3 个文件：`PinClipAssetTool.kt`（含 PinClipAsset + UnpinClipAsset + assetIdForClip helper）、`PinLockfileEntryTool.kt`、`UnpinLockfileEntryTool.kt`。
- 测试重命名 + 重写：`PinClipAssetToolTest.kt` → `SetClipAssetPinnedToolTest.kt`、`PinLockfileEntryToolTest.kt` → `SetLockfileEntryPinnedToolTest.kt`。测试用例数从 8 + 8 变成 8 + 8（同构移植：pin/unpin 路径改为 `pinned=true/false` 参数化，idempotency 路径断言 `changed=false`）。
- 5 端 AppContainer（CLI / Desktop / Server / Android / iOS）各自删除 4 行旧注册、加 2 行新注册（含 import 调整）。
- doc / helpText 交叉引用更新：`RegenerateStaleClipsTool.kt`（两处：helpText 的 `pin_lockfile_entry` → `set_lockfile_entry_pinned`；KDoc 的 `unpin_lockfile_entry` → `set_lockfile_entry_pinned pinned=false`）、`GcLockfileTool.kt`（helpText）、`ListLockfileEntriesTool.kt`（多处 helpText / KDoc）、`Lockfile.kt`（数据类 KDoc）、`ArchiveSessionTool.kt`（KDoc 参考）。

`docs/decisions/2026-04-21-pin-lockfile-entry-for-hero-shot-aigc.md` 和 `docs/decisions/2026-04-21-pin-clip-asset-clip-level-shortcut.md` **不改**——历史决策 artifact 保留原名作为考古依据，新决策文件显式说明了演化关系。

**LLM context 成本（§3a.10）**：pin/unpin 旧 4 工具每个约 250–280 tokens 的 spec + helpText + schema。合并后 2 工具各约 280–320 tokens（多了 `pinned` 参数 + 更详细的 upsert 语义说明）。估算：旧 ~1100 tokens → 新 ~620 tokens。**每个 turn 节省约 480 tokens**，折算每天数千 turn 是可感收益。

**Alternatives considered.**

1. **Option A (chosen)**: 2 个 upsert 工具，`pinned: Boolean` 参数。优点：契合 §3a.2 / `set_character_ref` / `rename_session` 的既有命名惯例；Input/Output 字段 `pinnedBefore` / `pinnedAfter` / `changed` 对称；tool count 净 -2；LLM context 净减约 480 tokens/turn。缺点：`Input.pinned` 是新的 required 字段，历史调用方（如果有人 replay old tool calls）无法直接迁移——但 Tool spec 不持久化，每次 `Agent` 跑都从 registry 重新注册，所以这个约束不存在。
2. **Option B**: 保留 4 个工具，合并 Pin/Unpin 的实现到共享 helper（纯代码 dedup）。拒绝：tool count 不减、LLM context 不降、§3a.2 劣化信号依然存在。只是码侧整洁，不是本 debt 的目标。
3. **Option C**: 做成 3 个工具：`pin_or_unpin_lockfile_entry(inputHash, pinned)` + `pin_or_unpin_clip_asset(clipId, pinned)` + 保留一个 `toggle_lockfile_entry_pin`（自动翻转）。拒绝：`toggle_*` 对 LLM 来说需要先查当前状态再决定是否发起——等于把状态推断成本推给模型；显式 `pinned: Boolean` 更鲁棒。另外 3 工具比 2 工具多 1 份 spec 成本，无收益。
4. **Option D**: 只合并 lockfile 那对，保留 clip 那对（hash 维度用 upsert，clip 维度保持 "shortcut" 命名）。拒绝：两个维度语义完全同构（都是布尔 upsert），切分不一致会让 LLM 更难记住规则；一次拍平所有 4 个工具比分两次做更干净。
5. **Option E**: 把 `inputHash`-维度合并进 `set_clip_asset_pinned`，让它接受 `clipId XOR inputHash`。拒绝：混合 identifier 在一个 Input 里违反 XOR 正交原则（`ListLockfileEntriesTool` 与 `describe_lockfile_entry` 的分离正是因为 hash 和 asset 是两个查询维度）；两个工具各司其职更清晰。历史上 `pin_lockfile_entry` + `pin_clip_asset` 并存就是因为专家流程（按 hash pin）和小白流程（按 clip pin）**语义不同**——不能用"同一个 API 两种 identifier"简单融合。两个 `set_*_pinned` 维持维度分离，只是各自内部吃 Pin+Unpin。

**Coverage.**

- `SetClipAssetPinnedToolTest`（8 tests）：pin 视频 clip、pin 已 pinned 是幂等（`changed=false`）、unpin 清 pin、unpin 已 unpinned 是幂等、text clip loud fail、missing clipId loud fail (含 `project_query(select=timeline_clips)` 提示断言)、imported-media loud fail (含 `set_lockfile_entry_pinned` 提示断言)、audio clip 同样能 pin。
- `SetLockfileEntryPinnedToolTest`（8 tests）：pin entry 并 report changed、pin 已 pinned 是幂等、pin 不影响其他 entries、missing hash loud fail (含 `list_lockfile_entries` 提示)、missing project loud fail、unpin 清 pin、unpin 已 unpinned 是幂等、unpin missing hash loud fail。
- `./gradlew :core:jvmTest` 全绿（31s）。
- `./gradlew :core:ktlintCheck` 全绿。
- 5 端构建：`:core:compileKotlinIosSimulatorArm64`、`:apps:android:assembleDebug`、`:apps:desktop:assemble`、`:apps:server:assemble`、JVM core 全部成功。

**Registration.** 5 个 `AppContainer` 改动（CLI / Desktop / Server / Android / iOS）：每个删 4 行旧 `register(...)` 加 2 行新 `register(...)`，import 同步换。iOS 用 SKIE 导出的 Swift API（`SetLockfileEntryPinnedTool(projects:)` 等），自动生成无需手写。

**§3a 自查.**
1. Tool count: **-2**（4 删 + 2 新）。这就是债的修复目标。PASS。
2. Define/Update 成对: 本轮正是 §3a.2 的显式 remedy。`set_*_pinned` 对齐 `set_character_ref` / `rename_session` upsert 惯例。PASS。
3. Project blob: 不动 `Project` 字段。`LockfileEntry.pinned` 早已存在。PASS。
4. 状态字段二元: `pinned` 早是二元布尔，本轮不引入新状态字段也不改变语义。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: `projectId: String` 仍是显式参数。`tool-input-default-projectid-from-context`（P1 backlog 另一条）会统一处理此类 case，本轮不动。PASS。
7. 序列化向前兼容: 新的 `@Serializable` Input / Output 所有字段都是 required（`pinned` 没 default——这是 Input 契约的一部分，必须显式）或带 default（Output 无需 default 因为仅作为返回值）。旧 Pin*Tool / Unpin*Tool 的 Input/Output JSON 格式彻底删除，不持久化所以无回滚风险。PASS。
8. 5 端装配: 5 个 AppContainer 全部同步更新。PASS。
9. 测试语义覆盖: 16 个用例覆盖 pin / unpin / 幂等 / 错误路径 / 跨 entry 隔离 / 不同 clip 类型等边界。PASS。
10. LLM context 成本: 净 -480 tokens/turn。PASS（实际是收益不是成本）。

**Non-goals / 后续切片.**
- 本轮只处理 pin/unpin 这一对。后续 `archive_session` / `unarchive_session`（存在同样 Pair 问题）已在历史 decision 里以 `set_session_archived` 形态提前讨论过但未实施，可作独立 cycle 题材。今天不一揽子动，避免变更面太大。
- `tool-input-default-projectid-from-context`（P1 另一条）将使新的两个 set_*_pinned 工具的 `projectId` 变成可选（从 `ToolContext.currentProjectId` 自动取），届时 LLM context 成本还能再降。
- 旧决策文件（`2026-04-21-pin-lockfile-entry-for-hero-shot-aigc.md` / `2026-04-21-pin-clip-asset-clip-level-shortcut.md`）保留不改——考古用。
