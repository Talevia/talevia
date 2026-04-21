package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
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
        val store: SqlDelightProjectStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
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

    private suspend fun seedProject(store: SqlDelightProjectStore, id: String = "p-src"): Project {
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
}
