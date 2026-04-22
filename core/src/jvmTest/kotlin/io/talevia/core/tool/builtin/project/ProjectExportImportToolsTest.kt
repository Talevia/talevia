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
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProjectExportImportToolsTest {

    private data class Rig(
        val store: FileProjectStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val store = ProjectStoreTestKit.create()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, ctx)
    }

    private suspend fun seedProject(store: FileProjectStore, id: String = "p-src"): Project {
        val project = Project(
            id = ProjectId(id),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        id = TrackId("v"),
                        clips = listOf(
                            Clip.Video(
                                id = ClipId("c-1"),
                                timeRange = TimeRange(0.seconds, 2.seconds),
                                sourceRange = TimeRange(0.seconds, 2.seconds),
                                assetId = AssetId("a-1"),
                            ),
                        ),
                    ),
                ),
                duration = 2.seconds,
            ),
            // Registered so the post-cycle-46 import integrity gate doesn't
            // refuse the round-trip (dangling-asset check). Clip → asset
            // reference resolves cleanly.
            assets = listOf(
                MediaAsset(
                    id = AssetId("a-1"),
                    source = MediaSource.File("/tmp/a-1.mp4"),
                    metadata = MediaMetadata(duration = 2.seconds),
                ),
            ),
        )
        store.upsert("Title of $id", project)
        store.mutateSource(ProjectId(id)) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        return store.get(ProjectId(id))!!
    }

    @Test fun roundTripPreservesTimelineAndSource() = runTest {
        val rig = rig()
        seedProject(rig.store)

        val exportOut = ExportProjectTool(rig.store).execute(
            ExportProjectTool.Input(projectId = "p-src"),
            rig.ctx,
        ).data
        assertEquals(ExportProjectTool.FORMAT_VERSION, exportOut.formatVersion)
        assertEquals(1, exportOut.clipCount)
        assertEquals(1, exportOut.sourceNodeCount)

        // Rename on import so it doesn't collide with the source project.
        val importOut = ImportProjectFromJsonTool(rig.store).execute(
            ImportProjectFromJsonTool.Input(
                envelope = exportOut.envelope,
                newProjectId = "p-dst",
            ),
            rig.ctx,
        ).data

        assertEquals("p-dst", importOut.projectId)
        assertEquals("Title of p-src", importOut.title)

        val target = rig.store.get(ProjectId("p-dst"))!!
        assertEquals(1, target.timeline.tracks.size)
        assertEquals(1, target.timeline.tracks.single().clips.size)
        assertTrue(SourceNodeId("mei") in target.source.byId)
    }

    @Test fun reuseOriginalIdWhenNoRenameGiven() = runTest {
        val rig = rig()
        seedProject(rig.store, id = "p-src")
        val exportOut = ExportProjectTool(rig.store).execute(
            ExportProjectTool.Input(projectId = "p-src"),
            rig.ctx,
        ).data
        // Delete source first so the original id is free.
        rig.store.delete(ProjectId("p-src"))

        val importOut = ImportProjectFromJsonTool(rig.store).execute(
            ImportProjectFromJsonTool.Input(envelope = exportOut.envelope),
            rig.ctx,
        ).data

        // Landed at the original id.
        assertEquals("p-src", importOut.projectId)
    }

    @Test fun collisionWithoutRenameFailsLoud() = runTest {
        val rig = rig()
        seedProject(rig.store, id = "p-src")
        val envelope = ExportProjectTool(rig.store).execute(
            ExportProjectTool.Input(projectId = "p-src"),
            rig.ctx,
        ).data.envelope

        // Attempt to import back into the same store without renaming.
        val ex = assertFailsWith<IllegalArgumentException> {
            ImportProjectFromJsonTool(rig.store).execute(
                ImportProjectFromJsonTool.Input(envelope = envelope),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("already exists"), ex.message)
    }

    @Test fun unknownFormatVersionRejected() = runTest {
        val rig = rig()
        seedProject(rig.store, id = "p-src")
        val exportOut = ExportProjectTool(rig.store).execute(
            ExportProjectTool.Input(projectId = "p-src"),
            rig.ctx,
        ).data
        // Mutate the formatVersion to a future value — the envelope is
        // otherwise well-formed so the parse succeeds and the version
        // require() surfaces the loud error.
        val spoofed = exportOut.envelope.replace(
            ExportProjectTool.FORMAT_VERSION,
            "talevia-project-export-v999",
        )

        val ex = assertFailsWith<IllegalArgumentException> {
            ImportProjectFromJsonTool(rig.store).execute(
                ImportProjectFromJsonTool.Input(envelope = spoofed),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("v999"), ex.message)
    }

    @Test fun malformedEnvelopeFails() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ImportProjectFromJsonTool(rig.store).execute(
                ImportProjectFromJsonTool.Input(envelope = "not-json"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not valid JSON"), ex.message)
    }

    @Test fun newTitleOverridesDefault() = runTest {
        val rig = rig()
        seedProject(rig.store, id = "p-src")
        val exportOut = ExportProjectTool(rig.store).execute(
            ExportProjectTool.Input(projectId = "p-src"),
            rig.ctx,
        ).data

        val importOut = ImportProjectFromJsonTool(rig.store).execute(
            ImportProjectFromJsonTool.Input(
                envelope = exportOut.envelope,
                newProjectId = "p-dst",
                newTitle = "Custom Title",
            ),
            rig.ctx,
        ).data

        assertEquals("Custom Title", importOut.title)
        val summary = rig.store.listSummaries().single { it.id == "p-dst" }
        assertEquals("Custom Title", summary.title)
    }

    @Test fun prettyPrintProducesLargerEnvelope() = runTest {
        val rig = rig()
        seedProject(rig.store)

        val compact = ExportProjectTool(rig.store).execute(
            ExportProjectTool.Input(projectId = "p-src", prettyPrint = false),
            rig.ctx,
        ).data
        val pretty = ExportProjectTool(rig.store).execute(
            ExportProjectTool.Input(projectId = "p-src", prettyPrint = true),
            rig.ctx,
        ).data

        assertTrue(pretty.envelope.length > compact.envelope.length)
    }

    @Test fun missingProjectFailsLoudOnExport() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ExportProjectTool(rig.store).execute(
                ExportProjectTool.Input(projectId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    // ---- integrity validation on import ------------------------------------

    /**
     * Hand-build an envelope from an in-memory [Project] without going through
     * `export_project`, so tests can ship malformed payloads (dangling refs,
     * parent cycles) that a well-behaved export path would never produce.
     */
    private fun envelopeOf(project: Project, title: String = "Broken"): String {
        val envelope = ProjectEnvelope(
            formatVersion = ExportProjectTool.FORMAT_VERSION,
            title = title,
            project = project,
        )
        return JsonConfig.default.encodeToString(ProjectEnvelope.serializer(), envelope)
    }

    private fun fakeAsset(id: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File(path = "/tmp/$id.mp4"),
        metadata = MediaMetadata(duration = 2.seconds),
    )

    private fun projectWithDanglingAsset(): Project = Project(
        id = ProjectId("p-broken"),
        timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c-1"),
                            timeRange = TimeRange(0.seconds, 2.seconds),
                            sourceRange = TimeRange(0.seconds, 2.seconds),
                            assetId = AssetId("a-missing"),
                        ),
                    ),
                ),
            ),
            duration = 2.seconds,
        ),
        // assets intentionally empty so the clip's assetId dangles
    )

    @Test fun importRejectsEnvelopeWithDanglingAsset() = runTest {
        val rig = rig()
        val envelope = envelopeOf(projectWithDanglingAsset())
        val ex = assertFailsWith<IllegalStateException> {
            ImportProjectFromJsonTool(rig.store).execute(
                ImportProjectFromJsonTool.Input(envelope = envelope),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("integrity"), ex.message)
        assertTrue(ex.message!!.contains("dangling-asset"), ex.message)
        // Project must NOT have been upserted.
        assertEquals(null, rig.store.get(ProjectId("p-broken")))
    }

    @Test fun importRejectsEnvelopeWithDanglingSourceBinding() = runTest {
        val rig = rig()
        val project = Project(
            id = ProjectId("p-broken-src"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        id = TrackId("v"),
                        clips = listOf(
                            Clip.Video(
                                id = ClipId("c-1"),
                                timeRange = TimeRange(0.seconds, 2.seconds),
                                sourceRange = TimeRange(0.seconds, 2.seconds),
                                assetId = AssetId("a-1"),
                                sourceBinding = setOf(SourceNodeId("ghost")),
                            ),
                        ),
                    ),
                ),
                duration = 2.seconds,
            ),
            assets = listOf(fakeAsset("a-1")),
        )
        val envelope = envelopeOf(project)
        val ex = assertFailsWith<IllegalStateException> {
            ImportProjectFromJsonTool(rig.store).execute(
                ImportProjectFromJsonTool.Input(envelope = envelope),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("dangling-source-binding"), ex.message)
        assertEquals(null, rig.store.get(ProjectId("p-broken-src")))
    }

    @Test fun importRejectsEnvelopeWithSourceParentCycle() = runTest {
        val rig = rig()
        // Hand-author a source with a→b and b→a in the parents relation.
        val nodeA = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "test.node",
            parents = listOf(SourceRef(SourceNodeId("b"))),
        )
        val nodeB = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "test.node",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val project = Project(
            id = ProjectId("p-cycle"),
            timeline = Timeline(),
            source = Source(nodes = listOf(nodeA, nodeB)),
        )
        val envelope = envelopeOf(project)
        val ex = assertFailsWith<IllegalStateException> {
            ImportProjectFromJsonTool(rig.store).execute(
                ImportProjectFromJsonTool.Input(envelope = envelope),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("source-parent-cycle"), ex.message)
        assertEquals(null, rig.store.get(ProjectId("p-cycle")))
    }

    @Test fun importWithForceTrueBypassesIntegrityGate() = runTest {
        val rig = rig()
        val envelope = envelopeOf(projectWithDanglingAsset())
        val out = ImportProjectFromJsonTool(rig.store).execute(
            ImportProjectFromJsonTool.Input(envelope = envelope, force = true),
            rig.ctx,
        ).data

        // Upsert succeeded…
        assertEquals("p-broken", out.projectId)
        assertTrue(rig.store.get(ProjectId("p-broken")) != null)
        // …and the Output still carried the error counters so the caller
        // knows what they bypassed.
        assertTrue(out.validationErrorCount >= 1)
        assertTrue(out.validationIssueCodes.contains("dangling-asset"))
    }

    @Test fun warningOnlyEnvelopeImportsAndReportsWarnCount() = runTest {
        val rig = rig()
        // duration-mismatch: timeline says 0s but the clip ends at 2s.
        // This is a warning, not an error, so the import should succeed.
        val project = Project(
            id = ProjectId("p-warn"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        id = TrackId("v"),
                        clips = listOf(
                            Clip.Video(
                                id = ClipId("c-1"),
                                timeRange = TimeRange(0.seconds, 2.seconds),
                                sourceRange = TimeRange(0.seconds, 2.seconds),
                                assetId = AssetId("a-1"),
                            ),
                        ),
                    ),
                ),
                duration = kotlin.time.Duration.ZERO,
            ),
            assets = listOf(fakeAsset("a-1")),
        )
        val envelope = envelopeOf(project)

        val out = ImportProjectFromJsonTool(rig.store).execute(
            ImportProjectFromJsonTool.Input(envelope = envelope),
            rig.ctx,
        ).data
        assertEquals(0, out.validationErrorCount)
        assertEquals(1, out.validationWarnCount)
        assertTrue(out.validationIssueCodes.contains("duration-mismatch"))
        assertTrue(rig.store.get(ProjectId("p-warn")) != null)
    }

    @Test fun cleanImportReportsZeroIssues() = runTest {
        val rig = rig()
        seedProject(rig.store)
        val envelope = ExportProjectTool(rig.store).execute(
            ExportProjectTool.Input(projectId = "p-src"),
            rig.ctx,
        ).data.envelope

        val out = ImportProjectFromJsonTool(rig.store).execute(
            ImportProjectFromJsonTool.Input(envelope = envelope, newProjectId = "p-dst"),
            rig.ctx,
        ).data
        assertEquals(0, out.validationIssueCount)
        assertEquals(0, out.validationErrorCount)
        assertEquals(0, out.validationWarnCount)
        assertTrue(out.validationIssueCodes.isEmpty())
    }
}
