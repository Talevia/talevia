package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.project.query.StaleClipReportRow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Cycle 138 folded `find_stale_clips` into
 * `project_query(select=stale_clips)`. This suite continues to round-trip
 * the lockfile-driven detector through the unified dispatcher and
 * pin the legacy / imported-media skip rules.
 */
class FindStaleClipsToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun newStore(): FileProjectStore = ProjectStoreTestKit.create()

    private fun videoClip(id: String, asset: AssetId, binding: Set<SourceNodeId> = emptySet()): Clip.Video =
        Clip.Video(
            id = ClipId(id),
            timeRange = TimeRange(start = 0.seconds, duration = 1.seconds),
            sourceRange = TimeRange(start = 0.seconds, duration = 1.seconds),
            assetId = asset,
            sourceBinding = binding,
        )

    private suspend fun seedProjectWithClip(
        store: FileProjectStore,
        projectId: ProjectId,
        clip: Clip.Video,
    ) {
        val track = Track.Video(id = TrackId("v0"), clips = listOf(clip))
        store.upsert(
            "demo",
            Project(id = projectId, timeline = Timeline(tracks = listOf(track))),
        )
    }

    private fun fakeProvenance(seed: Long = 1L): GenerationProvenance =
        GenerationProvenance(
            providerId = "fake",
            modelId = "fake-model",
            modelVersion = null,
            seed = seed,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 1_700_000_000_000L,
        )

    private suspend fun appendLockfile(
        store: FileProjectStore,
        projectId: ProjectId,
        entry: LockfileEntry,
    ) {
        store.mutate(projectId) { it.copy(lockfile = it.lockfile.append(entry)) }
    }

    private fun staleInput(projectId: String, limit: Int? = null) = ProjectQueryTool.Input(
        projectId = projectId,
        select = ProjectQueryTool.SELECT_STALE_CLIPS,
        limit = limit,
    )

    private fun decodeRows(out: ProjectQueryTool.Output): List<StaleClipReportRow> {
        assertEquals(ProjectQueryTool.SELECT_STALE_CLIPS, out.select)
        return JsonConfig.default.decodeFromJsonElement(
            ListSerializer(StaleClipReportRow.serializer()),
            out.rows,
        )
    }

    @Test fun freshProjectReportsZeroStale() = runTest {
        val store = newStore()
        val pid = ProjectId("p-fresh")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
        }
        val nowHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))
        appendLockfile(
            store,
            pid,
            LockfileEntry(
                inputHash = "h1",
                toolId = "generate_image",
                assetId = asset,
                provenance = fakeProvenance(),
                sourceBinding = setOf(SourceNodeId("mei")),
                sourceContentHashes = mapOf(SourceNodeId("mei") to nowHash),
            ),
        )

        val out = ProjectQueryTool(store).execute(staleInput(pid.value), ctx()).data
        assertEquals(0, out.total)
        assertEquals(0, out.returned)
        assertTrue(decodeRows(out).isEmpty())
    }

    @Test fun characterEditFlagsBoundClip() = runTest {
        val store = newStore()
        val pid = ProjectId("p-stale")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
        }
        val originalHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))
        appendLockfile(
            store,
            pid,
            LockfileEntry(
                inputHash = "h1",
                toolId = "generate_image",
                assetId = asset,
                provenance = fakeProvenance(),
                sourceBinding = setOf(SourceNodeId("mei")),
                sourceContentHashes = mapOf(SourceNodeId("mei") to originalHash),
            ),
        )

        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red hair"),
                    ),
                )
            }
        }

        val out = ProjectQueryTool(store).execute(staleInput(pid.value), ctx()).data
        assertEquals(1, out.total)
        val rows = decodeRows(out)
        assertEquals(1, rows.size)
        val report = rows.single()
        assertEquals("c-1", report.clipId)
        assertEquals(asset.value, report.assetId)
        assertEquals(listOf("mei"), report.changedSourceIds)
    }

    @Test fun reportsOnlyTheBoundNodesThatChanged() = runTest {
        val store = newStore()
        val pid = ProjectId("p-multi")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
                .let { s -> s.addStyleBible(SourceNodeId("noir"), StyleBibleBody(name = "noir", description = "noir, high contrast")) }
        }
        val src = store.get(pid)!!.source
        val meiHash = src.deepContentHashOf(SourceNodeId("mei"))
        val noirHash = src.deepContentHashOf(SourceNodeId("noir"))
        appendLockfile(
            store,
            pid,
            LockfileEntry(
                inputHash = "h1",
                toolId = "generate_image",
                assetId = asset,
                provenance = fakeProvenance(),
                sourceBinding = setOf(SourceNodeId("mei"), SourceNodeId("noir")),
                sourceContentHashes = mapOf(
                    SourceNodeId("mei") to meiHash,
                    SourceNodeId("noir") to noirHash,
                ),
            ),
        )

        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("noir")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        StyleBibleBody.serializer(),
                        StyleBibleBody(name = "noir", description = "vibrant pop"),
                    ),
                )
            }
        }

        val out = ProjectQueryTool(store).execute(staleInput(pid.value), ctx()).data
        assertEquals(1, out.total)
        val report = decodeRows(out).single()
        assertEquals(listOf("noir"), report.changedSourceIds)
    }

    @Test fun transitiveConsistencyEditFlagsGrandchildBoundClip() = runTest {
        val store = newStore()
        val pid = ProjectId("p-transitive")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset, binding = setOf(SourceNodeId("mei"))))

        store.mutateSource(pid) {
            it.addStyleBible(
                SourceNodeId("noir"),
                StyleBibleBody(name = "noir", description = "high-contrast noir"),
            ).addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
                parents = listOf(SourceRef(SourceNodeId("noir"))),
            )
        }
        val snapshotHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))
        appendLockfile(
            store,
            pid,
            LockfileEntry(
                inputHash = "h-transitive",
                toolId = "generate_image",
                assetId = asset,
                provenance = fakeProvenance(),
                sourceBinding = setOf(SourceNodeId("mei")),
                sourceContentHashes = mapOf(SourceNodeId("mei") to snapshotHash),
            ),
        )
        val tool = ProjectQueryTool(store)
        val baseline = tool.execute(staleInput(pid.value), ctx()).data
        assertEquals(0, baseline.total, "freshly-snapshotted project must not report stale")

        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("noir")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        StyleBibleBody.serializer(),
                        StyleBibleBody(name = "noir", description = "vibrant pop"),
                    ),
                )
            }
        }

        val out = tool.execute(staleInput(pid.value), ctx()).data
        assertEquals(1, out.total, "parent-edit must flag the child-bound clip stale through deep-hash drift")
        val report = decodeRows(out).single()
        assertEquals("c-1", report.clipId)
        // Detector names only the directly-bound-and-drifted id. The parent
        // (`noir`) caused the drift but isn't in the clip's binding.
        assertEquals(listOf("mei"), report.changedSourceIds)
    }

    @Test fun legacyEntryWithoutSnapshotIsSkipped() = runTest {
        val store = newStore()
        val pid = ProjectId("p-legacy")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }

        appendLockfile(
            store,
            pid,
            LockfileEntry(
                inputHash = "h1",
                toolId = "generate_image",
                assetId = asset,
                provenance = fakeProvenance(),
                sourceBinding = setOf(SourceNodeId("mei")),
                sourceContentHashes = emptyMap(),
            ),
        )

        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red"),
                    ),
                )
            }
        }

        val out = ProjectQueryTool(store).execute(staleInput(pid.value), ctx()).data
        assertEquals(0, out.total)
        assertTrue(decodeRows(out).isEmpty())
    }

    @Test fun importedClipWithoutLockfileEntryIsSkipped() = runTest {
        val store = newStore()
        val pid = ProjectId("p-imported")
        val asset = AssetId("a-imported")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        val out = ProjectQueryTool(store).execute(staleInput(pid.value), ctx()).data
        assertEquals(0, out.total)
        assertTrue(decodeRows(out).isEmpty())
    }

    @Test fun emptyLockfileShortCircuits() = runTest {
        val store = newStore()
        val pid = ProjectId("p-empty")
        store.upsert("demo", Project(id = pid, timeline = Timeline()))

        val out = ProjectQueryTool(store).execute(staleInput(pid.value), ctx()).data
        assertEquals(0, out.total)
        assertTrue(decodeRows(out).isEmpty())
    }

    /**
     * Seeds a project with `clipCount` AIGC clips all bound to a single
     * character ref, then drifts the character so every clip goes stale.
     */
    private suspend fun seedManyStale(
        store: FileProjectStore,
        pid: ProjectId,
        clipCount: Int,
    ) {
        val clips = (clipCount - 1 downTo 0).map { i ->
            val idx = i.toString().padStart(3, '0')
            videoClip(
                id = "c-$idx",
                asset = AssetId("a-$idx"),
                binding = setOf(SourceNodeId("mei")),
            )
        }
        val track = Track.Video(id = TrackId("v0"), clips = clips)
        store.upsert(
            "demo",
            Project(id = pid, timeline = Timeline(tracks = listOf(track))),
        )
        store.mutateSource(pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        val originalHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))
        clips.forEach { clip ->
            appendLockfile(
                store,
                pid,
                LockfileEntry(
                    inputHash = "h-${clip.assetId.value}",
                    toolId = "generate_image",
                    assetId = clip.assetId,
                    provenance = fakeProvenance(),
                    sourceBinding = setOf(SourceNodeId("mei")),
                    sourceContentHashes = mapOf(SourceNodeId("mei") to originalHash),
                ),
            )
        }
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red hair"),
                    ),
                )
            }
        }
    }

    @Test fun limitCapsRowsButKeepsTrueStaleCount() = runTest {
        val store = newStore()
        val pid = ProjectId("p-capped")
        seedManyStale(store, pid, clipCount = 12)

        val out = ProjectQueryTool(store).execute(staleInput(pid.value, limit = 5), ctx()).data
        // True total preserved.
        assertEquals(12, out.total)
        // Rows trimmed to the cap.
        assertEquals(5, out.returned)
        assertEquals(5, decodeRows(out).size)
    }

    @Test fun reportOrderIsDeterministicAcrossCalls() = runTest {
        val store = newStore()
        val pid = ProjectId("p-ordered")
        seedManyStale(store, pid, clipCount = 8)

        val tool = ProjectQueryTool(store)
        val first = decodeRows(tool.execute(staleInput(pid.value), ctx()).data)
        val second = decodeRows(tool.execute(staleInput(pid.value), ctx()).data)

        assertEquals(first, second)
        // And the order is specifically ascending-by-clipId.
        assertEquals(first.map { it.clipId }, first.map { it.clipId }.sorted())
    }

    @Test fun omittedLimitFallsBackToProjectQueryDefault100() = runTest {
        val store = newStore()
        val pid = ProjectId("p-default")
        // 110 stale clips > project_query default cap of 100 (was 50 pre-fold).
        seedManyStale(store, pid, clipCount = 110)

        val out = ProjectQueryTool(store).execute(staleInput(pid.value), ctx()).data
        assertEquals(110, out.total)
        // Default limit shifted from 50 (find_stale_clips) → 100 (project_query) on the fold.
        assertEquals(100, out.returned)
    }
}
