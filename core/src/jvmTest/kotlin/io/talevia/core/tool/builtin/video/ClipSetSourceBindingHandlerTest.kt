package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [executeClipSetSourceBinding] —
 * `core/tool/builtin/video/ClipSetSourceBindingHandler.kt`.
 * The `clip_action(action="set_sourceBinding")` handler.
 * Cycle 193 audit: 76 LOC, 0 direct test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Pre-commit existence guard: every new
 *    SourceNodeId MUST exist in `project.source.byId`.**
 *    Per kdoc: "Pre-commit guard that every new id
 *    exists ... dangling bindings would silently stale
 *    the clip forever." Drift to "skip check" would let
 *    clips bind to ghost source nodes that never resolve.
 *
 * 2. **Set-swap semantics: REPLACES the entire previous
 *    binding (NOT merge).** Per kdoc: "Set-swap semantics:
 *    replaces the clip's `sourceBinding` set entirely with
 *    the provided one." Drift to "merge with previous"
 *    would silently keep stale bindings the user removed.
 *
 * 3. **Works on all 3 Clip variants (Video/Audio/Text)**
 *    with the sealed-class `when` exhaustively rebinding
 *    each. Drift to "Video only" would silently no-op
 *    audio/text rebinds. Each result row carries
 *    previousBinding (sorted) and newBinding (sorted).
 */
class ClipSetSourceBindingHandlerTest {

