# Milestones

> **Current: M3 — §4 小白路径 e2e**

迭代聚焦的粗粒度指针。每个 milestone 对应 `docs/VISION.md` §3 的一个**核心赌注**。
机制参考 media-engine 的 `docs/MILESTONES.md` + 三处本地化：**软优先级**（当前 M
的 bullet 同档内靠前，不降非当前 M）、**evidence 能 grep 才自动 tick**、纯主观
criterion 必须配 milestone exit append 段落才能勾。rationale 随引入该机制的
commit 一起落盘（`git log --grep 'milestone mechanism'`）。

`iterate-gap` skill 的 `§M` 步骤自动读取本文件、tick 可验证 criteria、全勾后
auto-promote 下一个 M 并在本文件尾部 append `## Completed — M<N>` 段落
（含每条 criterion 对应的 commit shorthash + M<N+1> 目标 + criteria 起草逻辑），
和触发该 promotion 的 feat commit 同 commit 落盘。

Milestone 不装任务 —— 任务仍在 `docs/BACKLOG.md`，每条 bullet 用
`Milestone §M<N>` / `Milestone §later` tag 分类。下一次 repopulate 自动为新
bullet 打 tag；现有 bullet 不手动 backfill。

---

## M3 — §4 小白路径 e2e （VISION §4）

**目标**：证明 Talevia 同时服务小白用户（一句话出初稿）和专家用户（精修单帧）
两条路径**共享同一套 Project / Source / Tool Registry**，操作深度差异不分裂
系统。M2 把 AIGC 驯服成 deterministic compiler 之后，M3 验证从"用户意图"到
"可 export 时间线"的 agent-driven 闭环：source 创建 → AIGC 调度 → timeline
排列 → 专家可介入精修。

选 §4 作 M3 的理由：M2 亚军列表头条；M2 的 deterministic AIGC 是小白路径
agent-trajectory 跑通的前提（不可复现的 AIGC 让"一句话出初稿"永远像拼运气）；
§4 双用户张力是 VISION 三条核心战略（§3.1 / §3.3 / §4）里唯一**横跨 source +
compiler + agent + UX** 全栈轴的。

### Exit criteria

- [x] 一句话出初稿 e2e：`core/jvmTest` 里有 `OneShotDraftE2ETest`，模拟 LLM
  多步 trajectory（`source_node_action` × 2 + `generate_video` +
  `clip_action`），断言最终 `Project.source.nodes.size ≥ 2` +
  `Project.lockfile.entries.size ≥ 1` + `Project.timeline.tracks` 含有
  ≥ 1 clip 的 Video track（grep: `OneShotDraftE2ETest` test class 存在 +
  上述断言） — cycle 2026-04-26 *本 commit*
- [ ] System-prompt 引导：default agent system prompt 含 §4 双用户张力的
  语言锚词，让 LLM 在小白 / 专家模式下自动选择正确的"探索 vs 精修"姿态
  （grep: `core/agent/AgentSystemPrompt.kt` 或等价 source 中含
  `intent` / `skeleton` / `infer.*genre` / `double-user` / `小白` / `专家`
  之一）
- [ ] 两路径共享 source：jvmTest 验证小白模式产出的 `SourceNodeId` 可被
  `source_node_action(action="update_body")` 直接编辑，下游 clips 走 stale
  标记 → regenerate 的标准链路（grep: `CrossPathSourceSharedTest` 或类似 +
  `assertTrue(clip.staleByLockfile)` / 等价断言）
- [ ] 进度可见：cli / desktop UI 把 multi-step `BusEvent.AgentRunState` 翻译
  为 user-readable 进度行（"Step 3/8: generate_video"），不是黑盒 5-30s
  （grep: cli `Renderer` / desktop UI 订阅 `AgentRunState.steps` + 行
  template 含 `Step .*\d` / `\d+/\d+` 之类）
