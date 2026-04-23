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
