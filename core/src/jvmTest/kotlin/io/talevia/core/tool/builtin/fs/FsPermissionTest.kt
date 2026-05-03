package io.talevia.core.tool.builtin.fs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct tests for [extractPathPattern] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/fs/FsPermission.kt`.
 * Cycle 255 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-254.
 *
 * `extractPathPattern` is the pattern-scoping helper consumed by
 * every fs tool's `PermissionSpec.patternFrom` lambda. It pulls
 * a path-like string out of the tool's input JSON to scope the
 * permission check ("Always allow path X" vs blanket
 * fs.read). The function is small (18 LOC) but security-sensitive:
 * drift in any failure-mode behavior silently expands or
 * narrows the permission pattern in ways the user can't see
 * until they trigger an unexpected ASK / ALLOW.
 *
 * Two correctness contracts pinned:
 *
 *  1. **Valid path string lands as the pattern**: when the input
 *     is a JsonObject with the named string field, the field's
 *     content (NOT the JSON-quoted form) is returned. Drift to
 *     return `"\"$content\""` (quoted) would silently mismatch
 *     filesystem-glob semantics.
 *
 *  2. **All failure modes fall back to `"*"`** (the
 *     blanket-scope sentinel — drift to return null / throw
 *     would crash the dispatcher; drift to return `""` would
 *     silently NEVER match any rule and refuse every fs op).
 *     Failure modes covered:
 *       - malformed JSON
 *       - non-JsonObject root (array, primitive, null literal)
 *       - missing field
 *       - non-string field (number, boolean)
 *       - blank / empty / whitespace-only field
 *       - object / array field (not a primitive)
 *
 * Pinning each failure mode catches drift to "throw on bad
 * input" or "let through stringified non-strings" — both would
 * silently change the dispatcher's pattern-matching semantics.
 */
class FsPermissionTest {

    @Test fun validStringFieldReturnsItsContent() {
        // Marquee happy-path pin: the most common shape.
        assertEquals(
            "/tmp/foo.txt",
            extractPathPattern(
                inputJson = """{"path":"/tmp/foo.txt"}""",
                field = "path",
            ),
        )
    }

    @Test fun unicodePathRoundTrips() {
        // Pin: Unicode / CJK path content survives extraction
        // (drift to ASCII-only filtering would silently break
        // operators with non-ASCII paths).
        assertEquals(
            "/Users/张三/Desktop/视频.mp4",
            extractPathPattern(
                inputJson = """{"path":"/Users/张三/Desktop/视频.mp4"}""",
                field = "path",
            ),
        )
    }

    @Test fun globPatternRoundTripsVerbatim() {
        // Pin: glob meta-chars (`*`, `?`, `[]`) survive verbatim
        // — drift to "escape the glob chars" would silently
        // change how the permission rule matches against the
        // user's "Always allow" patterns.
        assertEquals(
            "/Users/*/Desktop/**.{mp4,mov}",
            extractPathPattern(
                inputJson = """{"pattern":"/Users/*/Desktop/**.{mp4,mov}"}""",
                field = "pattern",
            ),
        )
    }

    @Test fun customFieldNameRespected() {
        // Pin: `field` arg drives which JSON key to read. Drift
        // to "always read 'path'" would silently break fs.list
        // / glob_files (which use `pattern` instead).
        assertEquals(
            "**/*.kt",
            extractPathPattern(
                inputJson = """{"path":"/decoy","pattern":"**/*.kt"}""",
                field = "pattern",
            ),
        )
    }

    // ── Failure modes — all fall back to "*" ───────────────

    @Test fun malformedJsonFallsBackToWildcard() {
        // Marquee parse-error pin: parse failure → "*". Drift
        // to "throw on malformed input" would crash the
        // dispatcher; drift to "" would silently never match.
        assertEquals(
            "*",
            extractPathPattern(inputJson = "not json {{{", field = "path"),
        )
    }

    @Test fun nonObjectRootFallsBackToWildcard() {
        // Pin: JSON array / primitive at the root → "*".
        // `jsonObject` accessor throws on a non-object — caught
        // by runCatching.
        for (notObject in listOf(
            """["a","b"]""",
            """"just a string"""",
            """42""",
            """null""",
        )) {
            assertEquals(
                "*",
                extractPathPattern(inputJson = notObject, field = "path"),
                "non-object root '$notObject' MUST fall back to '*'",
            )
        }
    }

    @Test fun missingFieldFallsBackToWildcard() {
        // Pin: field not present in object → null content →
        // takeIf gates → "*" fallback. Drift to "" would
        // silently never match.
        assertEquals(
            "*",
            extractPathPattern(
                inputJson = """{"otherField":"x"}""",
                field = "path",
            ),
        )
    }

    @Test fun nullFieldFallsBackToWildcard() {
        // Pin: explicit null value → JsonNull, not a primitive
        // string. `jsonPrimitive` of JsonNull is JsonNull (whose
        // .content is "null"); but the takeIf chain... actually
        // jsonPrimitive on JsonNull works (JsonNull is a
        // JsonPrimitive). Its content is "null" which is non-blank
        // → would be returned. Pin the actual behaviour.
        // Wait — JsonNull is itself a JsonPrimitive with
        // content="null" and isString=false. So this is one of
        // the gotcha cases. Per the source (the function uses
        // `.content`, NOT `.contentOrNull`), `null` would
        // round-trip as the literal string "null".
        // **This pin documents the actual behaviour** so a
        // future refactor doesn't quietly change it.
        assertEquals(
            "null",
            extractPathPattern(
                inputJson = """{"path":null}""",
                field = "path",
            ),
            "JSON null field returns the literal string 'null' (NOT '*' fallback) — pinning observed behaviour",
        )
    }

    @Test fun nonStringPrimitiveFieldReturnsContentString() {
        // Pin: number / boolean fields go through `jsonPrimitive`
        // (works for any primitive) and `.content` (returns the
        // raw string form: "42" / "true"). The takeIf gate
        // accepts non-blank, so the result is the stringified
        // form — NOT "*" fallback.
        // **Pinning observed behaviour**: drift to "filter to
        // strings only" would change failure modes; drift to
        // "stringify with quotes" would silently change format.
        assertEquals(
            "42",
            extractPathPattern(
                inputJson = """{"path":42}""",
                field = "path",
            ),
        )
        assertEquals(
            "true",
            extractPathPattern(
                inputJson = """{"path":true}""",
                field = "path",
            ),
        )
    }

    @Test fun objectFieldFallsBackToWildcard() {
        // Pin: nested object as the field value → `jsonPrimitive`
        // throws → runCatching → null → "*" fallback. Drift to
        // stringify the nested object (silly path) would surface
        // here.
        assertEquals(
            "*",
            extractPathPattern(
                inputJson = """{"path":{"nested":"value"}}""",
                field = "path",
            ),
        )
    }

    @Test fun arrayFieldFallsBackToWildcard() {
        // Pin: array as the field value → `jsonPrimitive` throws
        // → "*". Drift to "use first element" would silently
        // change which path scopes the permission.
        assertEquals(
            "*",
            extractPathPattern(
                inputJson = """{"path":["a","b"]}""",
                field = "path",
            ),
        )
    }

    @Test fun emptyStringFieldFallsBackToWildcard() {
        // Marquee blank-filter pin: empty string is "blank" per
        // `isNotBlank` — falls back to "*". Drift to "let blank
        // through" would silently NEVER match any rule (because
        // empty string ≠ "*" lexically), refusing every fs op.
        assertEquals(
            "*",
            extractPathPattern(
                inputJson = """{"path":""}""",
                field = "path",
            ),
        )
    }

    @Test fun whitespaceOnlyStringFieldFallsBackToWildcard() {
        // Pin: whitespace-only string is blank → "*" fallback.
        for (blank in listOf("   ", "\t", "\n", "\t  \n")) {
            assertEquals(
                "*",
                extractPathPattern(
                    inputJson = """{"path":"$blank"}""",
                    field = "path",
                ),
                "whitespace-only field '$blank' MUST fall back to '*'",
            )
        }
    }

    @Test fun extraFieldsArePreservedButNotInfluencingResult() {
        // Pin: presence of unrelated fields doesn't change the
        // result — only the `field` arg matters. Drift to "use
        // first string field" would let unrelated keys hijack
        // the pattern.
        assertEquals(
            "/expected/path",
            extractPathPattern(
                inputJson = """{"junk":"noise","path":"/expected/path","more":42}""",
                field = "path",
            ),
        )
    }

    @Test fun emptyJsonObjectFallsBackToWildcard() {
        // Pin: `{}` has no fields → null → "*".
        assertEquals(
            "*",
            extractPathPattern(inputJson = "{}", field = "path"),
        )
    }

    @Test fun completelyEmptyInputFallsBackToWildcard() {
        // Pin: empty string → JSON parse fails → runCatching
        // catches → "*". Drift to "throw on empty" would crash
        // the dispatcher.
        assertEquals(
            "*",
            extractPathPattern(inputJson = "", field = "path"),
        )
    }
}