    private val ctx: ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { /* no-op */ },
        messages = emptyList(),
    )

    private fun anyRange() = TimeRange(start = 0.seconds, duration = 1.seconds)

    private fun videoClip(
        id: String,
        sourceBinding: Set<String> = emptySet(),
    ): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = anyRange(),
        sourceRange = anyRange(),
        assetId = AssetId("asset-$id"),
        sourceBinding = sourceBinding.map { SourceNodeId(it) }.toSet(),
    )

    private fun audioClip(
        id: String,
        sourceBinding: Set<String> = emptySet(),
    ): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = anyRange(),
        sourceRange = anyRange(),
        assetId = AssetId("asset-$id"),
        sourceBinding = sourceBinding.map { SourceNodeId(it) }.toSet(),
    )

    private fun textClip(
        id: String,
        sourceBinding: Set<String> = emptySet(),
    ): Clip.Text = Clip.Text(
        id = ClipId(id),
        timeRange = anyRange(),
        text = "hi",
        sourceBinding = sourceBinding.map { SourceNodeId(it) }.toSet(),
    )

    private fun sourceNode(id: String): SourceNode = SourceNode.create(
        id = SourceNodeId(id),
        kind = "test",
    )

    private suspend fun newProjectWithClipsAndSourceNodes(
        store: io.talevia.core.domain.FileProjectStore,
        clips: List<Clip>,
        sourceNodeIds: List<String>,
    ): io.talevia.core.domain.Project {
        val created = store.createAt(
            path = "/projects/p1".toPath(),
            title = "Test",
        )
        return store.mutate(created.id) { p ->
            p.copy(
                source = Source(nodes = sourceNodeIds.map { sourceNode(it) }),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(id = TrackId("v"), clips = clips),
                    ),
                ),
            )
        }
    }

    private fun input(
        items: List<ClipActionTool.SourceBindingItem>?,
    ): ClipActionTool.Input = ClipActionTool.Input(
        action = "set_sourceBinding",
        sourceBindingItems = items,
    )

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingSourceBindingItemsThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClipsAndSourceNodes(
            store, clips = emptyList(), sourceNodeIds = emptyList(),
        )
        val ex = assertFailsWith<IllegalStateException> {
            executeClipSetSourceBinding(
                store = store,
                pid = project.id,
                input = input(items = null),
                ctx = ctx,
            )
        }
        assertTrue(
            "requires `sourceBindingItems`" in (ex.message ?: ""),
            "expected requires-items phrase; got: ${ex.message}",
        )
    }

    @Test fun emptyItemsThrowsViaRequire() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClipsAndSourceNodes(
            store, clips = emptyList(), sourceNodeIds = emptyList(),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipSetSourceBinding(
                store = store,
                pid = project.id,
                input = input(items = emptyList()),
                ctx = ctx,
            )
        }
        assertTrue(
            "must not be empty" in (ex.message ?: ""),
            "expected empty-items phrase; got: ${ex.message}",
        )
    }

    @Test fun missingClipThrowsWithItemIndexAndClipId() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClipsAndSourceNodes(
            store,
            clips = listOf(videoClip("c1")),
            sourceNodeIds = listOf("sn1"),
        )

        val ex = assertFailsWith<IllegalStateException> {
            executeClipSetSourceBinding(
                store = store,
                pid = project.id,
                input = input(
                    items = listOf(
                        ClipActionTool.SourceBindingItem(
                            clipId = "ghost-clip",
                            sourceBinding = listOf("sn1"),
                        ),
                    ),
                ),
                ctx = ctx,
            )
        }
        assertTrue("ghost-clip not found" in (ex.message ?: ""))
        assertTrue("[0]" in (ex.message ?: ""), "item index in message")
    }

    // ── Pre-commit existence guard ──────────────────────────

    @Test fun unknownSourceNodeIdRejected() = runTest {
        // Marquee guard pin: per kdoc "Pre-commit guard
        // that every new id exists in `project.source.byId`
        // — dangling bindings would silently stale the
        // clip forever."
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClipsAndSourceNodes(
            store,
            clips = listOf(videoClip("c1")),
            sourceNodeIds = listOf("sn1"),
        )

        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipSetSourceBinding(
                store = store,
                pid = project.id,
                input = input(
                    items = listOf(
                        ClipActionTool.SourceBindingItem(
                            clipId = "c1",
                            sourceBinding = listOf("sn1", "ghost-sn"),
                        ),
                    ),
                ),
                ctx = ctx,
            )
        }
        assertTrue(
            "unknown source node ids" in (ex.message ?: ""),
            "expected unknown-id phrase; got: ${ex.message}",
        )
        assertTrue(
            "ghost-sn" in (ex.message ?: ""),
            "expected ghost id cited; got: ${ex.message}",
        )
        assertTrue(
            "[0]" in (ex.message ?: ""),
            "item index cited; got: ${ex.message}",
        )
    }

    @Test fun multipleUnknownIdsAllCited() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClipsAndSourceNodes(
            store,
            clips = listOf(videoClip("c1")),
            sourceNodeIds = listOf("sn1"),
        )

        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipSetSourceBinding(
                store = store,
                pid = project.id,
                input = input(
                    items = listOf(
                        ClipActionTool.SourceBindingItem(
                            clipId = "c1",
                            sourceBinding = listOf("ghost-1", "ghost-2"),
                        ),
                    ),
                ),
                ctx = ctx,
            )
        }
        assertTrue("ghost-1" in (ex.message ?: ""))
        assertTrue("ghost-2" in (ex.message ?: ""))
    }

    // ── Set-swap semantics ──────────────────────────────────

    @Test fun setSwapReplacesPreviousBindingDoesNotMerge() = runTest {
        // Marquee set-swap pin: per kdoc "replaces the
        // clip's `sourceBinding` set entirely with the
        // provided one." Drift to "merge with previous"
        // would silently keep stale bindings.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClipsAndSourceNodes(
            store,
            clips = listOf(videoClip("c1", sourceBinding = setOf("sn-old", "sn-keep"))),
            sourceNodeIds = listOf("sn-old", "sn-keep", "sn-new"),
        )

        val result = executeClipSetSourceBinding(
            store = store,
            pid = project.id,
            input = input(
                items = listOf(
                    ClipActionTool.SourceBindingItem(
                        clipId = "c1",
                        sourceBinding = listOf("sn-new"), // ONLY this — old + keep removed
                    ),
                ),
            ),
            ctx = ctx,
        )

        // Result: previous = [sn-keep, sn-old] (sorted), new = [sn-new].
        val sbResult = result.data.sourceBindingResults.single()
        assertContentEquals(
            listOf("sn-keep", "sn-old"),
            sbResult.previousBinding,
            "previousBinding sorted",
        )
        assertContentEquals(
            listOf("sn-new"),
            sbResult.newBinding,
            "newBinding sorted",
        )

        // Store reflects the new binding (just sn-new).
        val updated = store.get(project.id)!!
        val updatedClip = updated.timeline.tracks.flatMap { it.clips }
            .first { it.id.value == "c1" } as Clip.Video
        assertEquals(
            setOf(SourceNodeId("sn-new")),
            updatedClip.sourceBinding,
            "store binding is exactly sn-new (NOT merged with previous)",
        )
    }

    @Test fun emptyBindingClearsPreviousBinding() = runTest {
        // Pin: per SourceBindingItem kdoc "Empty list
        // clears the binding." A clip with prior binding
        // and an empty new-set ends up unbound.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClipsAndSourceNodes(
            store,
            clips = listOf(videoClip("c1", sourceBinding = setOf("sn1", "sn2"))),
            sourceNodeIds = listOf("sn1", "sn2"),
        )

        val result = executeClipSetSourceBinding(
            store = store,
            pid = project.id,
            input = input(
                items = listOf(
                    ClipActionTool.SourceBindingItem(
                        clipId = "c1",
                        sourceBinding = emptyList(),
                    ),
                ),
            ),
            ctx = ctx,
        )

        val updated = store.get(project.id)!!
        val updatedClip = updated.timeline.tracks.flatMap { it.clips }
            .first { it.id.value == "c1" } as Clip.Video
        assertEquals(emptySet(), updatedClip.sourceBinding, "binding cleared")
        assertContentEquals(
            emptyList(),
            result.data.sourceBindingResults.single().newBinding,
        )
    }

    // ── Works on all 3 Clip variants ─────────────────────────

    @Test fun videoClipRebindWorks() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClipsAndSourceNodes(
            store,
            clips = listOf(videoClip("v1")),
            sourceNodeIds = listOf("sn"),
        )

        executeClipSetSourceBinding(
            store = store,
            pid = project.id,
            input = input(
                items = listOf(
                    ClipActionTool.SourceBindingItem(
                        clipId = "v1",
                        sourceBinding = listOf("sn"),
                    ),
                ),
            ),
            ctx = ctx,
        )

        val updated = store.get(project.id)!!
        val v = updated.timeline.tracks.flatMap { it.clips }.first { it.id.value == "v1" } as Clip.Video
        assertEquals(setOf(SourceNodeId("sn")), v.sourceBinding)
    }

    @Test fun audioClipRebindWorks() = runTest {
        val store = ProjectStoreTestKit.create()
        // Need to put audio clip on a track. We've put
        // them all on a Track.Video so far; this test
        // uses a Track.Audio.
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Test")
        val project = store.mutate(created.id) { p ->
            p.copy(
                source = Source(nodes = listOf(sourceNode("sn"))),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Audio(id = TrackId("a"), clips = listOf(audioClip("a1"))),
                    ),
                ),
            )
        }

        executeClipSetSourceBinding(
            store = store,
            pid = project.id,
            input = input(
                items = listOf(
                    ClipActionTool.SourceBindingItem(
                        clipId = "a1",
                        sourceBinding = listOf("sn"),
                    ),
                ),
            ),
            ctx = ctx,
        )

        val updated = store.get(project.id)!!
        val a = updated.timeline.tracks.flatMap { it.clips }.first { it.id.value == "a1" } as Clip.Audio
        assertEquals(setOf(SourceNodeId("sn")), a.sourceBinding, "Audio clip rebind works")
    }

    @Test fun textClipRebindWorks() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Test")
        val project = store.mutate(created.id) { p ->
            p.copy(
                source = Source(nodes = listOf(sourceNode("sn"))),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Subtitle(id = TrackId("st"), clips = listOf(textClip("t1"))),
                    ),
                ),
            )
        }

        executeClipSetSourceBinding(
            store = store,
            pid = project.id,
            input = input(
                items = listOf(
                    ClipActionTool.SourceBindingItem(
                        clipId = "t1",
                        sourceBinding = listOf("sn"),
                    ),
                ),
            ),
            ctx = ctx,
        )

        val updated = store.get(project.id)!!
        val t = updated.timeline.tracks.flatMap { it.clips }.first { it.id.value == "t1" } as Clip.Text
        assertEquals(setOf(SourceNodeId("sn")), t.sourceBinding, "Text clip rebind works")
    }

    // ── Multi-item batch ─────────────────────────────────────

    @Test fun multipleItemsAllRebindAtomically() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClipsAndSourceNodes(
            store,
            clips = listOf(videoClip("c1"), videoClip("c2"), videoClip("c3")),
            sourceNodeIds = listOf("sn1", "sn2"),
        )

        val result = executeClipSetSourceBinding(
            store = store,
            pid = project.id,
            input = input(
                items = listOf(
                    ClipActionTool.SourceBindingItem(clipId = "c1", sourceBinding = listOf("sn1")),
                    ClipActionTool.SourceBindingItem(clipId = "c2", sourceBinding = listOf("sn2")),
                    ClipActionTool.SourceBindingItem(clipId = "c3", sourceBinding = listOf("sn1", "sn2")),
                ),
            ),
            ctx = ctx,
        )

        assertEquals(3, result.data.sourceBindingResults.size)
        // outputForLlm cites count.
        assertTrue("3 clip(s)" in result.outputForLlm)
        // Each clip rebound.
        val updated = store.get(project.id)!!
        val byId = updated.timeline.tracks.flatMap { it.clips }.associateBy { it.id.value }
        assertEquals(setOf(SourceNodeId("sn1")), (byId["c1"] as Clip.Video).sourceBinding)
        assertEquals(setOf(SourceNodeId("sn2")), (byId["c2"] as Clip.Video).sourceBinding)
        assertEquals(
            setOf(SourceNodeId("sn1"), SourceNodeId("sn2")),
            (byId["c3"] as Clip.Video).sourceBinding,
        )
    }

    @Test fun singleItemFailureAbortsBatchAtomically() = runTest {
        // Pin: per `store.mutate`'s semantics, a `require`
        // failure inside the lambda rolls back the whole
        // mutation. Drift to "partial commit" would let
        // earlier items stick while later ones fail.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClipsAndSourceNodes(
            store,
            clips = listOf(videoClip("c1"), videoClip("c2")),
            sourceNodeIds = listOf("sn1"), // sn2 NOT seeded
        )

        assertFailsWith<IllegalArgumentException> {
            executeClipSetSourceBinding(
                store = store,
                pid = project.id,
                input = input(
                    items = listOf(
                        ClipActionTool.SourceBindingItem(clipId = "c1", sourceBinding = listOf("sn1")),
                        // c2 binding to ghost sn2 — fails the existence check.
                        ClipActionTool.SourceBindingItem(clipId = "c2", sourceBinding = listOf("sn2")),
                    ),
                ),
                ctx = ctx,
            )
        }

        // After failure: c1 binding NOT mutated (atomic rollback).
        val state = store.get(project.id)!!
        val clipsByid = state.timeline.tracks.flatMap { it.clips }.associateBy { it.id.value }
        assertEquals(
            emptySet(),
            (clipsByid["c1"] as Clip.Video).sourceBinding,
            "c1 binding NOT mutated after batch failure (atomic rollback)",
        )
    }

    // ── Output shape ─────────────────────────────────────────

    @Test fun outputCarriesProjectIdActionAndSnapshotId() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClipsAndSourceNodes(
            store,
            clips = listOf(videoClip("c1")),
            sourceNodeIds = listOf("sn"),
        )

        val result = executeClipSetSourceBinding(
            store = store,
            pid = project.id,
            input = input(
                items = listOf(
                    ClipActionTool.SourceBindingItem(clipId = "c1", sourceBinding = listOf("sn")),
                ),
            ),
            ctx = ctx,
        )
        assertEquals(project.id.value, result.data.projectId)
        assertEquals("set_sourceBinding", result.data.action)
        assertTrue(
            result.data.snapshotId.isNotBlank(),
            "snapshotId populated; got: ${result.data.snapshotId}",
        )
    }

    @Test fun toolResultTitleCitesRebindCount() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClipsAndSourceNodes(
            store,
            clips = listOf(videoClip("c1"), videoClip("c2")),
            sourceNodeIds = listOf("sn"),
        )

        val result = executeClipSetSourceBinding(
            store = store,
            pid = project.id,
            input = input(
                items = listOf(
                    ClipActionTool.SourceBindingItem(clipId = "c1", sourceBinding = listOf("sn")),
                    ClipActionTool.SourceBindingItem(clipId = "c2", sourceBinding = listOf("sn")),
                ),
            ),
            ctx = ctx,
        )
        assertTrue("rebind" in result.title!!)
        assertTrue("× 2" in result.title!!, "title cites count; got: ${result.title}")
    }
}
