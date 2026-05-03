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
import io.talevia.core.domain.render.RenderCache
import io.talevia.core.domain.render.RenderCacheEntry
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runLockfileCacheStatsQuery] —
 * `project_query(select=lockfile_cache_stats)`. The "pin 命中率
 * 可见" M2 §5.7 / §5.3 criterion: how many AIGC clips would hit
 * the lockfile cache vs re-call the provider on regeneration.
 * Cycle 126 audit: 156 LOC, **zero** transitive test references.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Hit/miss/imported tri-state classification.** Per kdoc:
 *    - clip with assetId in lockfile → HIT
 *    - clip with sourceBinding non-empty but no lockfile match
 *      → MISS (genuine cache miss; provider would be called)
 *    - clip with empty sourceBinding AND no lockfile match →
 *      NEITHER (imported / hand-authored media, hit/miss
 *      doesn't apply)
 *    A regression collapsing imported into miss would inflate
 *    miss counts (lowering hit ratio); collapsing into hit
 *    would silently mark imports as cached AIGC.
 *
 * 2. **`unknownMisses` synthetic row appears ONLY when misses
 *    > 0.** Per code: "rolled up so the reader can see 'N clips
 *    have no lockfile entry' without spelunking the row array".
 *    A regression always emitting the row would clutter empty-
 *    misses outputs with a noise row; never emitting would
 *    silently hide miss-count attribution.
 *
 * 3. **Hit ratio division by zero clamps to 0.0.** Per code:
 *    `(hits + misses).coerceAtLeast(1)`. A regression producing
 *    NaN on all-imported projects would silently corrupt UI
 *    cache-ratio gauges (NaN renders unpredictably across
 *    front-ends).
 */
class LockfileCacheStatsQueryTest {

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    private fun videoClip(
        id: String,
        assetId: String,
        sourceBinding: Set<SourceNodeId> = emptySet(),
    ) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
        sourceBinding = sourceBinding,
    )

    private fun audioClip(
        id: String,
        assetId: String,
        sourceBinding: Set<SourceNodeId> = emptySet(),
    ) = Clip.Audio(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
        sourceBinding = sourceBinding,
    )

    private fun textClip(id: String) = Clip.Text(
        id = ClipId(id),
        timeRange = timeRange,
        text = "subtitle",
    )

    private fun lockEntry(
        assetId: String,
        providerId: String = "openai",
        modelId: String = "gpt-image-1",
    ) = LockfileEntry(
        inputHash = "h-$assetId",
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = providerId,
            modelId = modelId,
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0L,
        ),
        originatingMessageId = MessageId("m"),
    )

    private fun project(
        clips: List<Clip> = emptyList(),
        entries: List<LockfileEntry> = emptyList(),
        renderCacheEntries: List<RenderCacheEntry> = emptyList(),
    ): Project {
        val tracks = if (clips.isEmpty()) emptyList() else listOf(Track.Video(TrackId("vt"), clips))
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            lockfile = EagerLockfile(entries = entries),
            renderCache = RenderCache(entries = renderCacheEntries),
        )
    }

    private fun renderEntry(fingerprint: String) = RenderCacheEntry(
        fingerprint = fingerprint,
        outputPath = "/tmp/$fingerprint.mp4",
        resolutionWidth = 1920,
        resolutionHeight = 1080,
        durationSeconds = 5.0,
        createdAtEpochMs = 0L,
    )

    private fun decodeRow(out: ProjectQueryTool.Output): LockfileCacheStatsRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(LockfileCacheStatsRow.serializer()),
            out.rows,
        ).single()

    // ── empty / div-by-zero clamp ─────────────────────────────────

    @Test fun emptyProjectReportsZeroHitsZeroMissesZeroRatio() {
        val result = runLockfileCacheStatsQuery(project())
        val row = decodeRow(result.data)
        assertEquals(0, row.totalExports)
        assertEquals(0, row.cacheHits)
        assertEquals(0, row.cacheMisses)
        assertEquals(0.0, row.hitRatio, "ratio clamped to 0 on div-by-zero")
        assertEquals(emptyList(), row.perModelBreakdown, "no breakdown when no hits/misses")
    }

    @Test fun allImportedClipsReportZeroHitsZeroMissesZeroRatio() {
        // Pin div-by-zero clamp: clips without lockfile match AND
        // without sourceBinding are imported — neither hit nor
        // miss. Result: hits=0, misses=0, ratio=0.0 (NOT NaN).
        val a = videoClip("c1", "imported-1")
        val b = videoClip("c2", "imported-2")
        val row = decodeRow(runLockfileCacheStatsQuery(project(clips = listOf(a, b))).data)
        assertEquals(0, row.cacheHits)
        assertEquals(0, row.cacheMisses)
        assertEquals(0.0, row.hitRatio, "imported-only project has 0.0 ratio (NOT NaN)")
    }

    // ── tri-state classification ──────────────────────────────────

    @Test fun clipWithLockfileMatchIsHit() {
        val clip = videoClip("c1", "asset-1", sourceBinding = setOf(SourceNodeId("n1")))
        val entry = lockEntry("asset-1")
        val row = decodeRow(
            runLockfileCacheStatsQuery(project(clips = listOf(clip), entries = listOf(entry))).data,
        )
        assertEquals(1, row.cacheHits)
        assertEquals(0, row.cacheMisses)
    }

    @Test fun clipWithBindingButNoLockfileMatchIsMiss() {
        // sourceBinding non-empty + assetId NOT in lockfile →
        // genuine cache miss (provider would be called on
        // regenerate).
        val clip = videoClip("c1", "ghost-asset", sourceBinding = setOf(SourceNodeId("n1")))
        val row = decodeRow(runLockfileCacheStatsQuery(project(clips = listOf(clip))).data)
        assertEquals(0, row.cacheHits)
        assertEquals(1, row.cacheMisses)
    }

    @Test fun clipWithoutBindingAndWithoutLockfileIsImportedNeitherHitNorMiss() {
        // Marquee tri-state pin: imported media (empty
        // sourceBinding + no lockfile entry) is NEITHER hit nor
        // miss. A regression collapsing into miss would inflate
        // miss counts; into hit would silently mark imports as
        // cached AIGC.
        val clip = videoClip("c1", "imported")
        val row = decodeRow(runLockfileCacheStatsQuery(project(clips = listOf(clip))).data)
        assertEquals(0, row.cacheHits, "imported NOT hit")
        assertEquals(0, row.cacheMisses, "imported NOT miss")
    }

    @Test fun clipWithBindingAndLockfileEntryIsHitNotMissEvenWithBinding() {
        // Pin: a clip with sourceBinding AND a lockfile entry is
        // a HIT (the binding doesn't disqualify it). A regression
        // requiring "binding empty" for hit would silently flip
        // every legitimate AIGC cache hit to miss.
        val clip = videoClip("c1", "asset-1", sourceBinding = setOf(SourceNodeId("n1")))
        val entry = lockEntry("asset-1")
        val row = decodeRow(
            runLockfileCacheStatsQuery(project(clips = listOf(clip), entries = listOf(entry))).data,
        )
        assertEquals(1, row.cacheHits)
        assertEquals(0, row.cacheMisses)
    }

    @Test fun textClipsSkippedEntirely() {
        // Text clips have no assetId in the domain model — pass
        // continue, neither hit nor miss.
        val v = videoClip("c1", "asset-1")
        val t = textClip("t1")
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(v)),
            Track.Subtitle(TrackId("st"), listOf(t)),
        )
        val proj = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
        )
        val row = decodeRow(runLockfileCacheStatsQuery(proj).data)
        assertEquals(0, row.cacheHits)
        assertEquals(0, row.cacheMisses)
    }

    @Test fun audioClipSourceBindingPathSameAsVideo() {
        // Audio clips also participate in hit/miss tracking. Pin
        // the same tri-state on Audio.
        val au = audioClip("c1", "audio-1", sourceBinding = setOf(SourceNodeId("n1")))
        val entry = lockEntry("audio-1", providerId = "elevenlabs", modelId = "tts-v3")
        val tracks = listOf(Track.Audio(TrackId("at"), listOf(au)))
        val proj = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            lockfile = EagerLockfile(entries = listOf(entry)),
        )
        val row = decodeRow(runLockfileCacheStatsQuery(proj).data)
        assertEquals(1, row.cacheHits)
        // Audio's provider/model surfaces in breakdown.
        val bd = row.perModelBreakdown.single()
        assertEquals("elevenlabs", bd.providerId)
        assertEquals("tts-v3", bd.modelId)
    }

    // ── hit ratio math ────────────────────────────────────────────

    @Test fun hitRatioHalfWhenOneHitOneMiss() {
        val hit = videoClip("c1", "asset-1", sourceBinding = setOf(SourceNodeId("n1")))
        val miss = videoClip("c2", "ghost", sourceBinding = setOf(SourceNodeId("n2")))
        val entry = lockEntry("asset-1")
        val row = decodeRow(
            runLockfileCacheStatsQuery(
                project(clips = listOf(hit, miss), entries = listOf(entry)),
            ).data,
        )
        assertEquals(1, row.cacheHits)
        assertEquals(1, row.cacheMisses)
        assertEquals(0.5, row.hitRatio)
    }

    @Test fun hitRatioOneWhenAllHits() {
        val clip = videoClip("c1", "asset-1", sourceBinding = setOf(SourceNodeId("n1")))
        val entry = lockEntry("asset-1")
        val row = decodeRow(
            runLockfileCacheStatsQuery(project(clips = listOf(clip), entries = listOf(entry))).data,
        )
        assertEquals(1.0, row.hitRatio)
    }

    @Test fun hitRatioZeroWhenAllMisses() {
        val miss = videoClip("c1", "ghost", sourceBinding = setOf(SourceNodeId("n1")))
        val row = decodeRow(runLockfileCacheStatsQuery(project(clips = listOf(miss))).data)
        assertEquals(0.0, row.hitRatio)
    }

    @Test fun hitRatioIgnoresImportedClipsInDenominator() {
        // 1 hit + 0 misses + 5 imported → hit ratio = 1.0 (NOT
        // 1/6). Pin: imported clips are excluded from the
        // denominator.
        val hit = videoClip("c-hit", "asset-1", sourceBinding = setOf(SourceNodeId("n1")))
        val imports = (1..5).map { videoClip("c-imp-$it", "imp-$it") }
        val entry = lockEntry("asset-1")
        val row = decodeRow(
            runLockfileCacheStatsQuery(
                project(clips = listOf(hit) + imports, entries = listOf(entry)),
            ).data,
        )
        assertEquals(1, row.cacheHits)
        assertEquals(0, row.cacheMisses)
        assertEquals(1.0, row.hitRatio)
    }

    // ── perModelBreakdown ─────────────────────────────────────────

    @Test fun breakdownGroupsHitsByProviderAndModel() {
        val openaiHit1 = videoClip("c1", "a1", sourceBinding = setOf(SourceNodeId("n1")))
        val openaiHit2 = videoClip("c2", "a2", sourceBinding = setOf(SourceNodeId("n2")))
        val replicateHit = videoClip("c3", "a3", sourceBinding = setOf(SourceNodeId("n3")))
        val entries = listOf(
            lockEntry("a1", providerId = "openai", modelId = "gpt-image-1"),
            lockEntry("a2", providerId = "openai", modelId = "gpt-image-1"),
            lockEntry("a3", providerId = "replicate", modelId = "flux-1"),
        )
        val row = decodeRow(
            runLockfileCacheStatsQuery(
                project(
                    clips = listOf(openaiHit1, openaiHit2, replicateHit),
                    entries = entries,
                ),
            ).data,
        )
        // Pin alphabetic order: openai before replicate.
        assertEquals(2, row.perModelBreakdown.size)
        val openai = row.perModelBreakdown[0]
        assertEquals("openai", openai.providerId)
        assertEquals("gpt-image-1", openai.modelId)
        assertEquals(2, openai.hits)
        assertEquals(0, openai.misses)
        val replicate = row.perModelBreakdown[1]
        assertEquals("replicate", replicate.providerId)
        assertEquals(1, replicate.hits)
    }

    @Test fun breakdownSortAlphabeticByProviderThenModel() {
        // Pin secondary sort: same provider, different models.
        val a = videoClip("c1", "a1", sourceBinding = setOf(SourceNodeId("n1")))
        val b = videoClip("c2", "a2", sourceBinding = setOf(SourceNodeId("n2")))
        val entries = listOf(
            lockEntry("a1", providerId = "openai", modelId = "z-newer-model"),
            lockEntry("a2", providerId = "openai", modelId = "a-older-model"),
        )
        val row = decodeRow(
            runLockfileCacheStatsQuery(
                project(clips = listOf(a, b), entries = entries),
            ).data,
        )
        // Pin: "a-older" before "z-newer" (alphabetic on modelId).
        assertEquals(2, row.perModelBreakdown.size)
        assertEquals("a-older-model", row.perModelBreakdown[0].modelId)
        assertEquals("z-newer-model", row.perModelBreakdown[1].modelId)
    }

    // ── unknownMisses synthetic row ───────────────────────────────

    @Test fun unknownMissesSyntheticRowAppearsOnlyWhenMissesPositive() {
        val miss = videoClip("c1", "ghost", sourceBinding = setOf(SourceNodeId("n1")))
        val row = decodeRow(runLockfileCacheStatsQuery(project(clips = listOf(miss))).data)
        // Pin: synthetic "unknown" row at the tail of breakdown.
        val unknown = row.perModelBreakdown.single()
        assertEquals("unknown", unknown.providerId)
        assertEquals("unknown", unknown.modelId)
        assertEquals(0, unknown.hits)
        assertEquals(1, unknown.misses)
    }

    @Test fun unknownMissesRowOmittedWhenZeroMisses() {
        // Pin: hits-only project does NOT emit the synthetic
        // unknown row. A regression always emitting it would
        // clutter every all-hits output with a 0/0 noise entry.
        val hit = videoClip("c1", "a1", sourceBinding = setOf(SourceNodeId("n1")))
        val row = decodeRow(
            runLockfileCacheStatsQuery(
                project(clips = listOf(hit), entries = listOf(lockEntry("a1"))),
            ).data,
        )
        assertEquals(1, row.perModelBreakdown.size, "no synthetic unknown row")
        val only = row.perModelBreakdown.single()
        assertTrue(only.providerId != "unknown", "real provider, not unknown")
    }

    @Test fun unknownMissesAppendedAfterRealProviderRows() {
        // Pin: real-provider rows come first (alphabetic),
        // unknown appended at the tail. A regression placing
        // unknown at index 0 would distort UI display order.
        val hit = videoClip("c1", "a1", sourceBinding = setOf(SourceNodeId("n1")))
        val miss = videoClip("c2", "ghost", sourceBinding = setOf(SourceNodeId("n2")))
        val entries = listOf(lockEntry("a1", providerId = "openai", modelId = "gpt-image-1"))
        val row = decodeRow(
            runLockfileCacheStatsQuery(
                project(clips = listOf(hit, miss), entries = entries),
            ).data,
        )
        assertEquals(2, row.perModelBreakdown.size)
        assertEquals("openai", row.perModelBreakdown[0].providerId, "real provider first")
        assertEquals("unknown", row.perModelBreakdown[1].providerId, "unknown at tail")
    }

    // ── totalExports from RenderCache ─────────────────────────────

    @Test fun totalExportsCountsRenderCacheEntries() {
        // Pin: totalExports = renderCache.entries.size, decoupled
        // from lockfile state.
        val proj = project(
            renderCacheEntries = listOf(
                renderEntry("fp-1"),
                renderEntry("fp-2"),
                renderEntry("fp-3"),
            ),
        )
        val row = decodeRow(runLockfileCacheStatsQuery(proj).data)
        assertEquals(3, row.totalExports)
    }

    @Test fun totalExportsZeroWhenRenderCacheEmpty() {
        // Pin: a project with lockfile but no renderCache reports
        // totalExports=0. The two caches are independent.
        val hit = videoClip("c1", "a1", sourceBinding = setOf(SourceNodeId("n1")))
        val row = decodeRow(
            runLockfileCacheStatsQuery(
                project(clips = listOf(hit), entries = listOf(lockEntry("a1"))),
            ).data,
        )
        assertEquals(0, row.totalExports)
        assertEquals(1, row.cacheHits, "lockfile state independent of render cache")
    }

    // ── outputForLlm + title ──────────────────────────────────────

    @Test fun outputForLlmIncludesProjectIdHitsMissesRatioAndTotalExports() {
        // Pin the natural-language summary format. The LLM uses
        // this to advise the user about cache health.
        val hit = videoClip("c1", "a1", sourceBinding = setOf(SourceNodeId("n1")))
        val miss = videoClip("c2", "ghost", sourceBinding = setOf(SourceNodeId("n2")))
        val out = runLockfileCacheStatsQuery(
            project(
                clips = listOf(hit, miss),
                entries = listOf(lockEntry("a1")),
                renderCacheEntries = listOf(renderEntry("fp")),
            ),
        ).outputForLlm
        assertTrue("Project p" in out, "project id; got: $out")
        assertTrue("1 export(s) memoized" in out, "totalExports; got: $out")
        assertTrue("1 clip(s) would hit" in out, "hits; got: $out")
        assertTrue("1 miss(es)" in out, "misses; got: $out")
        // Hit ratio formatted as percentage with 1 decimal.
        assertTrue("hit ratio 50.0%" in out, "ratio %; got: $out")
    }

    @Test fun ratioRoundsToOneDecimalPlace() {
        // Pin: `kotlin.math.round(it * 10.0) / 10.0` → 1 decimal
        // place. 1/3 = 0.333... → 33.3%.
        val hit = videoClip("c1", "a1", sourceBinding = setOf(SourceNodeId("n1")))
        val miss1 = videoClip("c2", "g1", sourceBinding = setOf(SourceNodeId("n2")))
        val miss2 = videoClip("c3", "g2", sourceBinding = setOf(SourceNodeId("n3")))
        val out = runLockfileCacheStatsQuery(
            project(clips = listOf(hit, miss1, miss2), entries = listOf(lockEntry("a1"))),
        ).outputForLlm
        // 1/3 * 100 = 33.333... rounded to 33.3.
        assertTrue("hit ratio 33.3%" in out, "rounded ratio; got: $out")
    }

    @Test fun titleIncludesHitsAndTotalAttempts() {
        val hit = videoClip("c1", "a1", sourceBinding = setOf(SourceNodeId("n1")))
        val miss = videoClip("c2", "ghost", sourceBinding = setOf(SourceNodeId("n2")))
        val result = runLockfileCacheStatsQuery(
            project(clips = listOf(hit, miss), entries = listOf(lockEntry("a1"))),
        )
        // Pin: "(<hits>/<hits+misses>)".
        assertTrue("(1/2)" in (result.title ?: ""), "title; got: ${result.title}")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelectAndSingleRow() {
        val result = runLockfileCacheStatsQuery(project())
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_LOCKFILE_CACHE_STATS, result.data.select)
        assertEquals(1, result.data.total, "single-row payload")
        assertEquals(1, result.data.returned)
    }

    @Test fun rowProjectIdMirrorsProjectId() {
        // Pin: the row's projectId field mirrors the project's
        // id. UI consumers grouping rows by project rely on this.
        val result = runLockfileCacheStatsQuery(project())
        val row = decodeRow(result.data)
        assertEquals("p", row.projectId)
    }
}
