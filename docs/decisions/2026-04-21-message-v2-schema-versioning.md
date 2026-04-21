## 2026-04-21 — message-v2-schema-versioning：Message / Part 加 `schemaVersion: Int = 1` 前向兼容钩子（§3a.7）

Commit: `1352de7` (pair with `docs(decisions): record choices for message-v2-schema-versioning`).

**Context.** `Message` / `Part` 的 JSON blob 今天没有版本字段。任何未来对这两个类型做的 rename / reshape / field-move 都会踩 §3a.7 序列化向前兼容红线——老 blob（SQLite `data` 列存的 JSON）decode 失败，session/part 数据变不可读。OpenCode `packages/opencode/src/session/message-v2.ts` 是他们已经踩过一次这个坑后显式留下的 v2 迁移产物。

本 cycle 不做任何 live 迁移。目的就是提前种一根"版本钩子"：**现在**加 `schemaVersion: Int = 1` 字段；**未来**需要改字段时，改 migrator 路由 `when (raw.schemaVersion)` 就能按 v1 → v2 解码，而不是靠字段名/存在性猜版本。

**Decision.** 新增 `core/session/Schema.kt` 包含两个常量对象：

```kotlin
object MessageSchema { const val CURRENT: Int = 1 }
object PartSchema { const val CURRENT: Int = 1 }
```

给 `Message` 的 2 个 subtype（`User` / `Assistant`）和 `Part` 的 10 个 subtype（`Text` / `Reasoning` / `Tool` / `Media` / `TimelineSnapshot` / `RenderProgress` / `StepStart` / `StepFinish` / `Compaction` / `Todos`）各加一个 `schemaVersion: Int = XxxSchema.CURRENT` 字段作为 data class 最后一个参数，KDoc 指向 [MessageSchema] / [PartSchema]。

**关键交互（`JsonConfig.default`）**：
- `encodeDefaults = false` — 今天所有 blob 的 `schemaVersion` 等于默认值 1 → JSON 里**不出现**该字段。on-disk 格式跟 pre-versioning 完全一致，老程序和新程序写出的 blob 字节级相同。
- `ignoreUnknownKeys = true` — 未来 v2 blob 多出来的字段，旧程序 decode 时直接忽略。
- default = 1 — 老 blob（没有 schemaVersion 字段）decode 时走字段默认值 → `schemaVersion=1`。和当时写入时的语义一致。

**迁移约定（文档化在 `Schema.kt` KDoc）**：
1. **绝不**把 `MessageSchema.CURRENT` / `PartSchema.CURRENT` 从 1 改成其他值。改常量意味着"已有老 blob 突然被当成新版本解读"——无声灾难。
2. 真到要改 schema 时，构造新 blob 时显式传 `schemaVersion = 2`（和 default 1 不同 → 被编码进 JSON）。老 blob 无字段 → default=1，新 blob 字段存在=2。decode 路径按 `when (raw.schemaVersion)` 路由到对应 migrator。
3. 本 cycle 不写 `when` 路由 —— 只种字段。第一次真实 migration 才加 decoder dispatcher。

**文件动作**：
- 新增：`core/src/commonMain/kotlin/io/talevia/core/session/Schema.kt`（38 行，两个 object + KDoc）。
- 修改：`Message.kt` — User / Assistant 各加一行 `schemaVersion` 字段。
- 修改：`Part.kt` — 10 个 subtype 各加一行 `schemaVersion` 字段。
- 新增：`core/src/jvmTest/kotlin/io/talevia/core/session/SchemaVersionTest.kt`（~130 行，8 个 test）覆盖四条不变量：
  1. `CURRENT` 常量等于 1（抓"未来有人改常量而忘写 migrator"的 bug）。
  2. 默认编码 → JSON 不含 schemaVersion 字段（pin `encodeDefaults=false` 的交互）。
  3. 老 blob（缺 schemaVersion）→ decode 成 schemaVersion=1。
  4. 显式 schemaVersion=2 → 被编码 AND round-trip → decode 回 2（模拟未来 v2）。

**Alternatives considered.**

