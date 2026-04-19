package io.talevia.core.domain.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.genre.vlog.VlogNodeKinds
import io.talevia.core.domain.source.genre.vlog.VlogRawFootageBody
import io.talevia.core.domain.source.genre.vlog.addVlogRawFootage
import io.talevia.core.domain.source.genre.vlog.asVlogRawFootage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Store-level contract: mutateSource goes through the existing ProjectStore mutex
 * (no second lock) and each mutation bumps both Source and node revisions.
 *
 * Also proves the extension-style genre contract: Core does not need to know a body
 * type — an in-test genre ("fake.thing") round-trips through Source untouched.
 */
class SourceMutationTest {

    private fun newStore(): Pair<SqlDelightProjectStore, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val store = SqlDelightProjectStore(db)
        val id = ProjectId("p-src")
        runBlocking { store.upsert("demo", Project(id = id, timeline = Timeline())) }
        return store to id
    }

    @Test fun mutateSourceAddsNodeAndBumpsRevisions() = runBlocking {
        val (store, id) = newStore()
        val before = store.get(id)!!
        assertEquals(0L, before.source.revision)

        store.mutateSource(id) {
            it.addVlogRawFootage(
                SourceNodeId("n-1"),
                VlogRawFootageBody(assetIds = listOf(AssetId("a-1"))),
            )
        }

        val after = store.get(id)!!
        assertEquals(1L, after.source.revision, "source revision must bump on mutation")
        val node = after.source.byId[SourceNodeId("n-1")]
        assertNotNull(node)
        assertEquals(VlogNodeKinds.RAW_FOOTAGE, node.kind)
        assertEquals(1L, node.revision, "new node's revision must bump on write")
        assertEquals(
            16,
            node.contentHash.length,
            "contentHash must be a stable FNV-1a 64-bit hex string",
        )
        assertEquals(listOf(AssetId("a-1")), node.asVlogRawFootage()?.assetIds)
    }

    @Test fun replaceNodeBumpsBothRevisions() = runBlocking {
        val (store, id) = newStore()
        store.mutateSource(id) {
            it.addVlogRawFootage(SourceNodeId("n-1"), VlogRawFootageBody(assetIds = listOf(AssetId("a-1"))))
        }

        store.mutateSource(id) { src ->
            src.replaceNode(SourceNodeId("n-1")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        VlogRawFootageBody.serializer(),
                        VlogRawFootageBody(assetIds = listOf(AssetId("a-1"), AssetId("a-2"))),
                    ),
                )
            }
        }

        val after = store.get(id)!!
        assertEquals(2L, after.source.revision)
        val node = after.source.byId.getValue(SourceNodeId("n-1"))
        assertEquals(2L, node.revision)
        assertEquals(16, node.contentHash.length)
        assertEquals(
            listOf(AssetId("a-1"), AssetId("a-2")),
            node.asVlogRawFootage()?.assetIds,
        )
    }

    @Test fun removeNodeBumpsSourceRevisionAndDropsFromIndex() = runBlocking {
        val (store, id) = newStore()
        store.mutateSource(id) {
            it.addVlogRawFootage(SourceNodeId("n-1"), VlogRawFootageBody(assetIds = emptyList()))
        }
        store.mutateSource(id) { it.removeNode(SourceNodeId("n-1")) }

        val after = store.get(id)!!
        assertEquals(2L, after.source.revision)
        assertEquals(emptyList(), after.source.nodes)
    }

    // ------------------------------------------------------------------
    // Extension-contract: Core stays genre-agnostic.
    // ------------------------------------------------------------------

    @Serializable
    private data class FakeGenreBody(val x: Int)

    @Test fun coreCanCarryAGenreBodyItHasNeverHeardOf() {
        val json = JsonConfig.default
        val body = FakeGenreBody(x = 42)

        val src = Source.EMPTY.addNode(
            SourceNode(
                id = SourceNodeId("fake-1"),
                kind = "fake.thing",
                body = json.encodeToJsonElement(FakeGenreBody.serializer(), body),
            ),
        )

        val decoded = json.decodeFromString(Source.serializer(), json.encodeToString(Source.serializer(), src))
        val node = decoded.byId.getValue(SourceNodeId("fake-1"))
        assertEquals("fake.thing", node.kind)
        // Core sees body as JsonElement; only the test (the "genre owner") knows the type.
        val raw: JsonElement = node.body
        assertEquals(body, json.decodeFromJsonElement(FakeGenreBody.serializer(), raw))
    }
}
