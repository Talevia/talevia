## 2026-04-21 — lockfile-byhash-index：byInputHash / byAssetId 反序列化自动重建（VISION §5.3 rubric）

Commit: `e216f30` (pair with `docs(decisions): record choices for lockfile-byhash-index`).

**Context.** `Lockfile.findByInputHash` / `findByAssetId` 都是对 `entries: List<LockfileEntry>` 的 `lastOrNull { it.xxx == key }` 线性扫描（`core/src/commonMain/kotlin/io/talevia/core/domain/lockfile/Lockfile.kt`）。原注释承认 "~100s of entries OK, if we blow that we add a `byHash` transient index the same way `Source.byId` works"。这是项目里少数明确标注了"何时补优化"的债条目之一：触发条件就是项目规模扩张（长叙事片、多 hero shot reruns、cache 命中反查多）。`Source.byId`（`core/domain/source/Source.kt`）已经落地了完全一样的 `@Transient val byId: Map<_, _> = nodes.associateBy { it.id }` 模式，没理由这一侧继续拖 O(n)。Backlog P1 #2 直接挑出这项。

**Decision.** 按 `Source.byId` 的形状在 `Lockfile` 上新增两个 `@Transient` 字段：

```kotlin
@Transient
val byInputHash: Map<String, LockfileEntry> = entries.associateBy { it.inputHash }

@Transient
val byAssetId: Map<AssetId, LockfileEntry> = entries.associateBy { it.assetId }
```

两者都在主构造器参数 `entries` 可见的默认参数上下文里 inline 求值 —— data class 构造时跑一次、`copy()` 后自然按新 `entries` 重建、kotlinx.serialization 反序列化也走构造器所以 decode 后自动回填。`findByInputHash(hash)` / `findByAssetId(id)` 改为直接走对应 map 的 `[key]` 查询（O(1)）。KDoc 同步把 "~100s of entries OK" 的讲法换掉，明确标注 last-wins 语义靠 `List.associateBy` 的 "later overwrites earlier" 保证。

**语义保真.** 原实现 `entries.lastOrNull { ... }` 返回同 key 的**最新追加**条目。Kotlin stdlib `List.associateBy` 的文档明确："If any two elements have the same key ... the last one gets added to the map." 插入顺序 = `entries` 的 list 顺序 = append 顺序，所以 map 的最终值就是最新条目 —— 和 `lastOrNull` 完全等价。新增 property 测试 `lastAppendedWinsForSameHash` 以 10 次随机 shuffle 的插入顺序验证这一点，防止未来有人把 `associateBy` 错改成 `groupBy(...).first()` 之类 first-wins 形态。

**Alternatives considered.**

1. **Option A（选）：`@Transient` inline default-init，`entries.associateBy { ... }`。** 和 `Source.byId` 形式一致，可读性高；反序列化后无需任何显式 rehydrate 代码，因为 kotlinx.serialization 解 data class 时会调默认构造器 → 触发默认值求值。新增 test `serializationRoundTripPreservesIndexLookup` 断言 encode/decode 之后 `byInputHash` / `byAssetId` 仍正确且**未**泄漏进 JSON。
2. **Option B：`by lazy { entries.associateBy { ... } }`.** 否决。lazy delegate 把 init 推迟到首次访问，省下 "构造但从未查询" 的场景；但 `Lockfile` 主要就是被 Agent / tool 反复读的——第一次查询立刻触发 init，省不下什么。且 `by lazy` 在 `@Serializable data class` 上和 `copy()` 的交互有些边缘情况（lazy 持有原对象上的 closure，copy 产生的新实例重新捕获 `entries` 但 lazy 状态不共享——实际不会错，但需要额外想一遍）。直接 default-init 少一层思考负担，性能差异可忽略（data class 主要是 copy on mutate，mutate 频率远低于 query）。
3. **Option C：`TreeMap` / 排序索引支持范围查询。** 否决。`findByInputHash` / `findByAssetId` 都是精确匹配；没有任何调用者做 "prefix" 或 "range" 查询。为不存在的需求付出 O(log n) 查询 + 更大常数没意义。

**Coverage.** 新增 `core/src/jvmTest/kotlin/io/talevia/core/domain/lockfile/LockfileByHashIndexTest.kt`（7 个测试用例）：

- `byInputHashIsPopulatedAtConstruction` / `byAssetIdIsPopulatedAtConstruction` —— 基础命中/未命中。
- `findByInputHashConsultsIndexAndReturnsLastAppended` / `findByAssetIdConsultsIndexAndReturnsLastAppended` —— 重复 key 下 last-wins（固定插入顺序）。
- `lastAppendedWinsForSameHash` —— property 测试：10 次随机 shuffle 的插入序列，每次都断言 map 返回的就是 shuffle 后最后一个 match 的条目（`assertSame` 用引用比较，进一步抓假阳性）。
- `serializationRoundTripPreservesIndexLookup` —— encode/decode 后索引正确重建，且 JSON 里不含 `byInputHash` / `byAssetId` 这两个键名。
- `emptyLockfileHasEmptyIndexes` —— `Lockfile.EMPTY` 的索引形状。

既有 `LockfileTest.findByInputHashReturnsLastMatching` 继续作为 smoke test 保留。`./gradlew :core:ktlintCheck :core:jvmTest` 全绿。

**Registration.** 无需注册 —— pure library internal optimization；不新增 tool、不改 `Project` 序列化 shape（`@Transient` 字段不进 JSON）、不触及任何 AppContainer。