- [ ] Failure-fallback 提示：Agent.run 终态 ERROR 时，agent 输出含"换 provider
  / 改 prompt / 让我介入"等 next-step 建议而非仅 stack dump，让小白用户知道
  下一步该做什么（grep: agent 错误处理路径中有 fallback suggestion 字符串
  `try.*provider` / `next.*step` / `换 provider` 之类）
- [ ] Cross-modal staleness 分区：`ProjectStaleness` 算法理解 modality
  （video / audio / both），改 `voiceId` 不会 stale 纯视觉 clip，反之亦然。
  M1 exit summary 列的"音视频跨模态一致性"接力（grep: `ProjectStaleness` 中
  有 `modality` 字段 + jvmTest 验证 voiceId 改不 stale 视觉 clip）
- [ ] Milestone 退出总结：在本文件 M3 block 末尾 append `### M3 exit summary`
  小段，列剩余的 §4 gap（视觉编辑 GUI / 项目模板 marketplace / 多用户协作
  等）以便 M4 / M5 接力 — *必须手动 tick（段落存在 + 三条以上具体 gap）*

### 亚军 milestones（未正式启动，仅作排序参考）

- **M4 候选 — §5.2 专家特效**：shader / 合成 / 粒子 / mask 的 Tool 接入成本。
- **M5 候选 — §3.2 依赖 DAG / 增量编译**：项目变更 → 最小重渲染集合，多 export
  场景不重复劳动。

---

（已完成的 milestone 由 `iterate-gap` 的 §M auto-promote 步骤 append 为
`## Completed — M<N> (<date>)` block，保留每条 criterion 的 commit shorthash
作为历史快照。最近完成的放最上面。）

## Completed — M2 (2026-04-26)

**目标**：把 AIGC 从"不可复现的黑盒"驯服成"随机编译器"——seed 显式、模型 / 版本
锁定、产物 pin 命中率可度量、provider 数量 ≥ 2 有 fallback、每次 AIGC 调用的成本
在 session 层可见。

**Exit criteria 完成情况**：

- [x] Lockfile 完整性：每个成功的 AIGC clip 在 `project.lockfile.entries`
  落一条 `LockfileEntry` (modelId, modelVersion?, seed, parameters, inputHash,
  sourceBindingContentHashes); round-trip e2e。
  — cycle 2026-04-24 **3fde671a** (`c30ecbb1` 也参与)
- [x] Provider 多元：`SeedanceVideoGenEngine` 接 ByteDance Volcano Engine ARK
  作为 `VideoGenEngine` 的第二 prod impl，证明 provider 抽象不是
  openai-hardcoded；`ARK_API_KEY` 触发，三个 JVM container 均 wire。
  — cycle 2026-04-26 **487c5989**
- [x] Pin 命中率可见：`project_query` 加 select 答 "export 几次、几条 clip
  从 lockfile cache 命中"。
  — cycle 2026-04-24 **08223ac3**
- [x] 成本可见：`session_query(select=spend_summary)` 聚合 per-session AIGC
  spend，按 provider 分档，tokens / USD 估算，反复调用累加。
  — cycle 2026-04-24 **7862ce7d**
- [x] Fallback 生产回归测试：`ProviderFallbackE2ETest` e2e 覆盖 "provider A
  抛 ProviderError → AgentProviderFallbackTracker 切 B → 产物正确落地"。
  — cycle 2026-04-24 **c3bad022**
- [x] Seed 复现证明：相同 seed + inputs 重跑 `generate_image` 得 bit-identical
  provenance + 同 assetId（lockfile cache-hit）。
  — cycle 2026-04-24 **e1adea8f**
- [x] Milestone 退出总结：M2 exit summary 段 + 5 条 §3.1 接力 gap（GPU-inference
  本地跑 / 预算硬 cap 触发 / 多 provider cost arbitrage / cold-start 优化 /
  cache invalidation latency）。
  — cycle 2026-04-24 **4ee6a08b**

### M2 exit summary

