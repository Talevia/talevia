## 2026-04-21 — export-variant-deterministic-hash：E2E test + ffmpeg `-fflags +bitexact` 守住 bit-identical export（Rubric §5.2 / §5.3）

Commit: `10c8ed3` (pair with `docs(decisions): record choices for export-variant-deterministic-hash`).

**Context.** `RenderCache` 用 `(project hash, output profile hash)` 作为 key 缓存 export 产物，隐含前提是：**同一 Project + 同一 OutputProfile 两次 export 产出 byte-identical mp4**。如果底层 ffmpeg 非 deterministic（encoder id、创建时间戳、libx264 多线程打包顺序、容器层 random padding），cache hit 会返回和"重新渲染一次"不同的字节——regression test 断言 sha256 就会 flake，用户两次按导出按钮得到"应该一样但略有不同"的产出。

Backlog 方向：写测试同 Project + 同 profile 两次 export，对比 SHA-256；不一致就加 `-fflags +bitexact`。

**Decision.** 两部分：

1. **新增 E2E 测试** `platform-impls/video-ffmpeg-jvm/src/test/kotlin/.../ExportDeterminismTest.kt`（~150 行）：
   - 生成固定 `testsrc` 源（2s / 320x240 / 24fps，ffmpeg lavfi 自身 deterministic）。
   - 用 `ImportMediaTool` + `AddClipTool` + `ExportTool` 驱动完整 core 工具链 export 两次到不同路径。
   - 计算两个 mp4 的 SHA-256，`assertEquals(hashA, hashB)`。
   - 当 ffmpeg 不在 PATH 时自动跳过（和 `FfmpegEndToEndTest` 相同 pattern）。
   - 测试失败消息包含两个文件路径 + hash，便于本地复现调试。

2. **在 `FfmpegVideoEngine.export` 里加 bitexact flags** 作为预防性 pin：
   - `-fflags +bitexact`（全局 format-level）：去掉 container encoder id 串 + 创建时间戳。
   - `-flags:v +bitexact`（video codec header level）：libx264 不写 encoder id。
   - `-flags:a +bitexact`（audio codec header level）：AAC 同理。

**发现**：在 ffmpeg 8.1 + macOS x264 上，**测试在不加 bitexact 时就通过**——当前 ffmpeg 版本的 libx264 在 `testsrc` 输入下已经 deterministic（single-threaded for small inputs、encoder id strip 不影响）。Bitexact 作为**跨版本安全网**仍保留：CI 可能跑更旧 / 更新 / 非 x264 的 ffmpeg 构建，届时 flag 立即生效；flag 本身开销可忽略（去掉几十字节的 metadata 串）。

`-threads 1` / `-x264-params deterministic=1` 等更激进的 pin **未加**——它们会把多核编码序列化（2-4× 慢），当前测试跑过时不必。如果未来跨环境出现非决定性（4K 输入触发 x264 线程并行、其他 codec），再在 decision followup 里 opt-in。

**文件动作**：
- 新增：`platform-impls/video-ffmpeg-jvm/src/test/kotlin/io/talevia/platform/ffmpeg/ExportDeterminismTest.kt`。
- 修改：`platform-impls/video-ffmpeg-jvm/src/main/kotlin/io/talevia/platform/ffmpeg/FfmpegVideoEngine.kt`（+3 行：`-fflags +bitexact` 在 args 初始；`-flags:v +bitexact` + `-flags:a +bitexact` 在 codec args 尾部）。
- 顺手：ktlintFormat 清了 4 个 AppContainer（CLI / Desktop / Server / Android）的过往 empty-line 残留（`find_*` 工具删除后留下的空行），纯 hygiene 化；不改行为。

**Alternatives considered.**

