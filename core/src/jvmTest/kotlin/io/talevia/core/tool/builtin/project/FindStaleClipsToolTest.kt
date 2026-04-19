package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Round-trips the lockfile-driven stale-clip detector through `find_stale_clips`.
 * The detector itself has unit coverage on the domain extension; this suite proves
 * the tool surfaces the right shape to the agent and respects the legacy /
 * imported-media skip rules.
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

    private fun newStore(): Pair<SqlDelightProjectStore, JdbcSqliteDriver> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightProjectStore(TaleviaDb(driver)) to driver
    }

    private fun videoClip(id: String, asset: AssetId, binding: Set<SourceNodeId> = emptySet()): Clip.Video =
        Clip.Video(
            id = ClipId(id),
            timeRange = TimeRange(start = 0.seconds, duration = 1.seconds),
            sourceRange = TimeRange(start = 0.seconds, duration = 1.seconds),
            assetId = asset,
            sourceBinding = binding,
        )

    private suspend fun seedProjectWithClip(
        store: SqlDelightProjectStore,
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
        store: SqlDelightProjectStore,
        projectId: ProjectId,
        entry: LockfileEntry,
    ) {
        store.mutate(projectId) { it.copy(lockfile = it.lockfile.append(entry)) }
    }

    @Test fun freshProjectReportsZeroStale() = runTest {
        val (store, _) = newStore()
        val pid = ProjectId("p-fresh")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        // Set up source + lockfile so the snapshot matches the current source hash.
        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
        }
        val nowHash = store.get(pid)!!.source.byId[SourceNodeId("mei")]!!.contentHash
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

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(0, out.staleClipCount)
        assertEquals(1, out.totalClipCount)
        assertTrue(out.reports.isEmpty())
    }

    @Test fun characterEditFlagsBoundClip() = runTest {
        val (store, _) = newStore()
        val pid = ProjectId("p-stale")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
        }
        val originalHash = store.get(pid)!!.source.byId[SourceNodeId("mei")]!!.contentHash
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

        // User edits the character — content hash changes.
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

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(1, out.staleClipCount)
        assertEquals(1, out.reports.size)
        val report = out.reports.single()
        assertEquals("c-1", report.clipId)
        assertEquals(asset.value, report.assetId)
        assertEquals(listOf("mei"), report.changedSourceIds)
    }

    @Test fun reportsOnlyTheBoundNodesThatChanged() = runTest {
        val (store, _) = newStore()
        val pid = ProjectId("p-multi")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
                .let { s -> s.addStyleBible(SourceNodeId("noir"), StyleBibleBody(name = "noir", description = "noir, high contrast")) }
        }
        val src = store.get(pid)!!.source
        val meiHash = src.byId[SourceNodeId("mei")]!!.contentHash
        val noirHash = src.byId[SourceNodeId("noir")]!!.contentHash
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

        // Only the style bible changes.
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

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(1, out.staleClipCount)
        val report = out.reports.single()
        // Detector reports only the *direct* drifted ids; mei is unchanged so absent.
        assertEquals(listOf("noir"), report.changedSourceIds)
    }

    @Test fun legacyEntryWithoutSnapshotIsSkipped() = runTest {
        val (store, _) = newStore()
        val pid = ProjectId("p-legacy")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }

        // Legacy: empty sourceContentHashes — pre-snapshot lockfile entry.
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

        // Even after a real edit, legacy entries are "unknown" — never stale, never fresh.
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

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(0, out.staleClipCount)
        assertTrue(out.reports.isEmpty())
    }

    @Test fun importedClipWithoutLockfileEntryIsSkipped() = runTest {
        val (store, _) = newStore()
        val pid = ProjectId("p-imported")
        val asset = AssetId("a-imported")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        // No lockfile entry at all — the clip plays an imported asset.
        // (Source has no nodes either; the detector should still gracefully skip.)

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(0, out.staleClipCount)
        assertEquals(1, out.totalClipCount)
        assertTrue(out.reports.isEmpty())
    }

    @Test fun emptyLockfileShortCircuits() = runTest {
        val (store, _) = newStore()
        val pid = ProjectId("p-empty")
        store.upsert("demo", Project(id = pid, timeline = Timeline()))

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(0, out.staleClipCount)
        assertEquals(0, out.totalClipCount)
        assertTrue(out.reports.isEmpty())
    }
}
