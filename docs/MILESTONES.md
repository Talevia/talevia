# Milestones

> **Current: M1 — 跨镜头一致性 e2e 闭环**

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

## M1 — 跨镜头一致性 e2e 闭环 （VISION §3.3）

**目标**：`character_ref` / `style_bible` / `brand_palette` 等 source 一致性约束
不只是 stale 标记，而是**在 AIGC 生成时物理注入 prompt / 参数 / LoRA pin**，
并在整个生命周期（clip 创建 → 重生成 → export）里保持绑定。"主角 50 个镜头
长得像同一个人" 要成为系统兜底，不能靠运气。

选 §3.3 作首个 milestone 的理由：VISION 明确点名这是"最硬的护城河"；首条 criterion
已完成（`foldPrompt` 物理注入在 cycles ≤ 53 已落地），开箱可勾一个 `[x]`，正好示范
机制能跑通；剩余 criteria 大多 grep-able（kind 数 / select 名 / test 存在性），
对自动 tick 友好。

### Exit criteria

- [x] 物理注入：`generate_image` / `generate_video` / `generate_music` 调用时通过
  `AigcPipeline.foldPrompt(...)` 把 consistency bindings 的描述 / 参考图 / LoRA pin
  折进 prompt（grep: `foldPrompt` 在 3 个 AIGC tool 中均出现） — *pre-M1 已落地*
- [ ] 绑定持久化：clip 能显式记录所绑定的 consistency source node id 列表，
  同一 clip 重生成不需要 LLM 再传一次 `consistencyBindingIds`（grep: `Clip` 有持久
  字段承载 consistency binding；非 `Part.Tool` input 参数）
- [ ] 绑定反查：`source_query` 或 `project_query` 能回答"哪些 clip 绑定了这个
  consistency node"（grep: 新 select 名如 `consistency_bound_clips` 或
  `binding_reverse` 注册到 dispatcher 的 ALL_SELECTS）
- [x] Kind 可扩证明：`ConsistencyKinds.ALL` 至少出现第 4 个成员（如 `lora_binding`
  / `color_palette`）+ 在 `PromptFolding` 的 when-branch 里正式处理（grep: `ALL`
  大小 ≥ 4） — cycle 2026-04-24 db5f5d2f
- [ ] 语义回归测试：改 `character_ref` 的可见字段（如 `name` / `appearance`）
  → 下游 clip stale → re-export 后产物 prompt 确实包含新值（grep: e2e test 带
  `character_ref` 字段改 → export → prompt 断言链条）
- [ ] Milestone 退出总结：在本文件 M1 block 末尾 append `### M1 exit summary`
  小段，列剩余的 §3.3 gap（LoRA fine-tune 产物持久化 / 多 character 交互
  一致性 / 音视频跨模态一致性 等）以便 M2 / M3 接力 —
  *必须手动 tick（段落存在 + 三条以上具体 gap）*

### 亚军 milestones（未正式启动，仅作排序参考）

- **M2 候选 — §3.1 AIGC 驯服产品化**：provider 数量、pin 命中率、成本可见、
  fallback 在生产环境有回归测试。
- **M3 候选 — §4 小白路径 e2e**：系统 prompt 引导 + 一句话出初稿 demo +
  两路径共享 source 的回归测试。
- **M4 候选 — §5.2 专家特效**：shader / 合成 / 粒子 / mask 的 Tool 接入成本。

---

（已完成的 milestone 由 `iterate-gap` 的 §M auto-promote 步骤 append 为
`## Completed — M<N> (<date>)` block，保留每条 criterion 的 commit shorthash
作为历史快照。最近完成的放最上面。）
