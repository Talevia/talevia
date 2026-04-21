## 2026-04-21 — platform-priority-doc-sync：CLAUDE.md 平台优先级对齐实际投入（Rubric 外 / docs）

Commit: `<feat-hash>` (single-commit docs cycle — this decision file ships in the same commit as the CLAUDE.md edit and the BACKLOG.md bullet deletion; self-referential hash accepted per docs-cycle convention).

**Context.** `CLAUDE.md` 的 "Platform priority — 当前阶段" 写着 iOS / Android "只维持不退化、不主动扩新特性"，但仓库实际状态：

- `apps/android/` 里有完整的 `Media3VideoEngine`；`apps/ios/` 通过 SKIE + xcodegen 把 core framework 集成进 SwiftUI 宿主，AVFoundation engine 提供 filter / transition / subtitle / LUT 全量能力。
- `CLAUDE.md` "Known incomplete" 首条本身已经承认：vignette / transitions / subtitles / LUT 四类渲染能力**在 FFmpeg + AVFoundation + Media3 三引擎对齐完成**。
- `Every target + every test` 的 gradle 命令显式包含 `:apps:android:assembleDebug` 和 `:core:compileKotlinIosSimulatorArm64`，即三平台编译是 CI 底线。

这层裂缝的实际危害：未来读 `CLAUDE.md`（人类或 LLM）会看到 "iOS / Android 不主动扩新特性" 就以为 Android Media3 不该动，结果下次 Core 加一个新 filter、FFmpeg / Media3 / AVFoundation 不齐了也不算破规 —— 但 "Known incomplete" 首条其实已经承诺了三引擎对齐。两段文字互相打脸。

**Decision.** 采用 **Direction B**：保留老的优先级表（Core > CLI > desktop > iOS > Android > Others 这个排序），但在 iOS / Android 条目上新增一段显式的 "不退化底线" 注解，把"已经达成的三平台对齐"写进底线、把"底线之外的主动扩展"仍然冻结。

Rationale（两句话）：
1. 原来的优先级表不是误写，是故意的 —— 用来阻止"顺手给 iOS 加个手势 / 给 Android 加个独立 UI 组件"这类机会主义增长抢走本该投 Core 的精力。这层意图今天依然成立（Core 还有大量 P1/P2 缺口）。Direction A（升级为 "Core > CLI > desktop ≈ iOS ≈ Android > server"）会变成"鼓励等量投资三端"的信号，反而和 VISION §4 / §5 的方向相反。
2. 真正的裂缝是"不退化底线"本身没定义 —— 到底什么算"不退化"？Direction B 把"三引擎已达成的渲染能力对齐（vignette / transitions / subtitles / LUT）"升格为显式底线：Core 新渲染能力在 FFmpeg 落地就要三端同步；但仍然禁止底线之外的主动扩展。这样 "Known incomplete" 首条和 "Platform priority" 条目互相印证，不再打脸。

**Alternatives considered.**

- **Direction A**：把优先级表改成 "Core > CLI > desktop ≈ iOS ≈ Android > server"。
  - Pro：一眼反映当下三端都有完整 engine 的事实。
  - Con：与 VISION §4（双用户张力 —— 当下在补 source/compiler 一等抽象）和 §5 rubric（先补 Core，再按平台过滤）冲突。会被读成"从今以后 iOS/Android 要等量投资"，引诱机会主义迭代。**拒绝**。

- **Direction B（采用）**：保留优先级表 + 显式加"不退化底线"注解。
  - Pro：意图（Core-first）和现实（三端 engine 已对齐）两件事同时说清。未来 Core 加新渲染能力时，三端对齐是明确要求、不是可选项；但 UI 层主动扩展仍受约束。
  - Con：CLAUDE.md 的 "Platform priority" 段落变长约两行；文档信息密度略降。可接受。

- **Direction C**：把 "Known incomplete" 首条的三平台对齐清单整条搬到 "Platform priority" 的底线注解里。
  - Pro：底线清单和现状清单合二为一，减少重复。
  - Con："Known incomplete" 的定位是"仍在收口的技术债 / 跟进事项清单"，"Platform priority" 的定位是"当前阶段投资方向"。两者视角不同，合并会让 "Known incomplete" 少一条已完成标记，后人读不到"这块曾经是 gap、现在关了"的历史。**拒绝**。

**Coverage.** 无代码改动。docs-only change。Grep-friendly 信号：未来任何人再发现 `CLAUDE.md` 的平台优先级和实际投入脱节（比如三引擎里某一端又落下了），回到 `docs/decisions/` 找 `platform-priority-doc-sync`，重新跑一次相同的判断 —— 是 Direction B 的底线该升级（更多能力算作"必须三端对齐"），还是原本就是底线之外的主动扩展（比如某端独立 UX）不该算违规。

**Registration.** 无需代码注册 —— pure docs note. 具体 copy 见同一 commit 里的 `CLAUDE.md` diff（"Platform priority — 当前阶段" 段落新增一条 iOS / Android 的不退化底线说明）。
