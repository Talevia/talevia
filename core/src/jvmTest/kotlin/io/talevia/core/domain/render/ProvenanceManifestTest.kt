package io.talevia.core.domain.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [ProvenanceManifest.encodeToComment] / [ProvenanceManifest.decodeFromComment]
 * round-trip and the "reject non-Talevia comments" contract. Semantic
 * boundaries (§3a rule 9):
 *  - null / blank → null (no probe result has a comment).
 *  - Missing prefix → null (user's own comment on a file we didn't produce).
 *  - Wrong prefix → null (e.g. future `talevia/v2:` before this parser is taught it).
 *  - Malformed JSON body → null (corrupted / truncated metadata, never throws).
 *  - Happy path → field-for-field round-trip.
 *  - Default schemaVersion round-trips as 1 (forward-compat anchor).
 */
class ProvenanceManifestTest {

    @Test fun happyPathEncodesAndDecodes() {
        val original = ProvenanceManifest(
            projectId = "p-42",
            timelineHash = "abc123",
            lockfileHash = "def456",
        )
        val encoded = original.encodeToComment()
        assertTrue(encoded.startsWith(ProvenanceManifest.MANIFEST_PREFIX))

        val decoded = ProvenanceManifest.decodeFromComment(encoded)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test fun defaultSchemaVersionIsOne() {
        val m = ProvenanceManifest(projectId = "p", timelineHash = "t", lockfileHash = "l")
        assertEquals(1, m.schemaVersion)
    }

    @Test fun nullCommentDecodesNull() {
        assertNull(ProvenanceManifest.decodeFromComment(null))
    }

    @Test fun blankCommentDecodesNull() {
        assertNull(ProvenanceManifest.decodeFromComment(""))
        assertNull(ProvenanceManifest.decodeFromComment("   "))
    }

    @Test fun commentWithoutPrefixDecodesNull() {
        // User-set comment from some other tool — don't claim it as ours.
        assertNull(ProvenanceManifest.decodeFromComment("Rendered by some-other-tool"))
        // Close-but-wrong prefix — also reject.
        assertNull(ProvenanceManifest.decodeFromComment("talevia/v2:{\"projectId\":\"p\"}"))
    }

    @Test fun malformedJsonBodyDecodesNull() {
        // Truncated JSON — the runCatching guard must swallow the parse error.
        val bad = "${ProvenanceManifest.MANIFEST_PREFIX}{\"projectId\":\"p\",\"timelineHash\""
        assertNull(ProvenanceManifest.decodeFromComment(bad))
    }

    @Test fun encodedStringIsSingleLine() {
        // ffmpeg -metadata values must not contain raw newlines — the canonical
        // JsonConfig.default emits single-line JSON so this is guaranteed, but
        // a future pretty-printing regression would break the bake path silently.
        val encoded = ProvenanceManifest(
            projectId = "p-newline",
            timelineHash = "t",
            lockfileHash = "l",
        ).encodeToComment()
        assertTrue('\n' !in encoded, "Encoded manifest must be single-line for ffmpeg metadata")
    }

    @Test fun roundTripPreservesFutureFieldDefault() {
        // If a future cycle adds a field with a default, a manifest encoded
        // today should still decode via the default. The reverse (new
        // manifest → old decoder) is covered by kotlinx.serialization's
        // `ignoreUnknownKeys = true` on JsonConfig.default.
        val withExplicitSchema = ProvenanceManifest(
            projectId = "p",
            timelineHash = "t",
            lockfileHash = "l",
            schemaVersion = 1,
        )
        val decoded = ProvenanceManifest.decodeFromComment(withExplicitSchema.encodeToComment())
        assertEquals(1, decoded?.schemaVersion)
    }
}