M2 关起门来证明了**AIGC 驯服产品化的 6/6 实质轴 + 1 manual exit 全部落地**：
lockfile 完整性 (3fde671a) + provider 多元 (487c5989, Seedance) + pin 命中率
(08223ac3) + 成本可见 (7862ce7d) + fallback 回归 (c3bad022) + seed 复现
(e1adea8f)。这六条是 "AIGC 从黑盒变 deterministic compiler" 的工程学基石。

**§3.1 完整愿景里 M2+ 接力的 gap**（超出本 milestone 的工程学跑道，列给
M3 / M4 / 未来 M）：

1. **GPU-inference 本地跑**：`ImageGenEngine` / `VideoGenEngine` 现在全走云端
   provider。本地 SD / local ComfyUI / MLX-diffusion 这类路径缺
   `LocalGpuImageGenEngine` impl + GPU-availability probing + fallback-to-cloud
   策略。对 data-sensitive 创作者（不愿把素材传云）是必需品。
2. **预算硬 cap 触发**：`session_query(select=spend_summary)` 给了读口，但没有
   写口——session.metadata 里没有 `spendCapUsdCents: Long?` 字段，`Agent.run`
   不在 AIGC tool 分发前检查 "this session spent > cap, refuse".
   `SessionRateLimits` 有骨架但不消费 spend_summary 数据。
3. **多 provider cost arbitrage**：已有 `provider_query(select=cost_compare)`
   答 "same prompt across providers — cheapest"；但 `AgentProviderFallbackTracker`
   只按 registry 顺序选 provider，不按 cost 挑。需要 `ProviderRoutingPolicy`
   抽象 + cost-aware fallback chain 排序。
4. **cold-start 优化**：首次 AIGC 调用 provider connection setup / model warmup
   / seed-pinning handshake 不可见。Session 起步第一个 image 生成会比后续慢
   很多，用户会以为卡死。`BusEvent.ProviderWarmup` 已存在但 CLI/Desktop 还无
   对其的 UI surface。
5. **cache invalidation latency**：`project.lockfile` cache hit 检测走 in-memory
   `byInputHash` map；跨 session 打开同一 project 会重建这个 map，但 JSON decode
   on large `entries` list (>500 items on a mature project) takes O(N) per open
   — profile 数据没收集。未来 perf bullet 可能要 `lockfile.jsonl` 分文件 +
   增量加载。

前四条都指向 M3/M4/M5 的不同赌注；第五条是 §5.7 perf 轴的独立 backlog 项。

## Completed — M1 (2026-04-24)

**目标**：跨镜头一致性 e2e 闭环（VISION §3.3）——consistency binding 不是
stale 标记而已，物理注入进 AIGC 生成并在生命周期保持绑定。

**Exit criteria 完成情况**：

- [x] 物理注入：`AigcPipeline.foldPrompt(...)` 在 3 个 AIGC tool 均使用。
  — *pre-M1 已落地*（milestone 机制起点时即完成，无专门 commit）
- [x] 绑定持久化：`Clip.sourceBinding: Set<SourceNodeId>` 承载，AIGC tool
  从 `folded.appliedNodeIds` 自动 populate，regenerate 经 lockfile.baseInputs
  无需 LLM 再传。— cycle 2026-04-24 **4ead8ad1**（skip-close 的 evidence
  cite，criterion grep 是散文无法 auto-tick 遂在此 M1 exit cycle 手动 tick）
- [x] 绑定反查：`project_query(select=clips_for_source, sourceNodeId=X)`
  返回直接 + 传递闭包下绑定的 clip 列表。— cycle 2026-04-24 **949a6551**
  （skip-close evidence；criterion grep 用了例子 slug 未命中实际 slug
  `clips_for_source` 遂在此 M1 exit cycle 手动 tick）
