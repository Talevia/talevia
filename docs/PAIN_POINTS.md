# Architectural pain points

收集对 **`CLAUDE.md` 硬规则 / `docs/VISION.md` 不变量 / 平台优先级** 的
反馈 —— 定期（每季度 / 每次 `docs(backlog)` repopulate 批次）评审这些硬
规定合理性的输入。

## Scope filter（关键判别）

实现 / 验证中遇到一个痛点时，先问：这个观察在挑战一条写进 `CLAUDE.md`
红线、`VISION.md` 不变量、或 `VISION.md` 平台优先级的**硬规定**吗？

- **是** → append 一段到本文件。这里的条目会在下次硬规则评审时被拿出来
  逐条过：规定是否仍然成立？证据是否足以修改规定？
- **否**（只是 `/iterate-gap` skill 步骤本身有 friction 或设计有改进空间）
  → 走 `docs/BACKLOG.md` 的"顺手记 debt"通道，命名 `debt-skill-<slug>`，
  下次专门调度 skill-level 改动时处理。
- **工程 gotcha / 正面范式**（Kotlin 语言陷阱、coroutines / Flow 测试、
  库边界 `assertFailsWith` 脆弱、build / lint 细节、refactor judgement
  范式） → append 到 `docs/ENGINEERING_NOTES.md` 对应主题小节。

判别的本质：**PAIN_POINTS 是"改规定"的输入，不是"记录所有实施摩擦"的
catch-all。** 把 skill-process 友 friction 和硬规则反馈混在一起会让真正
需要重评估的条目被大量低信噪观察淹没（这是 2026-04-22 前本文件的实际
状态，当日一次性裁剪分流）。

## 格式

Cycle-dated section：`## <YYYY-MM-DD> — <slug> (<short-sha>)`。每段
3–10 行观察，锚定到具体 commit。

Append-only —— 不编辑 / 重排历史条目。一条观察在其挑战的硬规则被更新后
（通过 decision 文件记录）可由下一次硬规则评审一并裁剪，删除动作在那次
评审的 commit 里留 diff。

## History

2026-04-23 之前本文件混用三种 scope（硬规则反馈 / skill tweak / 工程
gotcha）共 20+ 条。当日一次性裁剪到仅"硬规则 / VISION-level 反馈"
scope：skill tweak 折进 `.claude/skills/iterate-gap/SKILL.md` v2（见
`docs/decisions/2026-04-23-iterate-gap-skill-v2-rule-distillation.md`），
工程 gotcha 抽到 `docs/ENGINEERING_NOTES.md`。本文件此后只承载挑战
`CLAUDE.md` / `VISION.md` 级别规定的反馈。

---

## 2026-04-23 — bundle-cross-process-file-lock (`<commit-pending>`)

### Cross-process correctness via `FileChannel.tryLock` is one-line on JVM but no equivalent on iOS/Android native

JVM's `java.nio.channels.FileChannel.tryLock()` gives us cross-process
exclusion with ~15 lines in `JvmBundleLocker`. The equivalent on
Kotlin/Native for iOS would be `flock(2)` through cinterop (non-trivial;
requires `posix` imports + `memScoped` + nullability plumbing). Android
runs on Dalvik/ART which has `FileChannel` but a per-app sandbox makes
multi-process-on-same-bundle vanishingly rare. So today the abstraction
is: `interface BundleLocker` in commonMain, `JvmBundleLocker` in jvmMain,
default `BundleLocker.Noop` everywhere else. This is fine — but the
implicit assumption "iOS / Android are single-process-per-bundle" is
genuinely a platform limitation now baked into core. If a future cycle
ever needs cross-process bundles on mobile (e.g. shared iCloud folder
accessed by Talevia + a sibling helper app), it's not a simple "add an
iosMain actual" — it's a cinterop + test-harness expansion.

**Hard rule challenged**: `CLAUDE.md` §"Platform priority — 当前阶段"的
"mobile 不退化底线"暗含的"mobile 并发模型 = 单进程"假设。当前合理（mobile
priority 窗口未开）；mobile 并发 driver 出现时（iCloud 共享 bundle / 多
app 协作等）需要把 `BundleLocker` 的 iOS/Android `actual` 实现 +
cinterop 工具链升级一次性计入 scope estimate，否则会低估实际工作量。

---

## 2026-04-25 — ios-swift-validation-gap (`<commit-pending>`)

### `:core:compileKotlinIosSimulatorArm64` is not a Swift type-check — iOS app silently rots through every fold cycle

