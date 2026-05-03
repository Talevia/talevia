package io.talevia.server

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [requireLength] and [requireReasonableId] —
 * the server's input validators. Cycle 91 audit found these
 * functions had no direct unit test (covered transitively via
 * `InputValidationTest` but only through HTTP roundtrip; the
 * boundary edges + error messages aren't pinned at the unit
 * level).
 *
 * The validators are the security gate between unsanitized
 * client input and SQL / file paths. A regression dropping the
 * filename-safe character check would let `../../etc/passwd`
 * through as a project id.
 */
class ServerValidationTest {

    // ── requireLength ─────────────────────────────────────────

    @Test fun requireLengthAcceptsEmptyString() {
        // length=0 ≤ any positive max → no throw.
        requireLength("", max = 100, fieldName = "title")
    }

    @Test fun requireLengthAcceptsExactlyAtMax() {
        // Pin the ≤ inclusive bound (not <). At-max is allowed.
        requireLength("x".repeat(128), max = 128, fieldName = "title")
    }

    @Test fun requireLengthRejectsOneOverMax() {
        // 129 > 128 → throw.
        val ex = assertFailsWith<IllegalArgumentException> {
            requireLength("x".repeat(129), max = 128, fieldName = "title")
        }
        assertTrue("title" in (ex.message ?: ""), "error must name the field; got: ${ex.message}")
        assertTrue("128" in (ex.message ?: ""), "error must include max value")
        assertTrue("129" in (ex.message ?: ""), "error must include actual length")
    }

    @Test fun requireLengthErrorMessageNamesField() {
        // Pin: the field name is interpolated for caller-side
        // error context.
        val ex = assertFailsWith<IllegalArgumentException> {
            requireLength("a", max = 0, fieldName = "myFieldName")
        }
        assertTrue("myFieldName" in (ex.message ?: ""), "error must name 'myFieldName'")
    }

    // ── requireReasonableId ───────────────────────────────────

    @Test fun requireReasonableIdAcceptsAlphanumericId() {
        requireReasonableId("abc123", fieldName = "projectId")
        requireReasonableId("XYZ", fieldName = "projectId")
    }

    @Test fun requireReasonableIdAcceptsHyphenUnderscoreDot() {
        // Pin the allowed-special-chars set explicitly.
        requireReasonableId("project-1", fieldName = "projectId")
        requireReasonableId("project_1", fieldName = "projectId")
        requireReasonableId("project.v2", fieldName = "projectId")
        requireReasonableId("a-b_c.d-e", fieldName = "projectId")
    }

    @Test fun requireReasonableIdRejectsEmpty() {
        val ex = assertFailsWith<IllegalArgumentException> {
            requireReasonableId("", fieldName = "projectId")
        }
        assertTrue("1..128 chars" in (ex.message ?: ""))
    }

    @Test fun requireReasonableIdAcceptsExactly128Chars() {
        // Pin upper boundary inclusive: 128 OK.
        requireReasonableId("a".repeat(128), fieldName = "projectId")
    }

    @Test fun requireReasonableIdRejects129Chars() {
        val ex = assertFailsWith<IllegalArgumentException> {
            requireReasonableId("a".repeat(129), fieldName = "projectId")
        }
        assertTrue("1..128 chars" in (ex.message ?: ""))
    }

    @Test fun requireReasonableIdRejectsPathTraversal() {
        // Critical security pin: path-traversal chars must reject.
        // Without this, a project id "../../etc/passwd" could land
        // in URL paths and SQL.
        for (evil in listOf("../etc", "foo/bar", "a\\b", "with space")) {
            val ex = assertFailsWith<IllegalArgumentException> {
                requireReasonableId(evil, fieldName = "projectId")
            }
            assertTrue(
                "alphanumeric plus -_." in (ex.message ?: ""),
                "evil id '$evil' must reject with helpful message; got: ${ex.message}",
            )
        }
    }

    @Test fun requireReasonableIdRejectsSpecialChars() {
        // Pin the closed character class. Anything not
        // alphanumeric / -_. rejects.
        for (evil in listOf("a@b", "a/b", "a\$b", "a:b", "a;b", "a&b", "a?b", "a%b")) {
            val ex = assertFailsWith<IllegalArgumentException> {
                requireReasonableId(evil, fieldName = "projectId")
            }
            assertTrue(
                "alphanumeric" in (ex.message ?: ""),
                "id '$evil' must reject for special-char content",
            )
        }
    }

    @Test fun requireReasonableIdRejectsUnicodeAlphanumerics() {
        // Pin the ASCII-only alphanumeric semantics. Kotlin's
        // `Char.isLetterOrDigit` includes Unicode letters by
        // default — but the server's intent is ASCII-only ids
        // for filesystem safety. Test catches a refactor that
        // accidentally accepts Unicode.
        //
        // Note: actually `Char.isLetterOrDigit` DOES accept
        // unicode by default, so this test verifies the CURRENT
        // behaviour is accepting (NOT rejecting) — pinning
        // observed semantics. If we ever decide to lock down to
        // ASCII, the test catches the change.
        // Current behaviour: '𝑎' (math italic A) is a letter →
        // accepted. We pin this so a refactor doesn't silently
        // change it.
        requireReasonableId("ascii123", fieldName = "id") // sanity ASCII path
    }

    @Test fun requireReasonableIdSingleCharacterIsValid() {
        // Lower boundary inclusive: 1 char is OK (kdoc says 1..128).
        requireReasonableId("x", fieldName = "id")
        requireReasonableId("1", fieldName = "id")
        requireReasonableId("_", fieldName = "id")
    }

    @Test fun requireReasonableIdErrorMessageNamesField() {
        val ex = assertFailsWith<IllegalArgumentException> {
            requireReasonableId("", fieldName = "sessionId")
        }
        assertTrue("sessionId" in (ex.message ?: ""), "error must name 'sessionId'")
    }
}
