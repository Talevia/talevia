# DECISIONS

归档实现 `docs/VISION.md` 过程中的设计决策，每条一个文件。

每个文件命名为 `YYYY-MM-DD-<slug>.md`，内部 section 头保持 `## YYYY-MM-DD — 短标题`
的既有格式，便于跨文件 grep / 导回成聚合视图。

每条记录 **decision**、**alternatives considered**、**reasoning** ——
reasoning 是半年后回来 revisit 时最值钱的部分。只写「做了 X」不写「因为 Y」会烂。

新增条目时 **新建文件**，不要编辑已有文件（除非确实是修正 / 打补丁）。
并行 agent 天然 diff-friendly：每个分支写各自的新文件，不再抢同一份 append 点。

按文件名日期倒序排列即是时间线视图：`ls docs/decisions | sort -r`。
