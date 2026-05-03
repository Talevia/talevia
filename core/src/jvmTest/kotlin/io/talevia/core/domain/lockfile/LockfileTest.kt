package io.talevia.core.domain.lockfile

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
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

    @Test fun entryMissingCostFieldsDecodesAsNull() {
        // Blob written before costCents/sessionId existed must still decode; the
        // new fields surface as their null defaults, keeping spend aggregations
        // honest about "we don't know what this entry cost".
        val legacy = """
            {
              "inputHash": "h1",
              "toolId": "generate_image",
              "assetId": "asset-1",
              "provenance": {
                "providerId": "fake",
                "modelId": "m",
                "modelVersion": "v1",
                "seed": 42,
                "parameters": { "prompt": "p" },
                "createdAtEpochMs": 1700000000000
              }
            }
        """.trimIndent()
        val entry = json.decodeFromString(LockfileEntry.serializer(), legacy)
        assertNull(entry.costCents)
        assertNull(entry.sessionId)
    }

    @Test fun entryWithCostFieldsRoundTrips() {
        val original = entry("h1", "asset-1").copy(costCents = 42L, sessionId = "s-xyz")
        val roundTripped = json.decodeFromString(
            LockfileEntry.serializer(),
            json.encodeToString(LockfileEntry.serializer(), original),
        )
        assertEquals(42L, roundTripped.costCents)
        assertEquals("s-xyz", roundTripped.sessionId)
    }

    @Test fun entryMissingResolvedPromptDecodesAsNull() {
        // Entry written before the resolvedPrompt field must still decode; the
        // new field surfaces as null so UI / queries can say "unknown prompt
        // trace for this legacy entry" rather than silently reporting an empty
        // string (which would wrongly suggest "we asked the provider for ''").
        val legacy = """
            {
              "inputHash": "h1",
              "toolId": "generate_image",
              "assetId": "asset-1",
              "provenance": {
                "providerId": "fake",
                "modelId": "m",
                "modelVersion": "v1",
                "seed": 42,
                "parameters": { "prompt": "p" },
                "createdAtEpochMs": 1700000000000
              },
              "costCents": 4,
              "sessionId": "s-pre-cycle-7"
            }
        """.trimIndent()
        val entry = json.decodeFromString(LockfileEntry.serializer(), legacy)
        assertNull(entry.resolvedPrompt)
    }

    @Test fun entryWithResolvedPromptRoundTrips() {
        val folded = "[character_ref mei: Asian, mid-30s]\n\nCyberpunk street at night"
        val original = entry("h1", "asset-1").copy(resolvedPrompt = folded)
        val roundTripped = json.decodeFromString(
            LockfileEntry.serializer(),
            json.encodeToString(LockfileEntry.serializer(), original),
        )
        assertEquals(folded, roundTripped.resolvedPrompt)
    }

    @Test fun entryMissingOriginatingMessageIdDecodesAsNull() {
        // Legacy entries written before originatingMessageId existed must
        // still decode; audit-path callers treat null as "unknown — this
        // entry pre-dates provenance stamping" rather than crashing.
        val legacy = """
            {
              "inputHash": "h1",
              "toolId": "generate_image",
              "assetId": "asset-1",
              "provenance": {
                "providerId": "fake",
                "modelId": "m",
                "modelVersion": "v1",
                "seed": 42,
                "parameters": { "prompt": "p" },
                "createdAtEpochMs": 1700000000000
              }
            }
        """.trimIndent()
        val entry = json.decodeFromString(LockfileEntry.serializer(), legacy)
        assertNull(entry.originatingMessageId)
    }

    @Test fun entryWithOriginatingMessageIdRoundTrips() {
        val msgId = MessageId("msg-abc")
        val original = entry("h1", "asset-1").copy(originatingMessageId = msgId)
        val roundTripped = json.decodeFromString(
            LockfileEntry.serializer(),
            json.encodeToString(LockfileEntry.serializer(), original),
        )
        assertEquals(msgId, roundTripped.originatingMessageId)
    }

    @Test fun streamYieldsEntriesInInsertionOrderForLazyTraversal() {
        // `debt-lockfile-lazy-interface-O1-open` phase 2b-1a (cycle 48)
        // contract: stream() iterates entries in append order. Phase
        // 2b-1b will migrate 30+ callers from `entries.firstOrNull` /
        // `entries.size` patterns to this method; phase 2b-1c swaps the
        // eager backing store for a lazy JSONL reader. The insertion-
        // order contract is what those callers depend on (lockfile
        // semantics are append-only with last-wins on duplicate hash).
        val a = entry("h1", "asset-1")
        val b = entry("h2", "asset-2")
        val c = entry("h3", "asset-3")
        val lockfile = Lockfile.EMPTY.append(a).append(b).append(c)

        val streamed = lockfile.stream().toList()
        assertEquals(listOf(a, b, c), streamed, "stream() must preserve append order")
        assertEquals(lockfile.entries, streamed, "stream() observes the same logical sequence as entries")
    }

    @Test fun streamOnEmptyLockfileEmitsZeroElements() {
        // Edge that phase 2b-1c's lazy impl will need to handle (the
        // JSONL reader on an empty file yields an empty stream); pin
        // the contract here so the swap remains transparent.
        val empty = Lockfile.EMPTY.stream().toList()
        assertEquals(0, empty.size)
    }
}
