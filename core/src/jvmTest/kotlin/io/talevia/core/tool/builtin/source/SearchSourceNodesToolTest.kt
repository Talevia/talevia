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
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearchSourceNodesToolTest {

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

    private suspend fun seedCharacters(rig: Rig) {
        rig.store.mutateSource(ProjectId("p")) { source ->
            source
                .addCharacterRef(
                    SourceNodeId("mei"),
                    CharacterRefBody(name = "Mei", visualDescription = "teal hair, neon jacket"),
                )
                .addCharacterRef(
                    SourceNodeId("li"),
                    CharacterRefBody(name = "Li", visualDescription = "red hair, black leather"),
                )
                .addStyleBible(
                    SourceNodeId("cinematic"),
                    StyleBibleBody(name = "Cinematic", description = "neon rain, moody pace"),
                )
        }
    }

    @Test fun findsCaseInsensitiveMatchesAcrossKinds() = runTest {
        val rig = rig()
        seedCharacters(rig)
        val out = SearchSourceNodesTool(rig.store).execute(
            SearchSourceNodesTool.Input(projectId = "p", query = "NEON"),
            rig.ctx,
        ).data

        assertEquals(2, out.returnedMatches)
        assertEquals(setOf("mei", "cinematic"), out.matches.map { it.id }.toSet())
        out.matches.forEach { assertTrue(it.matchOffset >= 0) }
    }

    @Test fun caseSensitiveMissesLowercaseHit() = runTest {
        val rig = rig()
        seedCharacters(rig)
        val caseHit = SearchSourceNodesTool(rig.store).execute(
            SearchSourceNodesTool.Input(projectId = "p", query = "Mei", caseSensitive = true),
            rig.ctx,
        ).data
        assertEquals(1, caseHit.returnedMatches)

        val caseMiss = SearchSourceNodesTool(rig.store).execute(
            SearchSourceNodesTool.Input(projectId = "p", query = "mei", caseSensitive = true),
            rig.ctx,
        ).data
        // "mei" with case-sensitive matching finds the node id substring in neither
        // body serialization (bodies have "Mei" only). So 0 matches.
        assertEquals(0, caseMiss.returnedMatches)
    }

    @Test fun kindFilterScopesSearch() = runTest {
        val rig = rig()
        seedCharacters(rig)
        val out = SearchSourceNodesTool(rig.store).execute(
            SearchSourceNodesTool.Input(
                projectId = "p",
                query = "neon",
                kind = "core.consistency.style_bible",
            ),
            rig.ctx,
        ).data

        assertEquals(1, out.returnedMatches)
        assertEquals("cinematic", out.matches.single().id)
    }

    @Test fun limitCapsReturnedMatches() = runTest {
        val rig = rig()
        seedCharacters(rig)
        val out = SearchSourceNodesTool(rig.store).execute(
            SearchSourceNodesTool.Input(projectId = "p", query = "neon", limit = 1),
            rig.ctx,
        ).data

        assertEquals(1, out.returnedMatches)
    }

    @Test fun emptyResultSetStillReportsTotalNodes() = runTest {
        val rig = rig()
        seedCharacters(rig)
        val out = SearchSourceNodesTool(rig.store).execute(
            SearchSourceNodesTool.Input(projectId = "p", query = "nonexistent-zzzzz"),
            rig.ctx,
        ).data
        assertEquals(0, out.returnedMatches)
        assertEquals(3, out.totalNodes)
    }

    @Test fun blankQueryRejected() = runTest {
        val rig = rig()
        seedCharacters(rig)
        assertFailsWith<IllegalArgumentException> {
            SearchSourceNodesTool(rig.store).execute(
                SearchSourceNodesTool.Input(projectId = "p", query = "   "),
                rig.ctx,
            )
        }
    }

    @Test fun snippetBracketsMatch() = runTest {
        val rig = rig()
        seedCharacters(rig)
        val out = SearchSourceNodesTool(rig.store).execute(
            SearchSourceNodesTool.Input(projectId = "p", query = "neon"),
            rig.ctx,
        ).data

        val match = out.matches.first { it.id == "cinematic" }
        val normalized = match.snippet.lowercase()
        assertTrue(normalized.contains("neon"), "snippet must contain the match: '${match.snippet}'")
    }

    @Test fun missingProjectFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SearchSourceNodesTool(rig.store).execute(
                SearchSourceNodesTool.Input(projectId = "ghost", query = "x"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }
}
