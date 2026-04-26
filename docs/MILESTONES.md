# Milestones

> **Current: M2 — AIGC 驯服产品化**

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

## M2 — AIGC 驯服产品化 （VISION §3.1）

**目标**：把 AIGC 从"不可复现的黑盒"驯服成"随机编译器"——seed 显式、模型 / 版本
锁定、产物 pin 命中率可度量、provider 数量 ≥ 2 有 fallback、每次 AIGC 调用的成本
在 session 层可见。M1 已证明一致性 binding 能物理注入 prompt；M2 证明注入之后的
*生成过程本身* 是工程化可控的，不是"拼运气 + 事后补救"。

选 §3.1 作 M2 的理由：M1 亚军列表的头条候选；M1 的成果（consistency binding 持久化 +
反查 + 语义回归）只有在 AIGC 生成本身可复现时才有复用价值（否则"一致性绑定的
物理注入成功了" vs "但每次 generate 结果都漂" 就等于白干）。Rubric 上偏
§5.2（AIGC 面）+ §5.7（性能 / 成本）。

### Exit criteria

- [x] Lockfile 完整性：每个成功的 AIGC clip 都在 `project.lockfile.entries`
  产一条 `LockfileEntry`，含 `(modelId, modelVersion?, seed, parameters,
  inputHash, sourceBindingContentHashes)`；test 覆盖 "运行 AIGC tool →
  查 lockfile 里能找到对应 entry" 的 round-trip
  （grep: `class LockfileEntry` 在 `core/domain/lockfile/`；e2e test 带
  `project.lockfile.entries` 断言） — cycle 2026-04-24 3fde671a
- [x] Provider 多元：`ImageGenEngine` / `VideoGenEngine` / `MusicGenEngine` /
  `TtsEngine` **至少一个**长出第二个非 stub impl，证明 provider 抽象真的
  是 provider-agnostic 而非 replicate-hardcoded（grep: `class .* :
  ImageGenEngine` 或同类 interface 的 prod 实现在
  `core/src/*Main/` 或 `platform-impls/*/src/main/` 下计数 ≥ 2；
  `AgentProviderFallbackTracker` 有真实 chain 可填） — cycle 2026-04-26
  **此 commit**（`SeedanceVideoGenEngine` 接 Volcano Engine ARK 作为
  `VideoGenEngine` 的第二 prod impl，`OpenAiSoraVideoGenEngine` 之外的
  非 stub fallback；`ARK_API_KEY` 触发，三个 JVM container 均已 wire）
- [x] Pin 命中率可见：`project_query` 有 select 答 "export 了几次、有多少
  clip 的 asset 从 lockfile cache 命中（无需重跑 AIGC）"
  （grep: 新 select 如 `lockfile_cache_stats` / `pin_hit_rate` 在
  ProjectQueryTool ALL_SELECTS + handler 实现） — cycle 2026-04-24 08223ac3
- [x] 成本可见：`session_query(select=spend_summary)` 或等价 select 聚合
  per-session AIGC spend（至少按 provider 分档，有 tokens / USD 估算），
  同一 session 反复调用 `generate_image` 能看到数字累加
  （grep: `spend_summary` 或 `aigc_spend` 在 SessionQueryTool；handler
  消费 `LockfileEntry.cost` / `ProviderUsage` 类字段） — cycle 2026-04-24 7862ce7d
- [x] Fallback 生产回归测试：e2e test 覆盖 "provider A 抛 `ProviderError` →
  `AgentProviderFallbackTracker` 切 provider B → 产物仍正确落地"
  （grep: jvmTest 里有 `ProviderFallback` / `FallbackChain` 相关测试 +
  `assertEquals` 断言最终 assetId / provenance.providerId 是 B） — cycle 2026-04-24 c3bad022
- [x] Seed 复现证明：seed + inputs 相同时重跑 `generate_image` 得到 bit-identical
  `GenerationProvenance` + 同 assetId（或 lockfile cache-hit）——验证 Talevia
  的随机边界契约真的锁住了
  （grep: e2e test 运行 generate_image 两次 → assertEquals assetIds；
  `ReplayLockfileTool` 参与验证） — cycle 2026-04-24 e1adea8f
- [x] Milestone 退出总结：在本文件 M2 block 末尾 append `### M2 exit summary`
  小段，列剩余的 §3.1 gap（如 GPU-inference 本地跑 / 预算硬 cap 触发 /
  多 provider cost arbitrage / cold-start 优化 / cache invalidation
  latency 等）以便 M3 / M4 接力 —
  *必须手动 tick（段落存在 + 三条以上具体 gap）* — cycle 2026-04-24 4ee6a08b

