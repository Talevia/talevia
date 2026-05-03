package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runStaleClipsQuery] — `project_query(select=
 * stale_clips)`. The read side of VISION §3.2's edit-source-then-
 * regenerate loop. Cycle 135 audit: 103 LOC, 0 transitive test
 * refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Stale = clip's lockfile snapshot mismatches the current
 *    source DAG hash.** A clip with a matching snapshot is fresh
 *    (not stale); a clip with mismatching snapshot surfaces.
 *    Imported / non-AIGC media (no lockfile entry) is excluded
 *    entirely — the underlying `staleClipsFromLockfile` only
 *    walks AIGC clips. A regression including imported clips
 *    would inflate stale counts; a regression excluding genuine
 *    drift would silently mark stale clips as fresh.
 *
 * 2. **Sort by `clipId` ASC for reproducible pagination.** Per
 *    kdoc: "Sorted by clipId ASC so repeated calls against the
 *    same project state are reproducible." A regression dropping
 *    the sort would surface different clips on repeat calls
 *    under offset/limit.
 *
 * 3. **`changedSourceIds` lists DIRECTLY-bound drifted ids
 *    only.** Per kdoc: "A parent edit that propagates into a
 *    child via the source DAG (deep-hash drift) surfaces under
 *    the child's id, not the root-cause ancestor." A regression
 *    surfacing the root-cause would tell the LLM "edit ancestor"
 *    when the report's contract is "regenerate the bound child".
 */
class StaleClipsQueryTest {

    private val timeRange = TimeRange(start = kotlin.time.Duration.ZERO, duration = 5.seconds)