1. **Option A (chosen)**: 每个 subtype 一个字段，`const val CURRENT` 常量，default = CURRENT。优点：改动本地化（只在 data class 签名尾部加一行）；Kotlin 默认参数机制天然解决"老 blob 缺字段"；未来 migrator 就是 when 分派；不碰 kotlinx.serialization 的 `PolymorphicSerializer` 内部。缺点：12 个 subtype 都要同步加字段；如果以后出现新 subtype 要记得加——但 R.5 debt 扫描会捕捉（grep `schemaVersion` 覆盖率）。
2. **Option B**: 把 `schemaVersion` 做成 `Message` / `Part` sealed 基类的 `abstract val`，每个 subtype `override val schemaVersion: Int = MessageSchema.CURRENT`。拒绝：`@Serializable` sealed 对 abstract property 的处理是 subtype-specific —— override 的 default value 在 deserialize 时等效于当前方案，但签名更冗长；且 refactor 时 base 添加/移除 abstract 字段对 serialization 契约有副作用。直接在 data class 里加 concrete field 更简单。
3. **Option C**: 单独搞一个 wrapper `Versioned<T>(val version: Int, val body: T)` 裹每个 Message / Part。拒绝：彻底改变 JSON wire format（所有老 blob 要迁移）；失去 polymorphic discriminator 好处；和本 bullet "forward-compat, no live migration" 的定位矛盾。
4. **Option D**: 在 `JsonConfig.default` 里加一个自定义 `JsonContentPolymorphicSerializer` 统一注入 `schemaVersion` 字段。拒绝：需要覆盖 13 个 serializer；复杂度远超需求；数据类字段方案已经够用。
5. **Option E**: 什么都不做，等真需要 migration 时再加。拒绝：§3a.7 明说 "加新 @Serializable 字段必须有 default" —— 没有 schemaVersion 字段时，future 加该字段本身就是一次 schema 变更（老 blob 没这个字段，新解码器拿不到 default 除非带 default）。有 default 这条能自救，但失去了**主动知道这是哪个版本**的能力。先种钩子，成本低、收益明确。
6. **Option F**: 把 schemaVersion 放在 JSON 的 top level 而不是每个 subtype（如 `{"type":"text","_schema":1,...}`）。拒绝：kotlinx.serialization 的 class discriminator 已经占用 "`type`" 字段；额外引入 "`_schema`" 需要 custom serializer；而且不同 subtype 将来可能演进速度不同（Part.Tool 变 v2 时 Part.Text 还在 v1），每个 subtype 独立版本号更灵活。

**Coverage.**

- 新增 `SchemaVersionTest` 8 个 test：
  - `currentConstantsPinnedAt1` — 把 `MessageSchema.CURRENT == 1` / `PartSchema.CURRENT == 1` 钉死。任何人改这两个常量 → 红。
  - `defaultMessageRoundTripOmitsSchemaVersionField` — `JsonConfig.default.encodeDefaults = false` 的交互保证：默认值不落入 JSON。
  - `decodeLegacyMessageBlobWithoutSchemaVersionField` — 用 `buildJsonObject` 构造 "no `schemaVersion` key" 的老 blob，decode → schemaVersion=1。
  - `futureSchemaVersionMessageIsEncodedAndRoundTrips` — 显式 schemaVersion=2，确认落入 JSON 且回读是 2。
  - Part 版本的三个对应（`defaultPartRoundTripOmitsSchemaVersionField` / `decodeLegacyPartBlobWithoutSchemaVersionField` / `futureSchemaVersionPartIsEncodedAndRoundTrips`）。
  - `unknownFutureFieldIgnoredByDecoder` — 模拟未来 v2 blob 多了一个我们还没学的字段，`ignoreUnknownKeys=true` 让老代码不炸。
- 既有 session-lane 测试（SqlDelightSessionStoreTest 等）全部绿 —— 因为 schemaVersion 字段有 default，老代码构造 Message / Part 的位置零改动。
- `./gradlew :core:jvmTest` 全绿。
- `./gradlew :core:ktlintCheck` 全绿。
- 4 端构建：iOS sim / Android APK / Desktop / Server / JVM core 全部通过。

**Registration.** 无 AppContainer 变化。不是 LLM 可见 tool。纯 Core schema 钩子。

**§3a 自查.**
1. Tool count: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 Project（改的是 Message / Part）。PASS。
4. 状态字段: `schemaVersion: Int` 不是二元 flag，是版本整数。PASS。
5. Core genre: N/A。
6. Session/Project binding: N/A。
7. 序列化向前兼容: **本轮就是 §3a.7 的显式扩展**。所有新字段都有 default = 1；旧 blob 无字段 → decode 成 1；新 blob 显式写 2 → 编码成 2。先向前一层防御。PASS。
8. 5 端装配: 不变化。PASS。
9. 测试语义覆盖: 8 个 test 覆盖 default encode / legacy decode / explicit-future-version / unknown-field-ignored 四类边界。PASS。
10. LLM context 成本: 0（内部 schema，非 LLM tool spec）。PASS。

**Non-goals / 后续切片.**
- 真正的 migrator（`when (raw.schemaVersion) { 1 -> migrateV1ToV2(raw) }` 分派）在第一次真实 Message/Part 字段变更时加。现在没有真 migration 场景，预先写 dispatcher 就是 dead code。
- `Session` 本身的 `schemaVersion`：暂未加，因为 `Session` 相对稳定（title / projectId / currentProjectId / archived 等基础字段）；加会是分离 cycle。如果 Session 先变得易动，参照本轮模式扩到 Session。
- 数据库 schema 版本（`PRAGMA user_version`）是**表层 migration**，和本 JSON blob 版本不同 —— 两者并行存在：表结构改动 → `user_version` 升；blob 字段改动 → `schemaVersion` 升。decision KDoc 里没展开这层对比，但两者不会混淆（各自语义域）。
