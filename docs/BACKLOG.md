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

- **bundle-mobile-document-picker** — Android / iOS 当前限制于 app sandbox 内的 bundle (`<filesDir>/projects/` / `Documents/projects/`)。用户没法从 SAF / Files.app 选一个外部 bundle 打开。**方向：** Android 接 `Storage Access Framework` (`Intent.ACTION_OPEN_DOCUMENT_TREE`)，iOS 接 `UIDocumentPickerViewController`，结果 URI / NSURL 通过 platform-specific resolver 转成 Okio Path 喂给 `FileProjectStore.openAt`。Rubric §5.4 / mobile。 · skipped 2026-04-23: CLAUDE.md 平台优先级窗口未开 — mobile 仅维持"不退化底线"，此 bullet 属于超出底线的主动扩展。
- **bundle-talevia-json-split** — 当前 `talevia.json` 把 timeline + assets + source DAG + lockfile + snapshots 全装一个文件，单个 mutation 的 git diff 涨几百行；snapshot 多了文件可能涨到 MB 级。**方向：** 当真出现 diff 噪声时拆 `assets.json` / `timeline.json` / `lockfile.json` / `snapshots/<id>.json` 子文件；envelope `talevia.json` 只留 schemaVersion + 元数据 + 子文件清单。先写 decision 评估触发条件再动。Rubric §3a-3。
- **recents-registry-list-summaries-index** — `RecentsRegistry.listSummaries()` 当前每次扫所有 entry + 解码每个 `talevia.json` envelope，N≥几百时变慢。**方向：** registry 自身缓存 `(title, updatedAtEpochMs)`，envelope 写时同步 registry；`listSummaries` 直接读 registry 不再扫 bundle。Rubric §5.3 / 性能（profiling 触发再做）。
- **debt-export-missing-asset-warning** — cycle-14 的 `bundle-asset-relink-ux` 交付了 Core-layer `BusEvent.AssetsMissing` + `RelinkAssetTool`，但 bullet 里的 "CLI / Desktop 在 export 前显式列出 missing" 仍未落地。**方向：** CLI 订阅 `BusEvent.AssetsMissing`，`export` 命令前打印警告（"N assets don't resolve on this machine — call relink_asset or cancel"）；Desktop 在 SnapshotPanel / ExportPanel 旁边加 banner。Rubric 外 / 顺手记录。
- **debt-plist-extra-keys-lint-test** — Compose Desktop `nativeDistributions.macOS.infoPlist.extraKeysRawXml` 是裸 XML 注入，没有 schema / lint / compile-time 校验；错别字（`<array/>` 漏闭 `<dict>` 等）要到用户在 Finder 双击 `.talevia` 文件没反应才会暴露。**方向：** 加一个 unit test 把 `build.gradle.kts` 里的 raw-XML 串喂给 `NSPropertyListSerialization.propertyListWithData(...)`（或 CI 上调 `plutil -lint`），任何解析失败判 fail。Rubric 外 / 顺手记录。
- **debt-bundle-fs-testkit-copy-recursive** — Okio 的 `FakeFileSystem` 没有递归 copy 原语；`BundleCrossMachineExportSmokeTest` 在测试里手写了 `copyDirectoryRecursive(fs, src, dst)`（12 行）。**方向：** 第二个 bundle-level 测试需要同一个 walker 时，抽到 `ProjectStoreTestKit` 或 `BundleFsTestKit`；别提前做（don't pre-abstract on N=1）。**触发条件：** 出现第二个 caller。Rubric 外 / 顺手记录。
- **debt-register-tool-script** — 加一个新 tool 要同步改 5 个 `AppContainer`（CLI / Desktop / Server / Android / iOS），每次 import 行手插入位置错了就挨 ktlint 一顿，有 9/15 ≈ 60% 的 cycle 吃这份税。**方向：** 脚本 `/register-tool <ToolName> <ctorArgs>` 生成 10 行 diff 跨 5 文件 + 自动跑 `ktlintFormat`。**触发条件：** 连续 10 个新-tool cycle 都吃这份税，或 60% 占比在下一次 repopulate 时仍然持有。Rubric 外 / 顺手记录。
- **debt-unified-dispatcher-select-plugin-shape** — 每次给 `project_query` / `session_query` / `source_query` 加一个 select 要改 7-8 个协调站点（SELECT_* 常量 + ALL_SELECTS + Input 字段 + helpText + JSON Schema + `rejectIncompatibleFilters` 矩阵 + execute 分派 + sibling 文件）；reject 矩阵是 `O(n_selects × n_filters)` 成本。**方向：** 一个 select 一个文件的 "select plugin" shape（例：`@AppliesTo(SELECT_FOO)` 注解驱动 filter 适用性），把 7-8 站点收敛为 1 站点。**触发条件：** 任一 dispatcher 的 select 数达到 20，或 `rejectIncompatibleFilters` 矩阵达到 30+ 规则。当前：project 14 / session 15 / source 5。Rubric 外 / 顺手记录。

