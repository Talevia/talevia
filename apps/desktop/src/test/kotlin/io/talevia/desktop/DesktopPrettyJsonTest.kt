package io.talevia.desktop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [DesktopPrettyJson] —
 * `apps/desktop/src/main/kotlin/io/talevia/desktop/DesktopPrettyJson.kt`.
 * Cycle 272 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-271.
 *
 * `DesktopPrettyJson` is the centralised pretty-print Json
 * instance the Desktop panels (LockfilePanel, TimelineClipRow,
 * SourceNodeRow) use to render JSON-tree views. Per the
 * `debt-centralise-pretty-json-desktop` (2026-04-23)
 * consolidation kdoc, drift in any of its 4 settings silently
 * changes how panels render JSON to users:
 *
 *   - `prettyPrint = true` — drift to false would render
 *     panel JSON as one giant line instead of a tree.
 *   - `prettyPrintIndent = "  "` (2 spaces) — drift to tab /
 *     4 spaces silently changes panel visual spacing.
 *   - Inherits `classDiscriminator = "type"` from
 *     `JsonConfig.default` — load-bearing for Clip polymorphic
 *     serialization in TimelineClipRow.
 *   - Inherits `ignoreUnknownKeys = true` — drift to false
 *     would crash panels on unknown fields (e.g. forward-
 *     compat additions).
 *   - Inherits `encodeDefaults = false` — drift would bloat
 *     panel JSON with redundant default-value fields.
 *
 * Pins three correctness contracts:
 *
 *  1. **Pretty-print rendering**: encoded output contains
 *     newlines (drift to compact would make every panel
 *     a single-line wall).
 *
 *  2. **2-space indent**: nested levels use exactly 2 spaces
 *     (drift to tab / 4 spaces silently shifts panel
 *     spacing without crashing).
 *
 *  3. **Inherited JsonConfig.default settings**:
 *     - `classDiscriminator = "type"` — verified by
 *       round-tripping a polymorphic class.
 *     - `ignoreUnknownKeys = true` — verified by decoding
 *       JSON with extra keys (no exception).
 */
class DesktopPrettyJsonTest {

    @Test fun encodingIsMultilineWithNewlines() {
        // Marquee pretty-print pin: any non-trivial JsonObject
        // encoding contains newlines (drift to
        // `prettyPrint=false` would make every panel a
        // single-line wall).
        val obj = buildJsonObject {
            put("name", "test")
            put("value", 42)
            put("active", true)
        }
        val encoded = DesktopPrettyJson.encodeToString(JsonObject.serializer(), obj)
        assertTrue(
            "\n" in encoded,
            "DesktopPrettyJson MUST produce multi-line output (drift to prettyPrint=false surfaces here); got: $encoded",
        )
    }