    private fun videoClip(
        id: String,
        assetId: String,
        binding: Set<SourceNodeId> = emptySet(),
    ) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
        sourceBinding = binding,
    )

    private fun lockEntry(
        assetId: String,
        sourceContentHashes: Map<SourceNodeId, String> = emptyMap(),
        sourceBinding: Set<SourceNodeId> = emptySet(),
    ) = LockfileEntry(
        inputHash = "h-$assetId",
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "openai",
            modelId = "gpt-image-1",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0L,
        ),
        sourceContentHashes = sourceContentHashes,
        sourceBinding = sourceBinding,
        originatingMessageId = MessageId("m"),
    )

    private fun project(
        clips: List<Clip> = emptyList(),
        nodes: List<SourceNode> = emptyList(),
        entries: List<LockfileEntry> = emptyList(),
    ): Project {
        val tracks = if (clips.isEmpty()) emptyList()
        else listOf(Track.Video(TrackId("vt"), clips))
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            source = Source(nodes = nodes),
            lockfile = EagerLockfile(entries = entries),
        )
    }

    private fun decodeRows(out: ProjectQueryTool.Output): List<StaleClipReportRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(StaleClipReportRow.serializer()),
            out.rows,
        )

    // ── empty / fresh paths ───────────────────────────────────────

    @Test fun emptyProjectReturnsZeroAndAllFreshSummary() {
        val result = runStaleClipsQuery(project(), 100, 0)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertEquals(emptyList(), decodeRows(result.data))
        // Pin: empty branch summary "All AIGC clips fresh (N
        // clip(s) total; nothing to regenerate)" with totalClips
        // count.
        val out = result.outputForLlm
        assertTrue("All AIGC clips fresh" in out, "fresh marker; got: $out")
        assertTrue("0 clip(s) total" in out, "total clips; got: $out")
        assertTrue("nothing to regenerate" in out, "actionable hint; got: $out")
    }

    @Test fun importedClipsAreExcludedFromStaleCount() {
        // Pin: clips without lockfile entries (= imported media)
        // never surface as stale, regardless of source DAG state.
        val clip = videoClip("c1", "imported-asset")
        val result = runStaleClipsQuery(project(clips = listOf(clip)), 100, 0)
        assertEquals(0, result.data.total)
    }

    @Test fun aigcClipWithMatchingDeepHashSnapshotIsFresh() {
        // Pin: a clip whose lockfile snapshot matches the current
        // DEEP content hash (folded with ancestors per the
        // VISION §5.1 transitive-propagation contract) is fresh.
        // Use the actual deepContentHashOf helper to get the
        // current authoritative value rather than guessing —
        // staleClipsFromLockfile uses deep hash, NOT raw
        // node.contentHash, so the lockfile snapshot must mirror
        // the deep value to register as fresh.
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val source = io.talevia.core.domain.source.Source(nodes = listOf(node))
        val deepHash = source.deepContentHashOf(SourceNodeId("char"))
        val clip = videoClip("c1", "asset-1", binding = setOf(SourceNodeId("char")))
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(SourceNodeId("char") to deepHash),
            sourceBinding = setOf(SourceNodeId("char")),
        )
        val result = runStaleClipsQuery(
            project(clips = listOf(clip), nodes = listOf(node), entries = listOf(entry)),
            100,
            0,
        )
        assertEquals(0, result.data.total, "matching deep hash → fresh")
    }

    // ── stale path: drifted source ───────────────────────────────

    @Test fun aigcClipWithDriftedSnapshotSurfacesAsStale() {
        // Clip's lockfile snapshot has stale hash → stale.
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val clip = videoClip("c1", "asset-1", binding = setOf(SourceNodeId("char")))
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(SourceNodeId("char") to "stale-hash"),
            sourceBinding = setOf(SourceNodeId("char")),
        )
        val rows = decodeRows(
            runStaleClipsQuery(
                project(clips = listOf(clip), nodes = listOf(node), entries = listOf(entry)),
                100,
                0,
            ).data,
        )
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("c1", row.clipId)
        assertEquals("asset-1", row.assetId)
        // Pin: directly-bound `char` surfaces in changedSourceIds.
        assertTrue("char" in row.changedSourceIds, "drifted id in list; got: ${row.changedSourceIds}")
    }

    @Test fun multipleDriftedSourcesAllSurfaceInChangedSourceIds() {
        // Pin: when an entry binds multiple sources and ALL drift,
        // all surface in changedSourceIds (NOT just first).
        val charNode = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val styleNode = SourceNode.create(id = SourceNodeId("style"), kind = "k")
        val clip = videoClip(
            "c1",
            "asset-1",
            binding = setOf(SourceNodeId("char"), SourceNodeId("style")),
        )
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(
                SourceNodeId("char") to "old-c",
                SourceNodeId("style") to "old-s",
            ),
            sourceBinding = setOf(SourceNodeId("char"), SourceNodeId("style")),
        )
        val rows = decodeRows(
            runStaleClipsQuery(
                project(
                    clips = listOf(clip),
                    nodes = listOf(charNode, styleNode),
                    entries = listOf(entry),
                ),
                100,
                0,
            ).data,
        )
        val row = rows.single()
        assertEquals(setOf("char", "style"), row.changedSourceIds.toSet())
    }

    // ── sort + pagination ────────────────────────────────────────

    @Test fun rowsAreSortedAlphabeticallyByClipId() {
        // Pin marquee: insertion order [zebra-clip, alpha-clip,
        // mango-clip] → output [alpha-clip, mango-clip, zebra-clip].
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val clips = listOf("zebra-clip", "alpha-clip", "mango-clip").map {
            videoClip(it, "asset-$it", binding = setOf(SourceNodeId("char")))
        }
        val entries = clips.map {
            lockEntry(
                (it as Clip.Video).assetId.value,
                sourceContentHashes = mapOf(SourceNodeId("char") to "stale"),
                sourceBinding = setOf(SourceNodeId("char")),
            )
        }
        val rows = decodeRows(
            runStaleClipsQuery(
                project(clips = clips, nodes = listOf(node), entries = entries),
                100,
                0,
            ).data,
        )
        assertEquals(
            listOf("alpha-clip", "mango-clip", "zebra-clip"),
            rows.map { it.clipId },
        )
    }

    @Test fun limitTrimsRowsButTotalReflectsAllStaleClips() {
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val clips = (1..5).map {
            videoClip(
                "c${it.toString().padStart(2, '0')}",
                "asset-$it",
                binding = setOf(SourceNodeId("char")),
            )
        }
        val entries = (1..5).map {
            lockEntry(
                "asset-$it",
                sourceContentHashes = mapOf(SourceNodeId("char") to "stale"),
                sourceBinding = setOf(SourceNodeId("char")),
            )
        }
        val result = runStaleClipsQuery(
            project(clips = clips, nodes = listOf(node), entries = entries),
            2,
            0,
        )
        assertEquals(2, decodeRows(result.data).size, "page = limit")
        assertEquals(5, result.data.total, "total reflects all stale")
    }

    @Test fun offsetSkipsFirstNRowsOfSorted() {
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val clips = (1..5).map {
            videoClip(
                "c${it.toString().padStart(2, '0')}",
                "asset-$it",
                binding = setOf(SourceNodeId("char")),
            )
        }
        val entries = (1..5).map {
            lockEntry(
                "asset-$it",
                sourceContentHashes = mapOf(SourceNodeId("char") to "stale"),
                sourceBinding = setOf(SourceNodeId("char")),
            )
        }
        val result = runStaleClipsQuery(
            project(clips = clips, nodes = listOf(node), entries = entries),
            100,
            2,
        )
        val rows = decodeRows(result.data)
        // Sorted: c01..c05; offset=2 → start at c03.
        assertEquals(3, rows.size)
        assertEquals("c03", rows[0].clipId)
    }

    // ── outputForLlm ──────────────────────────────────────────────

    @Test fun nonEmptyOutputShowsCountTotalAndPreviewWithChangedIds() {
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val clip = videoClip("c1", "asset-1", binding = setOf(SourceNodeId("char")))
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(SourceNodeId("char") to "stale"),
            sourceBinding = setOf(SourceNodeId("char")),
        )
        val out = runStaleClipsQuery(
            project(clips = listOf(clip), nodes = listOf(node), entries = listOf(entry)),
            100,
            0,
        ).outputForLlm
        // Pin format: "<total> of <totalClips> clip(s) stale.
        // <preview>".
        assertTrue("1 of 1 clip(s) stale" in out, "count + total; got: $out")
        // Preview entry: "<clipId> (changed: <comma-separated ids>)".
        assertTrue("c1 (changed: char)" in out, "preview format; got: $out")
    }

    @Test fun outputShowsTruncationNoteWhenLimitTrims() {
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val clips = (1..5).map {
            videoClip(
                "c${it.toString().padStart(2, '0')}",
                "asset-$it",
                binding = setOf(SourceNodeId("char")),
            )
        }
        val entries = (1..5).map {
            lockEntry(
                "asset-$it",
                sourceContentHashes = mapOf(SourceNodeId("char") to "stale"),
                sourceBinding = setOf(SourceNodeId("char")),
            )
        }
        val out = runStaleClipsQuery(
            project(clips = clips, nodes = listOf(node), entries = entries),
            2,
            0,
        ).outputForLlm
        // Pin: trunc note when total > page.size.
        assertTrue("(showing 2 of 5" in out, "trunc count; got: $out")
        assertTrue("raise limit to see more" in out, "recovery; got: $out")
    }

    @Test fun outputShowsEllipsisWhenPageHasMoreThanFive() {
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val clips = (1..7).map {
            videoClip(
                "c${it.toString().padStart(2, '0')}",
                "asset-$it",
                binding = setOf(SourceNodeId("char")),
            )
        }
        val entries = (1..7).map {
            lockEntry(
                "asset-$it",
                sourceContentHashes = mapOf(SourceNodeId("char") to "stale"),
                sourceBinding = setOf(SourceNodeId("char")),
            )
        }
        val out = runStaleClipsQuery(
            project(clips = clips, nodes = listOf(node), entries = entries),
            100,
            0,
        ).outputForLlm
        assertTrue(out.endsWith("; …"), "ellipsis when page > 5; got: $out")
    }

    @Test fun outputNoEllipsisWhenAtMostFiveInPage() {
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val clips = (1..3).map {
            videoClip(
                "c$it",
                "asset-$it",
                binding = setOf(SourceNodeId("char")),
            )
        }
        val entries = (1..3).map {
            lockEntry(
                "asset-$it",
                sourceContentHashes = mapOf(SourceNodeId("char") to "stale"),
                sourceBinding = setOf(SourceNodeId("char")),
            )
        }
        val out = runStaleClipsQuery(
            project(clips = clips, nodes = listOf(node), entries = entries),
            100,
            0,
        ).outputForLlm
        assertTrue("…" !in out, "no ellipsis when page ≤ 5; got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val result = runStaleClipsQuery(project(), 100, 0)
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_STALE_CLIPS, result.data.select)
    }

    @Test fun titleIncludesTotalCount() {
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val clips = (1..3).map {
            videoClip(
                "c$it",
                "asset-$it",
                binding = setOf(SourceNodeId("char")),
            )
        }
        val entries = (1..3).map {
            lockEntry(
                "asset-$it",
                sourceContentHashes = mapOf(SourceNodeId("char") to "stale"),
                sourceBinding = setOf(SourceNodeId("char")),
            )
        }
        val result = runStaleClipsQuery(
            project(clips = clips, nodes = listOf(node), entries = entries),
            100,
            0,
        )
        assertTrue(
            "stale_clips (3)" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
    }

    @Test fun summarytotalClipsCountsAllClipsRegardlessOfLockfileStatus() {
        // Pin: the summary's "<N> clip(s) total" counts ALL clips
        // on the timeline, even imported ones. The contrast
        // between stale-count and total-count is what gives the
        // LLM the "what fraction is regeneratable" sense.
        val node = SourceNode.create(id = SourceNodeId("char"), kind = "k")
        val staleClip = videoClip("c-stale", "asset-1", binding = setOf(SourceNodeId("char")))
        val importedClip = videoClip("c-imported", "imported")
        val entry = lockEntry(
            "asset-1",
            sourceContentHashes = mapOf(SourceNodeId("char") to "old"),
            sourceBinding = setOf(SourceNodeId("char")),
        )
        val out = runStaleClipsQuery(
            project(clips = listOf(staleClip, importedClip), nodes = listOf(node), entries = listOf(entry)),
            100,
            0,
        ).outputForLlm
        // 1 stale of 2 total clips on timeline.
        assertTrue("1 of 2 clip(s) stale" in out, "fraction; got: $out")
    }

    @Test fun summaryEmptyClipsCountReportedInFreshMessage() {
        // Pin: empty timeline branch reports "0 clip(s) total".
        val out = runStaleClipsQuery(project(), 100, 0).outputForLlm
        assertTrue("0 clip(s) total" in out, "got: $out")
    }
}
