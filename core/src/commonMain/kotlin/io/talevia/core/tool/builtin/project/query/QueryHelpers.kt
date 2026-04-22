package io.talevia.core.tool.builtin.project.query

import io.talevia.core.JsonConfig
import io.talevia.core.domain.Track
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlin.time.Duration

/**
 * Package-private helpers shared by the per-select query files
 * ([runTracksQuery], [runTimelineClipsQuery], [runAssetsQuery]). These used
 * to be `private` members of `ProjectQueryTool`; pulled out when that file
 * crossed the 500-line long-file threshold (see decision
 * `docs/decisions/2026-04-21-debt-split-projectquerytool.md`).
 *
 * Do NOT move these into the public `ProjectQueryTool` API — they're pure
 * translation helpers with no semantics to expose to callers.
 */
internal val VALID_TRACK_KINDS: Set<String> = setOf("video", "audio", "subtitle", "effect")
internal val ASSET_KINDS: Set<String> = setOf("video", "audio", "image", "all")

internal val TRACK_SORTS: Set<String> = setOf("index", "clipcount", "span", "recent")
internal val CLIP_SORTS: Set<String> = setOf("startseconds", "durationseconds", "recent")
internal val ASSET_SORTS: Set<String> = setOf("insertion", "duration", "duration-asc", "id", "recent")

/**
 * `sortBy="recent"` — shared ordering: `updatedAtEpochMs` DESC with nulls
 * tailed, stable tie-break by a per-select id field. Pre-recency blobs
 * (rows with null stamps) sort after any stamped row so orientation calls
 * show the freshly-touched entities first without obscuring legacy rows.
 */
internal fun <T> recentComparator(
    stampOf: (T) -> Long?,
    idOf: (T) -> String,
): Comparator<T> =
    compareByDescending<T> { stampOf(it) ?: Long.MIN_VALUE }
        .thenBy { idOf(it) }

internal fun trackKindOf(track: Track): String = when (track) {
    is Track.Video -> "video"
    is Track.Audio -> "audio"
    is Track.Subtitle -> "subtitle"
    is Track.Effect -> "effect"
}

internal fun <T> encodeRows(serializer: KSerializer<List<T>>, rows: List<T>): JsonArray =
    JsonConfig.default.encodeToJsonElement(serializer, rows) as JsonArray

internal fun Duration.toSecondsDouble(): Double = inWholeMilliseconds / 1000.0