- [x] Kind 可扩证明：`ConsistencyKinds.ALL.size == 4`，新增
  `LOCATION_REF` + `LocationRefBody` + PromptFolding when-branch 处理。
  — cycle 2026-04-24 **db5f5d2f**（本 commit hash 在 MILESTONES 里记为
  db5f5d2f 但实际 push 是 amend 后的 4dc1fa28；shorthash 指针是 draft
  时记录的，整体 evidence 在 `git log -S LOCATION_REF`）
- [x] 语义回归测试：`RefactorLoopE2ETest` 添加 `capturedPrompts` +
  "seed prompt contains 原 visualDescription" + "regen prompt contains
  new value + NOT stale value" 断言，闭合 edit → stale → regen →
  prompt 链。— cycle 2026-04-24 **51aaf884**（同 db5f5d2f 的 pre-amend
  hash 注解，实际 commit `git log -S capturedPrompts`）
- [x] Milestone 退出总结：本 block + M1 exit summary 段。— cycle 2026-04-24
  0b47d198（m1-exit-summary-and-manual-ticks 的 feat commit；见其下的
  `### M1 exit summary` 段）

### M1 exit summary

M1 关起门来证明了**一致性 binding 能被物理注入、能持久化、能反查、机制
extensible、edit→stale→regen 全链路正确**。但 §3.3 的完整愿景还有几块 M2+ 要
接力：

1. **LoRA fine-tune 产物持久化**：`CharacterRefBody.loraPin` 已经能把 adapter
   id + weight 注入 AIGC 调用，但用户上传自己训的 LoRA → adapter id 的注册 /
   auth / upload / 版本管理流程还完全没有。目前要手动在 Replicate / Civitai
   这种外部 registry 里拿 adapter id 再粘进 body，这把"一致性"拆成"系统内 +
   外部 registry"两部分，脆弱。future M 应当建 `lora_binding` kind（
   LocationRef 之外的第 5 个？）承载 upload/train/serve 的本地生命周期。
2. **多 character 交互一致性**：当前 fold 是"character × N 各自拼字符串 append"
   —— 两个角色同时在镜头里时，fold 出来是 "Character A: ... Character B: ..."
   简单并列，模型还是要靠 prompt 语言能力把握谁是主谁是从、相对位置 /
   occluding / interaction。需要 `scene_interaction` 类的 source 层 primitive
   表达"两角色关系"，而不是让 LLM 每次在 base prompt 里重写。
3. **音视频跨模态一致性**：`CharacterRefBody.voiceId` 已经存在于 schema，
   `foldVoice` 也能 resolve，但当前 TTS + ImageGen + VideoGen 三路各自跑
   prompt fold，视觉 character + 听觉 voice 之间**没有 cross-modal 一致性
   check**（改了 voiceId 不会让依赖它的 speech clip stale，改了
   visualDescription 不会让 TTS 产物 stale）。需要 source DAG 记录跨模态
   依赖 + ProjectStaleness 算法理解"voice 的变化只影响 speech，visual 的
   变化只影响 image/video"的分区。
4. **consistency conflict detection**：多条 binding 互相冲突时（style_bible
   的 `negativePrompt: "warm"` vs character_ref 的 `visualDescription: "warm
   lighting"`）现在是盲 concat，模型收到自相矛盾的 prompt。应当有
   `project_query(select=binding_conflicts)` 静态扫 pair-wise 冲突 + 提示
   agent 解决。
5. **consistency drift 的跨 export 量化**：criterion 5 的 regression test
   证明了"edit → 新 prompt 有新值"，但**新值到新 asset 是否真的"变得更
   一致"**（视觉上/听觉上）没法自动验——需要加 perceptual metric（
   CLIP-similarity / face-embedding distance / voice-feature distance）的
   回归守护。这一步要外部模型依赖，M2/M3 不急，但是 consistency 真正的
   bottom line。

这 5 条不进 M2（M2 盯 §3.1 AIGC 驯服，正交议题）——由 future M（可能 M4 / M5）
把 "cross-modal consistency" / "interaction primitive" / "perceptual regression"
作为独立赌注接力。

## Completed — （更早 milestone 按 auto-promote 顺序 append）
