package io.talevia.core.domain.lockfile

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LockfileTest {

    private val json = JsonConfig.default

    private fun entry(hash: String, assetId: String): LockfileEntry = LockfileEntry(
        inputHash = hash,
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "m",
            modelVersion = "v1",
            seed = 42L,
            parameters = buildJsonObject { put("prompt", "p") },
            createdAtEpochMs = 1_700_000_000_000L,
        ),
        sourceBinding = setOf(SourceNodeId("mei")),
    )

    @Test fun appendReturnsNewLockfileWithEntryAppended() {
        val before = Lockfile.EMPTY
        val after = before.append(entry("h1", "asset-1"))
        assertEquals(0, before.entries.size, "Lockfile must be immutable")
        assertEquals(1, after.entries.size)
    }

    @Test fun findByInputHashReturnsLastMatching() {
        val l = Lockfile.EMPTY
            .append(entry("h1", "asset-1"))
            .append(entry("h1", "asset-2")) // same hash — take the latest
            .append(entry("h2", "asset-3"))
        val hit = assertNotNull(l.findByInputHash("h1"))
        assertEquals(AssetId("asset-2"), hit.assetId)
        assertEquals(AssetId("asset-3"), l.findByInputHash("h2")?.assetId)
        assertNull(l.findByInputHash("nope"))
    }

    @Test fun projectCarriesLockfileThroughSerialization() {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(),
            lockfile = Lockfile.EMPTY.append(entry("h1", "asset-1")),
        )
        val decoded = json.decodeFromString(Project.serializer(), json.encodeToString(Project.serializer(), project))
        assertEquals(1, decoded.lockfile.entries.size)
        assertEquals("h1", decoded.lockfile.entries.first().inputHash)
    }

    @Test fun preLockfileProjectJsonDecodesToEmptyLockfile() {
        val legacy = """
            { "id": "p-old", "timeline": { "tracks": [] }, "assets": [] }
        """.trimIndent()
        val project = json.decodeFromString(Project.serializer(), legacy)
        assertEquals(Lockfile.EMPTY, project.lockfile)
    }
}
