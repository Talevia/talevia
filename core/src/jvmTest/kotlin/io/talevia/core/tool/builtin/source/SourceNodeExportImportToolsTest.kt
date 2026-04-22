package io.talevia.core.tool.builtin.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.consistency.asCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trip coverage for [ExportSourceNodeTool] + [ImportSourceNodeTool] (envelope path)
 * — the cross-instance leg of VISION §5.1 "Source 能不能序列化、版本化、跨 project 复用?"
 */
class SourceNodeExportImportToolsTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val ctx: ToolContext,
    )

    private suspend fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        store.upsert("from", Project(id = ProjectId("from"), timeline = Timeline()))
        store.upsert("to", Project(id = ProjectId("to"), timeline = Timeline()))
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

    @Test fun roundTripPreservesCharacterRefBody() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("from")) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair", voiceId = "nova"),
            )
        }

        val export = ExportSourceNodeTool(rig.store).execute(
            ExportSourceNodeTool.Input(projectId = "from", nodeId = "mei"),
            rig.ctx,
        )
        assertEquals(1, export.data.nodeCount)
        assertEquals(listOf("core.consistency.character_ref"), export.data.kinds)

        val import = ImportSourceNodeTool(rig.store).execute(
            ImportSourceNodeTool.Input(
                toProjectId = "to",
                envelope = export.data.envelope,
            ),
            rig.ctx,
        )
        assertEquals(ExportSourceNodeTool.FORMAT_VERSION, import.data.formatVersion)
        val leaf = import.data.nodes.single()
        assertEquals("mei", leaf.originalId)
        assertEquals("mei", leaf.importedId)
        assertFalse(leaf.skippedDuplicate)

        // Body round-tripped verbatim.
        val targetNode = rig.store.get(ProjectId("to"))!!.source.byId[SourceNodeId("mei")]!!
        val body = targetNode.asCharacterRef()!!
        assertEquals("Mei", body.name)
        assertEquals("teal hair", body.visualDescription)
        assertEquals("nova", body.voiceId)
    }

    @Test fun reimportingSameEnvelopeDedupsByContentHash() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("from")) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }
        val export = ExportSourceNodeTool(rig.store).execute(
            ExportSourceNodeTool.Input(projectId = "from", nodeId = "mei"),
            rig.ctx,
        )

        // First import lands fresh.
        val first = ImportSourceNodeTool(rig.store).execute(
            ImportSourceNodeTool.Input(toProjectId = "to", envelope = export.data.envelope),
            rig.ctx,
        )
        assertFalse(first.data.nodes.single().skippedDuplicate)

        // Second import is a dedup no-op — contentHash already present.
        val second = ImportSourceNodeTool(rig.store).execute(
            ImportSourceNodeTool.Input(toProjectId = "to", envelope = export.data.envelope),
            rig.ctx,
        )
        assertTrue(second.data.nodes.single().skippedDuplicate)

        // Target still has exactly one node.
        val target = rig.store.get(ProjectId("to"))!!
        assertEquals(1, target.source.nodes.count { it.kind == "core.consistency.character_ref" })
    }

    @Test fun walksParentsTopologically() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("from")) { source ->
            var working = source
            working = working.addStyleBible(
                SourceNodeId("cinematic"),
                StyleBibleBody(name = "Cinematic", description = "warm tones"),
            )
            working = working.addNode(
                SourceNode.create(
                    id = SourceNodeId("mei"),
                    kind = "character_ref",
                    body = working.byId[SourceNodeId("cinematic")]!!.body, // irrelevant — just want parents
                    parents = listOf(SourceRef(SourceNodeId("cinematic"))),
                ),
            )
            working
        }

        val export = ExportSourceNodeTool(rig.store).execute(
            ExportSourceNodeTool.Input(projectId = "from", nodeId = "mei"),
            rig.ctx,
        )
        assertEquals(2, export.data.nodeCount)

        val import = ImportSourceNodeTool(rig.store).execute(
            ImportSourceNodeTool.Input(toProjectId = "to", envelope = export.data.envelope),
            rig.ctx,
        )
        // Parent was imported before the leaf.
        assertEquals(listOf("cinematic", "mei"), import.data.nodes.map { it.importedId })

        val target = rig.store.get(ProjectId("to"))!!
        val leaf = target.source.byId[SourceNodeId("mei")]!!
        assertEquals(listOf(SourceRef(SourceNodeId("cinematic"))), leaf.parents)
    }

    @Test fun renameRootNodeIdWorks() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("from")) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }
        val export = ExportSourceNodeTool(rig.store).execute(
            ExportSourceNodeTool.Input(projectId = "from", nodeId = "mei"),
            rig.ctx,
        )

        // Seed a different-content node at "mei" in the target to force the rename.
        rig.store.mutateSource(ProjectId("to")) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Other", visualDescription = "red"))
        }

        val out = ImportSourceNodeTool(rig.store).execute(
            ImportSourceNodeTool.Input(
                toProjectId = "to",
                envelope = export.data.envelope,
                newNodeId = "mei-imported",
            ),
            rig.ctx,
        )
        assertEquals("mei-imported", out.data.nodes.single().importedId)
        val target = rig.store.get(ProjectId("to"))!!
        assertTrue(SourceNodeId("mei") in target.source.byId)
        assertTrue(SourceNodeId("mei-imported") in target.source.byId)
    }

    @Test fun collisionWithoutRenameFailsLoudly() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("from")) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }
        val export = ExportSourceNodeTool(rig.store).execute(
            ExportSourceNodeTool.Input(projectId = "from", nodeId = "mei"),
            rig.ctx,
        )
        rig.store.mutateSource(ProjectId("to")) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Other", visualDescription = "red"))
        }

        val ex = assertFailsWith<IllegalStateException> {
            ImportSourceNodeTool(rig.store).execute(
                ImportSourceNodeTool.Input(toProjectId = "to", envelope = export.data.envelope),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("mei"), ex.message)
    }

    @Test fun unknownFormatVersionRejected() = runTest {
        val rig = rig()
        val bogus = """{"formatVersion":"talevia-source-export-v999","rootNodeId":"mei","nodes":[{"id":"mei","kind":"character_ref","body":{"name":"Mei","visualDescription":"teal"}}]}"""

        val ex = assertFailsWith<IllegalArgumentException> {
            ImportSourceNodeTool(rig.store).execute(
                ImportSourceNodeTool.Input(toProjectId = "to", envelope = bogus),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("v999"), ex.message)
        assertTrue(ex.message!!.contains(ExportSourceNodeTool.FORMAT_VERSION), ex.message)
    }

    @Test fun malformedEnvelopeFails() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ImportSourceNodeTool(rig.store).execute(
                ImportSourceNodeTool.Input(toProjectId = "to", envelope = "not-json"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not valid JSON"), ex.message)
    }

    @Test fun missingNodeFailsOnExport() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalArgumentException> {
            ExportSourceNodeTool(rig.store).execute(
                ExportSourceNodeTool.Input(projectId = "from", nodeId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun prettyPrintProducesLargerEnvelopeThanCompact() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("from")) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        val compact = ExportSourceNodeTool(rig.store).execute(
            ExportSourceNodeTool.Input(projectId = "from", nodeId = "mei"),
            rig.ctx,
        )
        val pretty = ExportSourceNodeTool(rig.store).execute(
            ExportSourceNodeTool.Input(projectId = "from", nodeId = "mei", prettyPrint = true),
            rig.ctx,
        )
        assertTrue(pretty.data.envelope.length > compact.data.envelope.length)
        // Both must round-trip. Feed pretty-printed envelope into the importer.
        val imported = ImportSourceNodeTool(rig.store).execute(
            ImportSourceNodeTool.Input(toProjectId = "to", envelope = pretty.data.envelope),
            rig.ctx,
        )
        assertEquals("mei", imported.data.nodes.single().importedId)
    }
}
