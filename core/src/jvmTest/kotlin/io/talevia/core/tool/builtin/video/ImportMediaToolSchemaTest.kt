package io.talevia.core.tool.builtin.video

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [IMPORT_MEDIA_INPUT_SCHEMA] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/video/ImportMediaToolSchema.kt`.
 * Cycle 242 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-241.
 *
 * The schema is the LLM-visible contract for `import_media`: the
 * agent reads it as a tool spec and decides what JSON to emit when
 * a user asks to import footage. Drift in any field's type /
 * description / required-list silently changes what the LLM
 * believes the tool accepts — surfaces only as user-visible "agent
 * sent malformed input" failures.
 *
 * The schema lives in a separate file (away from
 * `ImportMediaTool.kt`) per `R.5.4`'s long-file debt threshold —
 * the file kdoc explicitly says "byte-identical to the previous
 * inline definition; every field description is preserved
 * verbatim so the LLM-visible schema does not change". A pin
 * here protects that promise across future edits.
 *
 * Pins three correctness contracts:
 *
 *  1. **Top-level shape.** `type=object`, `additionalProperties=false`,
 *     `required=[]` (the empty array is significant — `path` xor
 *     `paths` is enforced inside `execute()`, NOT at the schema
 *     level). Drift to "required = ['path']" would force the LLM
 *     to ALWAYS send `path` (breaking batch imports); drift to
 *     `additionalProperties = true` (or omitting the key) would
 *     let the LLM smuggle ignored fields without surface error.
 *
 *  2. **Properties block has exactly the 4 documented fields.**
 *     `path`, `paths`, `projectId`, `copy_into_bundle`. Drift to
 *     drop / rename / silently add a field would mismatch what
 *     the dispatcher's deserialiser accepts.
 *
 *  3. **Each property's type matches the dispatcher's expectation.**
 *     `path: string`, `paths: array<string>`, `projectId: string`,
 *     `copy_into_bundle: boolean`. Drift to a different type would
 *     silently fail at deserialisation (the LLM sends a value of
 *     the type the schema declared, the deserialiser rejects it).
 *     The `paths` items type is also pinned (`array<string>`,
 *     not `array<object>`).
 *
 * Plus description-quality pins: every property's description is
 * non-empty (drift to "TODO: describe" would still leave the LLM
 * guessing); the `path` / `paths` descriptions both call out the
 * mutually-exclusive constraint (drift to "use both" would
 * silently change validation expectations); the `copy_into_bundle`
 * description preserves its "tri-state" wording (the auto / true /
 * false semantics are the marquee documented behavior).
 */
class ImportMediaToolSchemaTest {

    private val schema: JsonObject = IMPORT_MEDIA_INPUT_SCHEMA

    private val properties: JsonObject by lazy {
        schema["properties"]?.jsonObject
            ?: error("schema missing 'properties' object")
    }

    // ── 1. Top-level shape ──────────────────────────────────

    @Test fun typeIsObject() {
        assertEquals(
            "object",
            schema["type"]?.jsonPrimitive?.content,
            "schema type MUST be 'object'",
        )
    }

    @Test fun requiredIsEmptyArray() {
        // Marquee xor-enforcement pin: per the file kdoc,
        // `path` xor `paths` is checked inside execute(), NOT at
        // schema level. Drift to "required = ['path']" or
        // `["paths"]` would force the LLM to always send one,
        // silently breaking batch imports / single imports.
        val required = schema["required"]
        assertNotNull(required, "schema MUST declare 'required' (even if empty)")
        assertTrue(required is JsonArray, "required MUST be a JsonArray")
        assertEquals(
            0,
            required.jsonArray.size,
            "required MUST be the empty array (xor enforced in execute())",
        )
    }

    @Test fun additionalPropertiesIsFalse() {
        // Marquee strict-validation pin: drift to `true` (or
        // omitting the key) would let the LLM smuggle ignored
        // fields without surface error — silently absorbing
        // typos like `copyIntoBundle` (camelCase) instead of
        // surfacing them.
        val ap = schema["additionalProperties"]
        assertNotNull(ap, "schema MUST declare 'additionalProperties'")
        assertTrue(
            ap is JsonPrimitive && !ap.boolean,
            "additionalProperties MUST be the boolean `false`; got: $ap",
        )
    }

    // ── 2. Properties block — exactly 4 fields ──────────────

    @Test fun propertiesContainsExactly4DocumentedFields() {
        // Marquee field-set pin: the schema's properties match
        // exactly the 4 documented fields. Drift to drop / rename
        // / add a property would shift the LLM-visible contract.
        assertEquals(
            setOf("path", "paths", "projectId", "copy_into_bundle"),
            properties.keys,
            "properties MUST contain exactly {path, paths, projectId, copy_into_bundle}",
        )
    }

    // ── 3. Per-property type pins ───────────────────────────

    @Test fun pathIsString() {
        assertEquals(
            "string",
            properties["path"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
            "path MUST be type=string",
        )
    }

    @Test fun pathsIsArrayOfStrings() {
        // Pin: `paths` is an array of strings (not array of
        // objects, not array<unspecified>). Drift to a different
        // items.type would silently mis-route the LLM into
        // wrapping each path in a JsonObject.
        val paths = properties["paths"]?.jsonObject ?: error("paths missing")
        assertEquals(
            "array",
            paths["type"]?.jsonPrimitive?.content,
            "paths MUST be type=array",
        )
        assertEquals(
            "string",
            paths["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
            "paths.items MUST be type=string",
        )
    }

    @Test fun projectIdIsString() {
        assertEquals(
            "string",
            properties["projectId"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )
    }

    @Test fun copyIntoBundleIsBoolean() {
        // Marquee boolean pin: drift to "string" (e.g. so the
        // LLM sends "true"/"false" strings) would silently
        // change tri-state parsing — false-string would no
        // longer match the false branch.
        assertEquals(
            "boolean",
            properties["copy_into_bundle"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )
    }

    // ── 4. Description-quality pins ─────────────────────────

    @Test fun everyPropertyHasNonEmptyDescription() {
        // Pin: every property carries a description so the LLM
        // knows what each field means. Drift to a missing /
        // empty description would force the LLM to guess (drift
        // to "TODO: describe" would silently ship the typo).
        for ((key, propValue) in properties) {
            val desc = propValue.jsonObject["description"]?.jsonPrimitive?.content
            assertNotNull(desc, "property '$key' MUST have a 'description'")
            assertTrue(
                desc.isNotBlank(),
                "property '$key' description MUST be non-empty; got: '$desc'",
            )
        }
    }

    @Test fun pathAndPathsDescribeMutualExclusivity() {
        // Pin: both descriptions call out the mutually-
        // exclusive constraint. Drift to "use either" or
        // "merge if both supplied" would silently change the
        // LLM's mental model of the input contract.
        val pathDesc = properties["path"]?.jsonObject?.get("description")?.jsonPrimitive?.content ?: ""
        val pathsDesc =
            properties["paths"]?.jsonObject?.get("description")?.jsonPrimitive?.content ?: ""
        assertTrue(
            "Mutually exclusive" in pathDesc || "mutually exclusive" in pathDesc,
            "path description MUST surface the mutual-exclusion constraint; got: $pathDesc",
        )
        assertTrue(
            "Mutually exclusive" in pathsDesc || "mutually exclusive" in pathsDesc,
            "paths description MUST surface the mutual-exclusion constraint; got: $pathsDesc",
        )
    }

    @Test fun copyIntoBundleDescriptionPreservesTriStateWording() {
        // Marquee tri-state pin: per the schema's description,
        // `copy_into_bundle` accepts true / false / omitted, with
        // omitted → size-based auto. Drift to "boolean default
        // false" would silently change which files travel with
        // `git push`.
        val desc = properties["copy_into_bundle"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("copy_into_bundle missing description")
        assertTrue(
            "Tri-state" in desc,
            "copy_into_bundle description MUST surface the tri-state wording; got: $desc",
        )
        assertTrue(
            "auto" in desc,
            "copy_into_bundle description MUST mention auto behavior; got: $desc",
        )
        assertTrue(
            "50 MiB" in desc || "50MiB" in desc,
            "copy_into_bundle description MUST cite the 50 MiB auto threshold (so LLM doesn't guess); got: $desc",
        )
    }

    @Test fun projectIdDescriptionMentionsSwitchProject() {
        // Pin: the projectId description points at switch_project
        // so the LLM knows where the session-level default comes
        // from. Drift to a generic "current project" description
        // would lose that pointer.
        val desc = properties["projectId"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("projectId missing description")
        assertTrue(
            "switch_project" in desc,
            "projectId description MUST cite switch_project; got: $desc",
        )
    }
}
