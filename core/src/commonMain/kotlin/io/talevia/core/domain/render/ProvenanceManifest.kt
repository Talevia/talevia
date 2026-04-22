package io.talevia.core.domain.render

import io.talevia.core.JsonConfig
import kotlinx.serialization.Serializable

/**
 * Small structured record baked into every rendered artifact's container
 * metadata — VISION §5.3 "可复现的确定性产物" says the artifact should be
 * reachable back to its source. Without this, `out.mp4` in a user's
 * Downloads folder 3 months from now carries no trace of which Project
 * or Timeline produced it; the user has to grep logs / ProjectStore
 * snapshots to triangulate.
 *
 * Baked via ffmpeg's `-metadata comment=...` (FfmpegVideoEngine). On
 * Media3 / AVFoundation the metadata is carried through
 * [io.talevia.core.platform.OutputSpec.metadata] but the engines don't
 * yet wire it — future cycles will add per-engine container-metadata
 * writers once the driver surfaces.
 *
 * **Determinism (ExportDeterminismTest).** Two exports of the same
 * (timeline, lockfile, output spec) must produce bit-identical mp4s —
 * that's the RenderCache correctness assumption. This manifest is
 * therefore **pure function of its inputs**: no timestamps, no random
 * ids, no session / machine identifiers. `exportedAt` and similar fields
 * can be derived from the file's mtime if a caller needs them.
 *
 * Encoded inside a single ffmpeg metadata value with the
 * [MANIFEST_PREFIX] header so consumers can quickly distinguish a
 * Talevia comment from arbitrary user text in a re-edited file. Bodied
 * as JSON (canonical `JsonConfig.default`) for forward-compat — adding
 * optional fields is cheap; `ignoreUnknownKeys` makes rollback safe.
 */
@Serializable
data class ProvenanceManifest(
    /** [io.talevia.core.ProjectId.value] of the exporting Project. */
    val projectId: String,
    /** FNV-1a-64 hex over the canonical Timeline JSON — changes on any clip / track / source-binding edit. */
    val timelineHash: String,
    /** FNV-1a-64 hex over the canonical Lockfile.entries JSON — changes when any AIGC generation is pinned / unpinned / appended. */
    val lockfileHash: String,
    /** Manifest schema version. Bump when the body shape changes in a non-additive way. */
    val schemaVersion: Int = CURRENT_SCHEMA,
) {
    /** Encode to the single-line comment string ffmpeg pipes into container metadata. */
    fun encodeToComment(): String =
        MANIFEST_PREFIX + JsonConfig.default.encodeToString(serializer(), this)

    companion object {
        /**
         * Prefix identifying a Talevia-origin comment. A file re-edited in a
         * non-Talevia tool may still carry the comment tag forward, so the
         * prefix lets consumers distinguish "wrote by us" from "we're being
         * handed an unrelated comment".
         */
        const val MANIFEST_PREFIX: String = "talevia/v1:"

        /** Current value for new [schemaVersion] fields. */
        const val CURRENT_SCHEMA: Int = 1

        /**
         * Parse a container comment string back into a manifest. Returns null
         * for:
         *  - `null` input (no comment in the container).
         *  - Empty / blank input.
         *  - Comment without the [MANIFEST_PREFIX] (not a Talevia export —
         *    some other tool wrote the file or the user set their own comment).
         *  - Malformed JSON body (corrupted / truncated metadata).
         *
         * Never throws — this is called from UIs and probe responses where a
         * corrupted comment on a user-imported mp4 shouldn't error out the
         * whole probe.
         */
        fun decodeFromComment(comment: String?): ProvenanceManifest? {
            if (comment.isNullOrBlank()) return null
            if (!comment.startsWith(MANIFEST_PREFIX)) return null
            val body = comment.substring(MANIFEST_PREFIX.length)
            return runCatching {
                JsonConfig.default.decodeFromString(serializer(), body)
            }.getOrNull()
        }
    }
}
