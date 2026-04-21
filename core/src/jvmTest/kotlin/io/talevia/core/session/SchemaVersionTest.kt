package io.talevia.core.session

import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Forward-compat scaffold for the [MessageSchema] / [PartSchema] discriminators.
 * The invariants this file pins:
 *
 *  1. Current version is **1** for both Message and Part. Bumping it without
 *     writing a live migrator is a trap (existing on-disk blobs grow a
 *     schemaVersion field retroactively).
 *  2. Default-encoded blobs do **not** carry `schemaVersion` in the JSON
 *     because `JsonConfig.default.encodeDefaults = false`. This is what
 *     keeps pre-versioning blobs on disk from looking different.
 *  3. Blobs that lack `schemaVersion` decode to schemaVersion=1 via the
 *     data-class default — this is how legacy blobs survive the field
 *     addition without a migration.
 *  4. Explicit schemaVersion=2 (simulating a future migration) round-trips:
 *     it's encoded (differs from default) and decoded correctly.
 */
class SchemaVersionTest {

    @Test fun currentConstantsPinnedAt1() {
        assertEquals(1, MessageSchema.CURRENT)
        assertEquals(1, PartSchema.CURRENT)
    }

    @Test fun defaultMessageRoundTripOmitsSchemaVersionField() {
        val user = Message.User(
            id = MessageId("m"),
            sessionId = SessionId("s"),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            agent = "default",
            model = ModelRef("fake", "fake-model"),
        )
        val json = JsonConfig.default.encodeToString(Message.serializer(), user)
        assertFalse(
            json.contains("schemaVersion"),
            "default schemaVersion should NOT be encoded (encodeDefaults=false); got: $json",
        )
        val decoded = JsonConfig.default.decodeFromString(Message.serializer(), json) as Message.User
        assertEquals(1, decoded.schemaVersion)
    }

    @Test fun decodeLegacyMessageBlobWithoutSchemaVersionField() {
        // Simulates a blob written by pre-versioning code (no schemaVersion key).
        val legacy = buildJsonObject {
            put("type", "user")
            put("id", "m")
            put("sessionId", "s")
            put("createdAt", "2024-01-01T00:00:00Z")
            put("agent", "default")
            put(
                "model",
                buildJsonObject {
                    put("providerId", "fake")
                    put("modelId", "fake-model")
                },
            )
        }
        val decoded = JsonConfig.default.decodeFromJsonElement(Message.serializer(), legacy) as Message.User
        assertEquals(1, decoded.schemaVersion, "absent field must default to 1 (current schema)")
    }

    @Test fun futureSchemaVersionMessageIsEncodedAndRoundTrips() {
        val futureUser = Message.User(
            id = MessageId("m"),
            sessionId = SessionId("s"),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            agent = "default",
            model = ModelRef("fake", "fake-model"),
            schemaVersion = 2, // simulates future bump
        )
        val json = JsonConfig.default.encodeToString(Message.serializer(), futureUser)
        assertTrue(
            json.contains("\"schemaVersion\":2"),
            "non-default schemaVersion must be encoded; got: $json",
        )
        val decoded = JsonConfig.default.decodeFromString(Message.serializer(), json) as Message.User
        assertEquals(2, decoded.schemaVersion)
    }

    @Test fun defaultPartRoundTripOmitsSchemaVersionField() {
        val part = Part.Text(
            id = PartId("p"),
            messageId = MessageId("m"),
            sessionId = SessionId("s"),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            text = "hi",
        )
        val json = JsonConfig.default.encodeToString(Part.serializer(), part)
        assertFalse(json.contains("schemaVersion"), json)
        val decoded = JsonConfig.default.decodeFromString(Part.serializer(), json) as Part.Text
        assertEquals(1, decoded.schemaVersion)
    }

    @Test fun decodeLegacyPartBlobWithoutSchemaVersionField() {
        val legacy = buildJsonObject {
            put("type", "text")
            put("id", "p")
            put("messageId", "m")
            put("sessionId", "s")
            put("createdAt", "2024-01-01T00:00:00Z")
            put("text", "hi")
        }
        val decoded = JsonConfig.default.decodeFromJsonElement(Part.serializer(), legacy) as Part.Text
        assertEquals(1, decoded.schemaVersion)
    }

    @Test fun futureSchemaVersionPartIsEncodedAndRoundTrips() {
        val part = Part.Tool(
            id = PartId("p"),
            messageId = MessageId("m"),
            sessionId = SessionId("s"),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            callId = CallId("c"),
            toolId = "generate_image",
            state = ToolState.Pending,
            schemaVersion = 2,
        )
        val json = JsonConfig.default.encodeToString(Part.serializer(), part)
        assertTrue(json.contains("\"schemaVersion\":2"), json)
        val decoded = JsonConfig.default.decodeFromString(Part.serializer(), json) as Part.Tool
        assertEquals(2, decoded.schemaVersion)
    }

    @Test fun unknownFutureFieldIgnoredByDecoder() {
        // Sanity — JsonConfig has ignoreUnknownKeys=true, so a future v2 blob
        // with a field we haven't learned about yet decodes cleanly.
        val futureBlob: JsonObject = buildJsonObject {
            put("type", "text")
            put("id", "p")
            put("messageId", "m")
            put("sessionId", "s")
            put("createdAt", "2024-01-01T00:00:00Z")
            put("text", "hi")
            put("schemaVersion", 2)
            put("futureFieldThatDoesntExistYet", JsonPrimitive("whatever"))
        }
        val decoded = JsonConfig.default.decodeFromJsonElement(Part.serializer(), futureBlob) as Part.Text
        assertEquals(2, decoded.schemaVersion)
        assertEquals("hi", decoded.text)
    }
}
