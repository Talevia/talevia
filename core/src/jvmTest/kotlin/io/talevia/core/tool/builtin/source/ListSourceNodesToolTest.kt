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
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.consistency.BrandPaletteBody
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addBrandPalette
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ListSourceNodesToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val ctx: ToolContext,
    )

    private suspend fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        store.upsert("demo", Project(id = ProjectId("p"), timeline = Timeline()))
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

    @Test fun emptySourceReturnsEmpty() = runTest {
        val rig = rig()
        val out = ListSourceNodesTool(rig.store).execute(
            ListSourceNodesTool.Input(projectId = "p"),
            rig.ctx,
        ).data
        assertEquals(0, out.totalCount)
        assertEquals(0, out.returnedCount)
        assertTrue(out.nodes.isEmpty())
    }

    @Test fun defaultLimitKeepsAllWhenUnderDefault() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            source
                .addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
                .addCharacterRef(SourceNodeId("li"), CharacterRefBody(name = "Li", visualDescription = "red hair"))
                .addStyleBible(SourceNodeId("cine"), StyleBibleBody(name = "Cine", description = "neon rain"))
        }
        val out = ListSourceNodesTool(rig.store).execute(
            ListSourceNodesTool.Input(projectId = "p"),
            rig.ctx,
        ).data
        assertEquals(3, out.totalCount)
        assertEquals(3, out.returnedCount)
    }

    @Test fun limitCapsResponse() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            var s = source
            (1..5).forEach { i ->
                s = s.addCharacterRef(
                    SourceNodeId("c-$i"),
                    CharacterRefBody(name = "C$i", visualDescription = "desc $i"),
                )
            }
            s
        }
        val out = ListSourceNodesTool(rig.store).execute(
            ListSourceNodesTool.Input(projectId = "p", limit = 2),
            rig.ctx,
        ).data
        assertEquals(5, out.totalCount)
        assertEquals(2, out.returnedCount)
    }

    @Test fun limitClampedToMax() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            source
                .addCharacterRef(SourceNodeId("a"), CharacterRefBody(name = "A", visualDescription = "x"))
                .addCharacterRef(SourceNodeId("b"), CharacterRefBody(name = "B", visualDescription = "y"))
        }
        val out = ListSourceNodesTool(rig.store).execute(
            ListSourceNodesTool.Input(projectId = "p", limit = 999_999),
            rig.ctx,
        ).data
        // No exception — clamped silently. Both nodes returned.
        assertEquals(2, out.totalCount)
        assertEquals(2, out.returnedCount)
    }

    @Test fun defaultSortIsByIdAscending() = runTest {
        val rig = rig()
        // Insert in non-alphabetical order to prove sorting is by id, not insertion order.
        rig.store.mutateSource(ProjectId("p")) { source ->
            source
                .addCharacterRef(SourceNodeId("z-1"), CharacterRefBody(name = "Z1", visualDescription = "zz"))
                .addCharacterRef(SourceNodeId("a-2"), CharacterRefBody(name = "A2", visualDescription = "aa"))
                .addCharacterRef(SourceNodeId("m-3"), CharacterRefBody(name = "M3", visualDescription = "mm"))
        }
        val out = ListSourceNodesTool(rig.store).execute(
            ListSourceNodesTool.Input(projectId = "p"),
            rig.ctx,
        ).data
        assertEquals(listOf("a-2", "m-3", "z-1"), out.nodes.map { it.id })
    }

    @Test fun sortByRevisionDesc() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            // addNode bumps the supplied revision by +1 on write, so seeding with
            // (1, 5, 3) stores (2, 6, 4) — the ordering relation is what this test asserts.
            source
                .addNode(SourceNode.create(SourceNodeId("r-low"), kind = "custom.marker", revision = 1))
                .addNode(SourceNode.create(SourceNodeId("r-high"), kind = "custom.marker", revision = 5))
                .addNode(SourceNode.create(SourceNodeId("r-mid"), kind = "custom.marker", revision = 3))
        }
        val out = ListSourceNodesTool(rig.store).execute(
            ListSourceNodesTool.Input(projectId = "p", sortBy = "revision-desc"),
            rig.ctx,
        ).data
        assertEquals(listOf("r-high", "r-mid", "r-low"), out.nodes.map { it.id })
        // Stored revisions are the supplied ones +1 (addNode's bumpedForWrite), but the
        // high-to-low ordering is what matters for `revision-desc`.
        val revisions = out.nodes.map { it.revision }
        assertTrue(revisions[0] > revisions[1], "expected $revisions sorted high-to-low")
        assertTrue(revisions[1] > revisions[2], "expected $revisions sorted high-to-low")
    }

    @Test fun sortByKindGroupsDeterministically() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            source
                .addStyleBible(SourceNodeId("s-style"), StyleBibleBody(name = "S", description = "d"))
                .addCharacterRef(
                    SourceNodeId("z-char"),
                    CharacterRefBody(name = "Z", visualDescription = "z"),
                )
                .addBrandPalette(
                    SourceNodeId("b-brand"),
                    BrandPaletteBody(name = "B", hexColors = listOf("#000000")),
                )
                .addCharacterRef(
                    SourceNodeId("a-char"),
                    CharacterRefBody(name = "A", visualDescription = "a"),
                )
        }
        val out = ListSourceNodesTool(rig.store).execute(
            ListSourceNodesTool.Input(projectId = "p", sortBy = "kind"),
            rig.ctx,
        ).data
        // Alphabetical by kind: brand_palette < character_ref < style_bible.
        // Within character_ref, ties broken by id ASC: a-char before z-char.
        assertEquals(
            listOf("b-brand", "a-char", "z-char", "s-style"),
            out.nodes.map { it.id },
        )
        assertEquals(
            listOf(
                "core.consistency.brand_palette",
                "core.consistency.character_ref",
                "core.consistency.character_ref",
                "core.consistency.style_bible",
            ),
            out.nodes.map { it.kind },
        )
    }

    @Test fun sortByInvalidFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalArgumentException> {
            ListSourceNodesTool(rig.store).execute(
                ListSourceNodesTool.Input(projectId = "p", sortBy = "ghost"),
                rig.ctx,
            )
        }
        val msg = ex.message!!
        assertTrue(msg.contains("ghost"), msg)
        assertTrue(msg.contains("id"), msg)
        assertTrue(msg.contains("kind"), msg)
        assertTrue(msg.contains("revision-desc"), msg)
    }

    @Test fun kindFilterComposesWithLimit() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            var s = source
            (1..3).forEach { i ->
                s = s.addCharacterRef(
                    SourceNodeId("char-$i"),
                    CharacterRefBody(name = "C$i", visualDescription = "v$i"),
                )
            }
            (1..3).forEach { i ->
                s = s.addStyleBible(
                    SourceNodeId("style-$i"),
                    StyleBibleBody(name = "S$i", description = "d$i"),
                )
            }
            s
        }
        val out = ListSourceNodesTool(rig.store).execute(
            ListSourceNodesTool.Input(
                projectId = "p",
                kind = "core.consistency.character_ref",
                limit = 2,
            ),
            rig.ctx,
        ).data
        // totalCount preserves pre-filter semantics — 6 nodes total in source.
        assertEquals(6, out.totalCount)
        assertEquals(2, out.returnedCount)
        assertTrue(out.nodes.all { it.kind == "core.consistency.character_ref" })
        // Default id-ascending sort means the first two character_refs are char-1, char-2.
        assertEquals(listOf("char-1", "char-2"), out.nodes.map { it.id })
    }

    @Test fun sortByBlankFallsBackToDefault() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            source
                .addCharacterRef(SourceNodeId("z"), CharacterRefBody(name = "Z", visualDescription = "z"))
                .addCharacterRef(SourceNodeId("a"), CharacterRefBody(name = "A", visualDescription = "a"))
        }
        val out = ListSourceNodesTool(rig.store).execute(
            // Whitespace-only sortBy should normalise to the default (id ASC), not reject.
            ListSourceNodesTool.Input(projectId = "p", sortBy = "  "),
            rig.ctx,
        ).data
        assertEquals(listOf("a", "z"), out.nodes.map { it.id })
    }

    @Test fun includeBodyReturnsBody() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            source.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal"),
            )
        }
        val withBody = ListSourceNodesTool(rig.store).execute(
            ListSourceNodesTool.Input(projectId = "p", includeBody = true),
            rig.ctx,
        ).data
        val withoutBody = ListSourceNodesTool(rig.store).execute(
            ListSourceNodesTool.Input(projectId = "p", includeBody = false),
            rig.ctx,
        ).data
        assertTrue(withBody.nodes.single().body is JsonObject)
        assertEquals(null, withoutBody.nodes.single().body)
    }
}
