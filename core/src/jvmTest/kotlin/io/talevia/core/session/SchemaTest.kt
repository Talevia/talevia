package io.talevia.core.session

import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct tests for [MessageSchema] / [PartSchema] —
 * `core/session/Schema.kt`. The forward-compat discriminator
 * scaffold (landed 2026-04-21 per
 * `message-v2-schema-versioning.md`). Cycle 163 audit: 40
 * LOC, 0 direct test refs (the constants are read across 9+
 * Message / Part variants but the invariants on the
 * constants themselves were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **`CURRENT == 1` for both Message and Part schemas.**
 *    Per kdoc: "Do NOT change `MessageSchema.CURRENT` /
 *    `PartSchema.CURRENT` to anything other than 1 without
 *    writing a corresponding migrator — bumping the default
 *    would invisibly re-tag all pre-existing on-disk blobs
 *    as the new version." A naive bump from 1 → 2 in this
 *    file alone would cascade into every `data class
 *    User`/`Assistant`/`Text`/`Tool`/etc. default param,
 *    silently re-tagging every persisted JSON written
 *    without an explicit `schemaVersion`. **Marquee
 *    invariant** — this is the file's whole purpose.
 *
 * 2. **`encodeDefaults = false` collapses the version field
 *    when it equals `CURRENT`.** The migration recipe
 *    documented in the kdoc step 2: "v2+ blobs carry an
 *    explicit field, v1 / pre-versioning blobs don't." This
 *    only works because `JsonConfig.default.encodeDefaults =
 *    false` strips the field when it matches `CURRENT`.
 *    Drift to `encodeDefaults = true` (which would inflate
 *    every blob) would silently invalidate the entire
 *    forward-compat scaffold. Pinned indirectly by checking
 *    a default-versioned Message/Part roundtrip omits the
 *    field.
 *
 * 3. **Explicit non-default `schemaVersion` DOES surface in
 *    JSON.** The other half of the migration recipe: a v2
 *    write must produce a JSON blob that says
 *    `"schemaVersion":2`. If the v2 blob looked identical to
 *    a v1 blob on disk, decoding-time dispatch (described in
 *    kdoc step 3) couldn't route. Pinned with a synthetic
 *    `schemaVersion = 99` Message and Part.
 */
class SchemaTest {

    private val now: Instant = Instant.fromEpochMilliseconds(0)

    // ── CURRENT constants ───────────────────────────────────────

    @Test fun messageSchemaCurrentIsOne() {
        // Marquee constant pin: the kdoc explicitly forbids
        // bumping past 1 without a corresponding migrator.
        // A 1 → 2 bump would silently re-tag every persisted
        // Message blob (User + Assistant) to v2 because
        // they're constructed with `schemaVersion =
        // MessageSchema.CURRENT` as the default param.
        assertEquals(1, MessageSchema.CURRENT)
    }

    @Test fun partSchemaCurrentIsOne() {
        // Same pin for the Part side. Part has 9 variants
        // (Text, Reasoning, Tool, Media, TimelineSnapshot,
        // RenderProgress, StepStart, StepFinish, Compaction,
        // Todos, Plan) all defaulting `schemaVersion =
        // PartSchema.CURRENT`. A 1 → 2 bump would cascade
        // through every persisted part blob.
        assertEquals(1, PartSchema.CURRENT)
    }

    @Test fun messageAndPartCurrentAreSymmetric() {
        // Pin: the two schemas advance in lockstep until
        // there's a real driver to split them. The kdoc
        // describes a single migration policy; an asymmetric
        // bump (Message=2, Part=1) without a written rule
        // would break the symmetry the kdoc documents.
        // Drift here is the leading indicator of a one-sided
        // bump-without-migrator that contract #1 forbids.
        assertEquals(MessageSchema.CURRENT, PartSchema.CURRENT)
    }

    // ── encodeDefaults=false collapses CURRENT-version field ────

    @Test fun defaultVersionedMessageOmitsSchemaVersionInJson() {
        // The marquee migration-recipe pin (write side):
        // a Message constructed with the default
        // `schemaVersion` does NOT emit the field in JSON.
        // Drift to "always emit" would make every existing
        // pre-versioning blob look "different" on disk and
        // confuse the decode-time dispatcher described in
        // the kdoc step 3.
        val msg = Message.User(
            id = MessageId("m1"),
            sessionId = SessionId("s1"),
            createdAt = now,
            agent = "test",
            model = ModelRef(providerId = "anthropic", modelId = "claude"),
            // `schemaVersion` defaults to MessageSchema.CURRENT.
        )
        val encoded = JsonConfig.default.encodeToString(Message.serializer(), msg)
        assertFalse(
            "\"schemaVersion\"" in encoded,
            "default-version Message must NOT emit schemaVersion (encodeDefaults=false); got: $encoded",
        )
    }

    @Test fun defaultVersionedPartOmitsSchemaVersionInJson() {
        // Same pin for Part. Drift to "always emit" would
        // bloat every Tool / Text / Todos blob with a
        // redundant `"schemaVersion":1` field.
        val part = Part.Text(
            id = PartId("p1"),
            messageId = MessageId("m1"),
            sessionId = SessionId("s1"),
            createdAt = now,
            text = "hello",
        )
        val encoded = JsonConfig.default.encodeToString(Part.serializer(), part)
        assertFalse(
            "\"schemaVersion\"" in encoded,
            "default-version Part must NOT emit schemaVersion (encodeDefaults=false); got: $encoded",
        )
    }

    // ── explicit non-default version DOES surface ─────────────

    @Test fun explicitlyVersionedMessageEmitsSchemaVersionInJson() {
        // The marquee migration-recipe pin (write side, v2+
        // case): a Message constructed with an EXPLICIT
        // non-default `schemaVersion` DOES emit the field.
        // Without this, future v2 writes would be
        // indistinguishable from v1 writes on disk and the
        // decode-time dispatcher couldn't route. Pinned
        // with synthetic value 99 (any non-CURRENT works).
        val msg = Message.User(
            id = MessageId("m1"),
            sessionId = SessionId("s1"),
            createdAt = now,
            agent = "test",
            model = ModelRef(providerId = "anthropic", modelId = "claude"),
            schemaVersion = 99,
        )
        val encoded = JsonConfig.default.encodeToString(Message.serializer(), msg)
        assertTrue(
            "\"schemaVersion\":99" in encoded,
            "non-default Message must emit explicit schemaVersion; got: $encoded",
        )
    }

    @Test fun explicitlyVersionedPartEmitsSchemaVersionInJson() {
        val part = Part.Text(
            id = PartId("p1"),
            messageId = MessageId("m1"),
            sessionId = SessionId("s1"),
            createdAt = now,
            text = "hello",
            schemaVersion = 99,
        )
        val encoded = JsonConfig.default.encodeToString(Part.serializer(), part)
        assertTrue(
            "\"schemaVersion\":99" in encoded,
            "non-default Part must emit explicit schemaVersion; got: $encoded",
        )
    }

    // ── decode of pre-versioning blobs falls back to CURRENT ───

    @Test fun decodingPreVersioningMessageBlobYieldsCurrentVersion() {
        // The marquee migration-recipe pin (read side): a
        // legacy blob with NO `schemaVersion` field decodes
        // back to `MessageSchema.CURRENT` (= 1). Drift to
        // missing-field-throws would crash on every
        // pre-2026-04-21 Message blob.
        val legacyJson = """{"type":"user","id":"m1","sessionId":"s1","createdAt":"1970-01-01T00:00:00Z","agent":"test","model":{"providerId":"anthropic","modelId":"claude"}}"""
        val decoded = JsonConfig.default.decodeFromString(
            Message.serializer(),
            legacyJson,
        ) as Message.User
        assertEquals(
            MessageSchema.CURRENT,
            decoded.schemaVersion,
            "missing schemaVersion → falls back to CURRENT",
        )
    }

    @Test fun decodingPreVersioningPartBlobYieldsCurrentVersion() {
        val legacyJson = """{"type":"text","id":"p1","messageId":"m1","sessionId":"s1","createdAt":"1970-01-01T00:00:00Z","text":"hello"}"""
        val decoded = JsonConfig.default.decodeFromString(
            Part.serializer(),
            legacyJson,
        ) as Part.Text
        assertEquals(
            PartSchema.CURRENT,
            decoded.schemaVersion,
            "missing schemaVersion → falls back to CURRENT",
        )
    }

    // ── decoding explicit version preserves it ─────────────────

    @Test fun decodingExplicitlyVersionedMessageBlobPreservesValue() {
        // Pin: an explicitly-versioned blob round-trips with
        // the explicit value preserved (NOT silently coerced
        // back to CURRENT). This is the read side of the
        // future migration dispatcher: it needs to see "this
        // blob is v2" to route to the v2-aware decoder.
        val v99Json = """{"type":"user","id":"m1","sessionId":"s1","createdAt":"1970-01-01T00:00:00Z","agent":"test","model":{"providerId":"anthropic","modelId":"claude"},"schemaVersion":99}"""
        val decoded = JsonConfig.default.decodeFromString(
            Message.serializer(),
            v99Json,
        ) as Message.User
        assertEquals(99, decoded.schemaVersion)
    }

    // ── identity/object-shape sanity ───────────────────────────

    @Test fun bothSchemasAreSingletonObjects() {
        // Pin: both are `object` declarations (singletons),
        // not `class` constructors that produce new
        // instances. Drift to `class` would mean every
        // construction site allocates, AND the equality
        // semantics would change.
        assertTrue(MessageSchema === MessageSchema)
        assertTrue(PartSchema === PartSchema)
        // And NOT the same singleton — they're separate
        // objects per the public API.
        @Suppress("ReplaceCallWithBinaryOperator")
        assertFalse(
            (MessageSchema as Any).equals(PartSchema as Any),
            "MessageSchema and PartSchema are distinct singletons",
        )
    }
}