1. **Option A (chosen)**: 测试 + 无条件加 bitexact flag。优点：行为零回归（当前已 deterministic），测试守住未来漂移，flag 是安全网。缺点：flag 在某些 ffmpeg 版本下已被视为 default，加上是 no-op。无真实代价。
2. **Option B**: 只加测试，不改 ffmpeg args。拒绝：今天绿≠明天绿；ffmpeg 8.2 或某个 CI docker image 可能就不 deterministic，届时 test 红但我们没有先做 defensive pin，还要再单独 PR 加 flag。把"fix"和"test"放同一 cycle 更干净。
3. **Option C**: 加 `-threads 1` 强制单线程。拒绝：2-4× 慢；当前测试在 300ms 内跑完说明没必要。如果未来真出现多线程 non-determinism，再 opt-in（可能通过 `OutputProfile.deterministic: Boolean` 字段）。
4. **Option D**: 加 `-map_metadata -1` + `-movflags +faststart`。拒绝：`-map_metadata -1` 会擦输入元数据（可能用户故意保留 camera EXIF）；`-movflags` 改 moov atom 位置，影响流式播放，和 determinism 无关。bitexact 已经覆盖 encoder metadata，不需要这两个。
5. **Option E**: 测试改成 sha256 两次 export 然后 **如果不同则自动触发 retry + 加 flag**，并在 CI 报告里记录哪些 flag 必要。拒绝：测试应该是 spec，不是 heuristic 调优工具。deterministic 是契约，不是 best-effort。
6. **Option F**: 给 `OutputProfile` 加 `deterministic: Boolean = true` 字段，engine 根据字段决定要不要加 flag。拒绝：今天所有用户都希望 deterministic（否则 `RenderCache` 作假了）；当字段只有一个常用值时，字段本身是 noise。如果将来有合理用例需要"强制 non-deterministic"（生成唯一 watermark？）再加。

**Coverage.**

- 新增 `ExportDeterminismTest.sameProjectTwiceProducesBitIdenticalMp4`（1 test）：
  - Generate testsrc → two exports → `sha256(a) == sha256(b)` + length equal。
  - 失败消息包含 A/B 路径 + 两个 hash 值，方便本地 `diff <(xxd a.mp4) <(xxd b.mp4)` 调查。
- 既有 30 个 ffmpeg tests（`FfmpegEndToEndTest` 3 + `DrawtextChainTest` 10 + `FilterChainTest` 12 + `TransitionFadesTest` 5）全部继续通过——bitexact flag 不影响 filter graph / transition / drawtext 渲染路径。
- `./gradlew :platform-impls:video-ffmpeg-jvm:test` 全绿（31 tests）。
- `./gradlew ktlintCheck` 全绿（4 container 的 empty-line hygiene 顺手修了）。
- 4 端构建：iOS sim / Android APK / Desktop / Server 全部通过——FFmpeg engine 是 JVM-only，iOS/Android 走各自 engine（AVFoundation / Media3），不受此 flag 影响。

**Registration.** 无 AppContainer 改动（engine internals）。

**§3a 自查.**
1. Tool count: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 Project。PASS。
4. 状态字段: N/A（不引入新字段，也不改 OutputProfile）。PASS。
5. Core genre: N/A。
6. Session/Project binding: N/A。
7. 序列化向前兼容: N/A（不动 data class schema）。
8. 5 端装配: 不变化。PASS。
9. 测试语义覆盖: 1 个 E2E 测试覆盖 bit-identical export contract；失败消息含诊断信息。PASS。
10. LLM context 成本: 0（engine internals 不出现在 tool spec）。PASS。

**Non-goals / 后续切片.**
- 跨平台 engine 对等（Media3 Android / AVFoundation iOS）deterministic 性质——它们用不同 codec stack，bitexact 是 ffmpeg 专属概念。跨 engine byte-identical 不是目标（也不现实）；同 engine 跨 export byte-identical 是契约。
- 超过一个 video clip / 带 transition / 带 filter 的 deterministic 测试——当前 test 只覆盖 single clip + no transform 的基线。扩展测试覆盖复杂 timeline 的 deterministic 属性属于未来 cycle，如果发现复杂 graph 会漂移再做。
- `OutputProfile.deterministic: Boolean` 字段——今天所有 profile 都默认 deterministic（隐式 always-true 通过 engine 里写死的 flag）。如果将来出现"刻意要每次不同的 non-cacheable export"需求（per-export watermark），加这个字段把 engine 的 flag 通过字段开关；不是本轮目标。
- ffprobe 层面的 deterministic（import metadata 解析）——一般稳定，未测；如果将来发现 import 后的 MediaMetadata 两次不同，单开 cycle。
- Real-world videos（not lavfi testsrc）的 determinism——testsrc 足够诱发 encoder timestamp / id 漂移；真实视频样本如果跑红，加到 test 里。现在不预先扩大测试面。