CLAUDE.md "Build & run" 表把 `./gradlew :core:compileKotlinIosSimulatorArm64`
+ `./gradlew :apps:android:assembleDebug` 当作 iOS / Android 的 every-target
validation。但前者只编译 KMP shared module 的 iOS framework — 完全不碰
`apps/ios/Talevia/**/*.swift`。这意味着每次给 dispatcher 加 action +
删旧 standalone tool 时，4 个 Kotlin AppContainer 是验证过的（CLI / Desktop /
Server / Android `assembleDebug` 都跑），但 iOS 的 `apps/ios/Talevia/Platform/AppContainer.swift`
里的 `tools.register(SetSessionSpendCapTool(...))` 一类的引用静悄悄成为悬
空符号。Cycles 136–147 累计了 12+ 处这种漂移（cycle 147 调研时发现
`ImportSourceNodeTool` / `DescribeSourceNodeTool` / `FindStaleClipsTool` /
`ValidateProjectTool` / `CreateProjectFromTemplateTool` / `EstimateSessionTokensTool` /
`SetToolEnabledTool` / `SetSessionSpendCapTool` / `ForkSessionTool` /
`ExportSessionTool` / `RevertSessionTool` / `CompactSessionTool` / `GetProjectStateTool`
全部还是 Swift 端的 dangling symbol）。

**Hard rule challenged**: CLAUDE.md "Build & run" 的 "Every target + every test"
gradle 命令 + "iOS 不退化底线"。当前命令是
`./gradlew :core:jvmTest :platform-impls:video-ffmpeg-jvm:test :apps:server:test :apps:desktop:assemble :core:compileKotlinIosSimulatorArm64 :apps:android:assembleDebug`
—— 注意 iOS 那一项**不是** `:apps:ios:assemble` 类的 Xcode invoke，是只做 framework
link。真实的 iOS 不退化保证至少需要 `cd apps/ios && xcodegen generate && xcodebuild build -workspace Talevia.xcodeproj -scheme Talevia -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO`
之类的步骤，否则"compile 通过"的承诺只覆盖了 KMP framework 暴露面，不覆盖
Swift consumer。这条债现在挂了 P0 bullet `debt-ios-swift-catchup-tool-renames`
专门 sweep 一遍并把 xcodebuild 拉进 every-target —— 但根本上是验证 pipeline
的设计漏洞：fold cycle 完全照规矩跑了所有"应跑"的 target，问题仍然累积。

**短期 mitigation**：把 `xcodebuild` 加入 every-target gradle command；中期
mitigation：fold/rename refactor 时显式 grep `apps/ios/Talevia` 看引用；长期
mitigation：在 KMP common 层用 typealias / sealed registry 让 Swift 端按符号
而非按构造名引用 tool（KMP 的 dispatcher pattern 是 Kotlin object，Swift 端
能直接拿到 enum-like 接口）。


## 2026-05-03 — dual-roster-drift-listmodels-llmpricing (cycle 313)

**Observation**: Three real bugs in three cycles, all the same shape: an id
that names a model OR a provider drifts between `LlmProvider.listModels()`
and `LlmPricing.find()`. The lookup is nullable, so missing entries =
silent uncosted calls (or, in cycle 312's case, silent demotion in
`CheapestFirstPolicy` via "unknown pricing").

- cycle 311 fixed `claude-haiku-4-5-20251001` (listModels) vs `claude-haiku-4-5`
  (LlmPricing).
- cycle 312 fixed `gemini` (GeminiProvider.id + canonical) vs `google`
  (LlmPricing.PROVIDER_GOOGLE + EnvProviderAuth map key).
- cycle 313 surfaces three more (banked as observation pins, not yet fixed
  pending product decision):
  - `gpt-4.1` listed but unpriced;
  - `gpt-5.4` / `gpt-5.4-mini` priced under "openai" but reachable only
    through `OpenAiCodexProvider` ("openai-codex") — same shape as 312;
  - `gemini-2.0-flash` listed but unpriced.

**Hard rule / VISION challenged**: CLAUDE.md §"Provider abstraction is
SDK-agnostic" is satisfied at the *interface* layer (`LlmEvent` is provider-
neutral) but the **model registry is duplicated across two flat tables**
in different files. `listModels()` returns hardcoded `ModelInfo` lists in
each `*Provider.kt`; `LlmPricing.kt` holds an independent hardcoded
`Entry` list. There is no single source of truth coupling them, and no
compile-time guarantee that they agree. Three drift bugs in three cycles
suggest the duplicate-roster design will keep producing this bug class
unless reified.

**Resolution candidates** (left for next driver / repopulate cycle to
weigh):

1. **Single `ModelRegistry` with both ModelInfo + pricing** in one
   declaration: each provider declares its full roster
   `(modelId, contextWindow, capabilities, inputRate, outputRate)`
   in one place; `listModels()` and `LlmPricing.find()` derive views
   over it. Pricing-without-listing or listing-without-pricing become
   compile-time impossible.

2. **Cross-coupling pin tests** (cycle 313 banked these as
   `ProviderListModelsLlmPricingCrossCouplingTest`): cheap, no
   refactor, but every new model still needs both layers updated by
   hand — the test only catches drift at PR time.

3. **`Provider.pricing()` method** on the LlmProvider interface:
   forces each provider to vend its pricing alongside its models.
   Pricing entries can stop being a flat-file table; LlmPricing.find
   becomes a registry-walk.

The pin file is the cheap mitigation. The single-registry refactor is
the structural fix; it has not been picked up because the BACKLOG has
no driver beyond "we've shipped this bug 3 times" — but cycle 311/312/313
together IS that driver, and the PAIN_POINTS append is meant to make
that visible to the next architectural-debt sweep.
