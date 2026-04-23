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

- **bundle-mobile-document-picker** — Android / iOS 当前限制于 app sandbox 内的 bundle (`<filesDir>/projects/` / `Documents/projects/`)。用户没法从 SAF / Files.app 选一个外部 bundle 打开。**方向：** Android 接 `Storage Access Framework` (`Intent.ACTION_OPEN_DOCUMENT_TREE`)，iOS 接 `UIDocumentPickerViewController`，结果 URI / NSURL 通过 platform-specific resolver 转成 Okio Path 喂给 `FileProjectStore.openAt`。Rubric §5.4 / mobile。
- **bundle-talevia-json-split** — 当前 `talevia.json` 把 timeline + assets + source DAG + lockfile + snapshots 全装一个文件，单个 mutation 的 git diff 涨几百行；snapshot 多了文件可能涨到 MB 级。**方向：** 当真出现 diff 噪声时拆 `assets.json` / `timeline.json` / `lockfile.json` / `snapshots/<id>.json` 子文件；envelope `talevia.json` 只留 schemaVersion + 元数据 + 子文件清单。先写 decision 评估触发条件再动。Rubric §3a-3。
- **recents-registry-list-summaries-index** — `RecentsRegistry.listSummaries()` 当前每次扫所有 entry + 解码每个 `talevia.json` envelope，N≥几百时变慢。**方向：** registry 自身缓存 `(title, updatedAtEpochMs)`，envelope 写时同步 registry；`listSummaries` 直接读 registry 不再扫 bundle。Rubric §5.3 / 性能（profiling 触发再做）。
- **debt-import-media-auto-in-bundle-default** — `bundle-source-footage-consolidate` 故意跳过了 bullet 的第二半 — `import_media` 智能默认（<50MB 自动 in-bundle）。当前 `copy_into_bundle: Boolean = false` 改 default 会 regress 现有 caller。**方向：** 切到 `copy_into_bundle: Boolean? = null` 三态；null = "按 file size 自动", explicit `true/false` 保留语义。对应 schema + helpText 更新。Rubric 外 / 顺手记录。
- **debt-streaming-bundle-blob-writer** — 三个 caller 内联复制 okio `source→sink.writeAll` 模式因为 `BundleBlobWriter.writeBlob(bytes: ByteArray)` 强制全文件入内存。**方向：** `BundleBlobWriter` 加 `suspend fun writeBlobStreaming(projectId, assetId, source: okio.Source, format): MediaSource.BundleFile`；migrate ImportMediaTool 的 `copy_into_bundle` 分支 + ConsolidateMediaIntoBundleTool + FileBundleBlobWriter.writeBlob 自己（byte-buffered 变 streaming wrapper）。Rubric 外 / 顺手记录。
- **debt-export-missing-asset-warning** — cycle-14 的 `bundle-asset-relink-ux` 交付了 Core-layer `BusEvent.AssetsMissing` + `RelinkAssetTool`，但 bullet 里的 "CLI / Desktop 在 export 前显式列出 missing" 仍未落地。**方向：** CLI 订阅 `BusEvent.AssetsMissing`，`export` 命令前打印警告（"N assets don't resolve on this machine — call relink_asset or cancel"）；Desktop 在 SnapshotPanel / ExportPanel 旁边加 banner。Rubric 外 / 顺手记录。