### M2 exit summary

M2 关起门来证明了**AIGC 驯服产品化的 6/6 实质轴 + 1 manual exit 全部
落地**：lockfile 完整性 (criterion 1, 3fde671a / c30ecbb1) + provider
多元 (criterion 2, 此 commit — `SeedanceVideoGenEngine` 解锁 ByteDance
Seedance via Volcano Engine ARK，`VideoGenEngine` 现有 2 个 prod impl) +
pin 命中率可见 (criterion 3, 08223ac3) + 成本可见 (criterion 4, 7862ce7d)
+ fallback 生产回归测试 (criterion 5, c3bad022) + seed 复现证明
(criterion 6, e1adea8f)。这六条是 "AIGC 从黑盒变 deterministic compiler"
的工程学基石。

**M2 7/7 全勾 → 触发 §M.3 auto-promote 到 M3**。下一次 `iterate-gap §M`
cycle 会按照 milestone 机制把本 block 移入 `## Completed — M2 (2026-04-26)`
段、起草 M3 (§4 小白路径 e2e) 的 criteria 并把 `> Current:` pointer
从 M2 翻到 M3。本次 user-driven commit 只完成 criterion tick + exit
summary 更新，不抢先起草 M3 criteria（避免和 iterate-gap 自动起草
逻辑冲突；§M.5 的 "和触发该 promotion 的 feat commit 同 commit 落盘"
约束在 user-driven 解 block 场景下让位于职责边界）。

**§3.1 完整愿景里 M2+ 接力的 gap**（超出本 milestone 的工程学跑道，列给
M3 / M4 / 未来 M）：

1. **GPU-inference 本地跑**：`ImageGenEngine` / `VideoGenEngine` 现在全走
   Replicate 云端。本地 SD / local ComfyUI / MLX-diffusion 这类路径缺
   `LocalGpuImageGenEngine` impl + GPU-availability probing + fallback-
   to-cloud 策略。对 data-sensitive 创作者（不愿把素材传云）是必需品。
2. **预算硬 cap 触发**：`session_query(select=spend_summary)` 给了读口，
   但没有写口——session.metadata 里没有 `spendCapUsdCents: Long?` 字段，
   `Agent.run` 不在 AIGC tool 分发前检查 "this session spent > cap,
   refuse". `SessionRateLimits` 有骨架但不消费 spend_summary 数据。
3. **多 provider cost arbitrage**：已有 `provider_query(select=cost_compare)`
   答 "same prompt across providers — cheapest"；但 `AgentProviderFallbackTracker`
   只按 registry 顺序选 provider，不按 cost 挑。需要 `ProviderRoutingPolicy`
   抽象 + cost-aware fallback chain 排序。
4. **cold-start 优化**：首次 AIGC 调用 provider connection setup / model
   warmup / seed-pinning handshake 不可见。Session 起步第一个 image 生成会
   比后续慢很多，用户会以为卡死。需要 progress event（`BusEvent.ProviderWarmup`）
   + CLI/Desktop 对其的 UI surface。
5. **cache invalidation latency**：`project.lockfile` cache hit 检测走
   in-memory `byInputHash` map；跨 session 打开同一 project 会重建这个
   map 从 disk，但 JSON decode on large `entries` list (>500 items on
   a mature project) takes O(N) per open — profile 数据没收集。未来 perf
   bullet 可能要 `lockfile.jsonl` 分文件 + 增量加载。

前四条都指向 M3/M4/M5 的不同赌注；第五条是 §5.7 perf 轴的独立 backlog
项。M2 至此**功能上 + 产品上均 ready** — 第二 video provider 已选型 +
落地（Seedance / `ARK_API_KEY`）。

### 亚军 milestones（未正式启动，仅作排序参考）

- **M3 候选 — §4 小白路径 e2e**：系统 prompt 引导 + 一句话出初稿 demo +
  两路径共享 source 的回归测试。
- **M4 候选 — §5.2 专家特效**：shader / 合成 / 粒子 / mask 的 Tool 接入成本。

---

（已完成的 milestone 由 `iterate-gap` 的 §M auto-promote 步骤 append 为
`## Completed — M<N> (<date>)` block，保留每条 criterion 的 commit shorthash
作为历史快照。最近完成的放最上面。）

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
