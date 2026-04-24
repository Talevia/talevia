package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.Lockfile

/**
 * Pure structural rewrites that cascade an id change (`oldId` -> `newId`) across
 * every surface that stores a raw [SourceNodeId]: the source DAG itself, every
 * timeline clip's `sourceBinding`, and every lockfile entry's `sourceBinding` +
 * `sourceContentHashes` key. Extracted from `RenameSourceNodeTool` so the tool
 * layer can orchestrate validation + snapshot emission + permission while the
 * genre-agnostic mechanics live in one testable place.
 *
 * **Scope — structural only.** These helpers rewrite ids at the Core level; they
 * do *not* look inside opaque typed bodies. If a `narrative.shot.body.sceneId`
 * happens to reference the renamed id as a string, the caller is responsible for
 * updating it via the kind-specific `update_*` tool. The alternative (snooping
 * typed fields) would break the Core -> genre boundary this project defends.
 *
 * **contentHash semantics.** [SourceNode.contentHash] is computed from
 * `(kind, body, parents)` — not from `id` — so the renamed node's hash is
 * unchanged. Descendant nodes whose `parents` list contained `oldId` *do* get a
 * new hash (the parent-ref value changes, so the serialised parents list
 * changes). That cascade is the *correct* stale-propagation behaviour: renaming
 * a node is a refactor, and any downstream AIGC render that consumed the old
 * parent-ref hash should be invalidated.
 */

/**
 * Rewrite [oldId] -> [newId] in the source DAG. Touches both the target node's
 * own `id` and every other node's [SourceNode.parents] entry that referenced the
 * old id. Recomputes [SourceNode.contentHash] on touched rows via
 * [SourceNode.create] so the stale-propagation lane picks up the (correct)
 * cascade on parent-ref changes.
 *
 * Returns the rewritten [Source] paired with the number of *other* nodes whose
 * `parents` list was rewritten (does not count the renamed node itself).
 */
fun Source.rewriteNodeId(oldId: SourceNodeId, newId: SourceNodeId): Pair<Source, Int> {
    var parentsTouched = 0
    val rewritten = nodes.map { node ->
        val isRenameTarget = node.id == oldId
        val hadOldParent = node.parents.any { it.nodeId == oldId }
        if (!isRenameTarget && !hadOldParent) {
            node
        } else {
            val nextParents = if (hadOldParent) {
                parentsTouched += 1
                node.parents.map { ref ->
                    if (ref.nodeId == oldId) SourceRef(newId) else ref
                }
            } else {
                node.parents
            }
            val nextId = if (isRenameTarget) newId else node.id
            // contentHash must be recomputed: for the target the (kind, body, parents)
            // tuple may be identical (hash stays), for a descendant the parents list
            // changed (hash bumps). SourceNode.create handles both.
            SourceNode.create(
                id = nextId,
                kind = node.kind,
                body = node.body,
                parents = nextParents,
                revision = node.revision + 1,
            )
        }
    }
    return copy(
        revision = revision + 1,
        nodes = rewritten,
    ) to parentsTouched
}

/**
 * Rewrite [oldId] -> [newId] in every clip's [Clip.sourceBinding] across every
 * track. Preserves clip order, track order, and all other clip fields.
 *
 * Returns the rewritten [Timeline] paired with the count of *clips* touched
 * (not tracks).
 */
fun Timeline.rewriteSourceBinding(oldId: SourceNodeId, newId: SourceNodeId): Pair<Timeline, Int> {
    var clipsTouched = 0
    val rewrittenTracks = tracks.map { track ->
        val rewrittenClips = track.clips.map { clip ->
            if (oldId !in clip.sourceBinding) {
                clip
            } else {
                clipsTouched += 1
                val nextBinding = clip.sourceBinding.map { if (it == oldId) newId else it }.toSet()
                when (clip) {
                    is Clip.Video -> clip.copy(sourceBinding = nextBinding)
                    is Clip.Audio -> clip.copy(sourceBinding = nextBinding)
                    is Clip.Text -> clip.copy(sourceBinding = nextBinding)
                }
            }
        }
        when (track) {
            is Track.Video -> track.copy(clips = rewrittenClips)
            is Track.Audio -> track.copy(clips = rewrittenClips)
            is Track.Subtitle -> track.copy(clips = rewrittenClips)
            is Track.Effect -> track.copy(clips = rewrittenClips)
        }
    }
    return copy(tracks = rewrittenTracks) to clipsTouched
}

/**
 * Rewrite [oldId] -> [newId] in every lockfile entry's `sourceBinding` set and
 * `sourceContentHashes` map key.
 *
 * Returns the rewritten [Lockfile] paired with the count of *entries* touched
 * (an entry counts once even if both fields contained the id).
 */
fun Lockfile.rewriteSourceBinding(oldId: SourceNodeId, newId: SourceNodeId): Pair<Lockfile, Int> {
    var touched = 0
    val rewritten = entries.map { entry ->
        val inBinding = oldId in entry.sourceBinding
        val inHashes = oldId in entry.sourceContentHashes
        if (!inBinding && !inHashes) {
            entry
        } else {
            touched += 1
            val nextBinding = if (inBinding) {
                entry.sourceBinding.map { if (it == oldId) newId else it }.toSet()
            } else {
                entry.sourceBinding
            }
            val nextHashes = if (inHashes) {
                entry.sourceContentHashes
                    .mapKeys { (k, _) -> if (k == oldId) newId else k }
            } else {
                entry.sourceContentHashes
            }
            entry.copy(
                sourceBinding = nextBinding,
                sourceContentHashes = nextHashes,
            )
        }
    }
    return copy(entries = rewritten) to touched
}
