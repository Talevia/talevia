# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

## P1 — 中优，做完 P0 再排

## P2 — 记债 / 观望

- **auto-generate-proxies-on-import-ios** — iOS `AppContainer.swift` 仍然用 `NoopProxyGenerator`。4K import 在 iOS 上看不到 thumbnail。**方向：** 实现 `AVFoundationProxyGenerator` (AVAssetImageGenerator) 并在 iOS AppContainer 里 wire 进 `ImportMediaTool`。Rubric §5.3 parity。

- **progressive-export-preview** — `ExportTool.executeRender` 只发 start / complete 两个 `Part.RenderProgress`，长 export 中间没预览。VISION §5.4 "专家路径能中途接管"需要中途 artifact 可看。**方向：** `VideoEngine.render` flow 增加 `ProgressPreview(frameBytes, ratio)` 事件（FFmpeg 用 `-filter_complex showinfo`+每 N 秒 pipe 一张 JPG）；`ExportTool` 转成 `Part.RenderProgress` 的 `thumbnail` 字段更新。Rubric §5.4。

