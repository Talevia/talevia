# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **auto-author-first-project-from-intent** — 小白路径 §5.4 的硬缺口：今天用户必须手动 `create_project` + 手动 `set_character_ref` / `add_source_node` 才能给 agent 投料。北极星是 "一句话意图 → 可看初稿"。**方向：** 新增 `start_project_from_intent(intent: String)` tool：LLM 调 agent 把 intent 解析成 genre（先覆盖 narrative / vlog），生成 skeleton source graph（character / style / shot placeholders），返回 projectId。不产生任何 AIGC 资产——只是搭好骨架让 agent 继续 fill in。Rubric §5.4。

## P1 — 中优，做完 P0 再排

- **generate-project-variant** — VISION §6 叙事 / vlog 例子明确点 "30s / 竖版 variant"，但当前没有一等抽象生成变体；用户必须手动 `fork_project` + `set_output_profile` + re-export。**方向：** `generate_variant(projectId, variantSpec: {aspectRatio?, durationSeconds?, language?})`：fork project、按 spec 调整 timeline（比例裁剪 / 按 key-shot 浓缩 / 重生成 TTS 变体）、write a child project id pointing back to parent。Rubric §5.2。

## P2 — 记债/观望

- **message-v2-schema-versioning** — `Message` / `Part` 当前没有 `schemaVersion` 字段。OpenCode `session/message-v2.ts` 是显式 v2 迁移产物。未来任何字段重构（譬如 Part 的 `MediaAttachment` shape 变化）会踩 §3a.7 序列化向前兼容。**方向：** 加 `schemaVersion: Int = 1` 到 Message + Part，decode 时 detect 版本 → route 到对应 migrator。现在不迁移，只为未来准备。Rubric 外 / §3a.7。

- **rate-limit-aigc-per-session** — 防止长跑 session 失控消耗 AIGC 额度。当前无上限。**方向：** 先记债：加 `SessionRateLimits` 占位类 + `rate_limit_aigc-per-session-recorded.md` 触发条件（cost/session 超 $X、每分钟 >Y calls）。暂不实现。Rubric 外 / 操作债务。

- **export-variant-deterministic-hash** — 同一 Project + 同 output profile 两次 export 是否 bit-identical？ffmpeg 默认非 deterministic（encoder order、timestamps）。`RenderCache` 假设一致性；如果实际不 bit-identical，cache 命中但产物不完全一样，可能破坏 regression 测试。**方向：** 写一个测试：同项目 export 两次，对比 SHA256。不一致就加 `-fflags +bitexact` 到 ffmpeg 命令、文档化哪些 codec option 必须固定。Rubric §5.2 / §5.3。

- **integration-test-real-provider-smoke** — 当前 provider 测试全 mock，真 API 没 smoke test。Anthropic SSE 格式变动 / OpenAI retire 旧 model 时测试察觉不到。**方向：** 加 `@EnabledIfEnvironmentVariable("ANTHROPIC_API_KEY")` 的一轮端到端 smoke test（创建 session、发一句话、断言返回含 text + stop reason），CI 不跑，本地可选。Rubric 外。