    @Test fun nestedLevelsUseTwoSpaceIndent() {
        // Marquee 2-space indent pin: drift to tab / 4 spaces
        // silently changes panel visual spacing. Encode a
        // nested object — first inner field's line MUST start
        // with exactly 2 spaces.
        val obj = buildJsonObject {
            put("outer", "x")
            put("nested", buildJsonObject { put("inner", "y") })
        }
        val encoded = DesktopPrettyJson.encodeToString(JsonObject.serializer(), obj)
        // Find the `"outer"` line — it MUST be indented by 2
        // spaces (top-level fields are nested 1 level deep
        // inside the root object).
        val lines = encoded.lines()
        val outerLine = lines.firstOrNull { """"outer"""" in it }
            ?: error("expected to find \"outer\" line; got: $encoded")
        assertTrue(
            outerLine.startsWith("  \"outer\""),
            "first-level field MUST be indented with exactly 2 spaces; got: '$outerLine'",
        )
        // Find `"inner"` line — nested inside `nested`, so
        // 4 spaces (2 levels × 2 spaces).
        val innerLine = lines.firstOrNull { """"inner"""" in it }
            ?: error("expected \"inner\" line; got: $encoded")
        assertTrue(
            innerLine.startsWith("    \"inner\""),
            "second-level field MUST be indented with 4 spaces (2 levels × 2 each); got: '$innerLine'",
        )
        // Negative-evidence: NO line starts with a tab
        // (drift to tab indent surfaces here).
        for (line in lines) {
            assertTrue(
                !line.startsWith("\t"),
                "line MUST NOT start with tab character (drift to tab-indent surfaces); got: '$line'",
            )
        }
    }

    @Test fun configurationFlagsInheritFromJsonConfigDefault() {
        // Pin: per the kdoc, DesktopPrettyJson is built from
        // `Json(JsonConfig.default) { ... }` — meaning it
        // INHERITS classDiscriminator / ignoreUnknownKeys /
        // encodeDefaults from the base config. Drift to
        // override / drop the inheritance would silently
        // change panel behavior. Verify via the Json
        // instance's `configuration` snapshot.
        //
        // Engineering note: apps/desktop doesn't apply the
        // kotlinx-serialization plugin, so we can't write
        // @Serializable test fixtures here — instead we
        // inspect the configuration object directly.
        val cfg = DesktopPrettyJson.configuration
        assertEquals(
            "type",
            cfg.classDiscriminator,
            "DesktopPrettyJson MUST inherit classDiscriminator='type' (load-bearing for Clip polymorphic serialization in TimelineClipRow)",
        )
        assertTrue(
            cfg.ignoreUnknownKeys,
            "DesktopPrettyJson MUST inherit ignoreUnknownKeys=true (drift would crash panels on forward-compat fields)",
        )
        assertTrue(
            !cfg.encodeDefaults,
            "DesktopPrettyJson MUST inherit encodeDefaults=false (drift to true would bloat panel JSON)",
        )
    }

    @Test fun configurationPrettyPrintFlagsAreExplicitlyOverridden() {
        // Pin: the explicit overrides in DesktopPrettyJson —
        // `prettyPrint = true` and `prettyPrintIndent = "  "`.
        // The configuration accessor exposes these directly,
        // so we can verify the overrides took effect (NOT
        // accidentally inherited from the false default).
        val cfg = DesktopPrettyJson.configuration
        assertTrue(
            cfg.prettyPrint,
            "DesktopPrettyJson MUST explicitly set prettyPrint=true",
        )
        assertEquals(
            "  ",
            cfg.prettyPrintIndent,
            "DesktopPrettyJson MUST set prettyPrintIndent to exactly 2 spaces",
        )
    }

    @Test fun jsonObjectRoundTripPreservesShape() {
        // Sanity pin: encoding then decoding via
        // DesktopPrettyJson is a no-op semantically.
        val obj = buildJsonObject {
            put("a", JsonPrimitive("string"))
            put("b", JsonPrimitive(42))
            put("c", JsonPrimitive(true))
            put("d", JsonPrimitive(3.14))
        }
        val encoded = DesktopPrettyJson.encodeToString(JsonObject.serializer(), obj)
        val decoded = DesktopPrettyJson.parseToJsonElement(encoded).jsonObject
        assertEquals(
            obj["a"]?.jsonPrimitive?.content,
            decoded["a"]?.jsonPrimitive?.content,
        )
        assertEquals(
            obj["b"]?.jsonPrimitive?.content,
            decoded["b"]?.jsonPrimitive?.content,
        )
        assertEquals(
            obj["c"]?.jsonPrimitive?.content,
            decoded["c"]?.jsonPrimitive?.content,
        )
        assertEquals(
            obj["d"]?.jsonPrimitive?.content,
            decoded["d"]?.jsonPrimitive?.content,
        )
    }

    @Test fun emptyJsonObjectStillProducesValidOutput() {
        // Edge: empty object encodes to `{}` (drift to crash
        // on empty would surface here).
        val empty = buildJsonObject { }
        val encoded = DesktopPrettyJson.encodeToString(JsonObject.serializer(), empty)
        assertEquals("{}", encoded)
    }
}
