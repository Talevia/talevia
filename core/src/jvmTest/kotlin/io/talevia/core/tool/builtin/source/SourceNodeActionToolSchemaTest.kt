package io.talevia.core.tool.builtin.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [SOURCE_NODE_ACTION_INPUT_SCHEMA] — the JSON
 * schema for `source_node_action`, a 7-verb dispatcher
 * (add / remove / fork / rename / update_body / set_parents / import).
 * Cycle 109 audit: 140 LOC, **zero** transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Action enum is the complete verb list.** The LLM's
 *    discriminator. A regression dropping a verb (e.g., `import`,
 *    which cycle 136 folded in from a standalone tool) would
 *    silently render that verb un-callable; adding a verb to the
 *    dispatcher without the enum lets it slip past schema validation
 *    but fail cryptically at dispatch.
 *
 * 2. **`body` accepts arbitrary JSON via `additionalProperties: true`.**
 *    The kdoc commits to "opaque JSON matching genre shape"
 *    — Core deliberately doesn't know the inner shape. A regression
 *    flipping this to `false` would reject every legitimate genre
 *    body the LLM submits (narrative.scene's `description`,
 *    musicmv.track's `bpm`, etc.).
 *
 * 3. **revisionIndex fields enforce `minimum: 0`.** The kdoc
 *    commits to "0 = most-recent overwritten" — negative values
 *    are nonsense for a revision index and would fail late at
 *    dispatch with a confusing error. Pinning `minimum: 0`
 *    catches the schema-level rejection.
 */
class SourceNodeActionToolSchemaTest {

    private val schema: JsonObject = SOURCE_NODE_ACTION_INPUT_SCHEMA
    private val props: JsonObject by lazy { schema["properties"]!!.jsonObject }

    private fun JsonObject.requiredKeys(): Set<String> =
        (this["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet()

    // ── top-level shape ───────────────────────────────────────────

    @Test fun topLevelTypeIsObject() {
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
    }

    @Test fun topLevelRequiredIsProjectIdAndAction() {
        // Pin: both projectId AND action are universally required.
        // A regression dropping projectId would let the LLM submit
        // ambiguous "edit some node" without knowing which project
        // — fails late, dispatcher would have no fallback.
        assertEquals(setOf("projectId", "action"), schema.requiredKeys())
    }

    @Test fun topLevelHasAdditionalPropertiesFalse() {
        // Closes the input shape so the LLM can't slip arbitrary
        // keys past schema validation.
        val ap = schema["additionalProperties"]
        assertNotNull(ap)
        assertEquals(false, ap.jsonPrimitive.boolean)
    }

    // ── action enum ───────────────────────────────────────────────

    @Test fun actionEnumExposesAllSevenVerbs() {
        // Pin: the complete verb set. Cycle 136 folded `import` from
        // a standalone tool; future verbs need both schema + dispatch
        // updates. This test catches drift between them.
        val actionSchema = props["action"]!!.jsonObject
        assertEquals("string", actionSchema["type"]!!.jsonPrimitive.content)
        val verbs = (actionSchema["enum"] as JsonArray)
            .map { it.jsonPrimitive.content }
            .toSet()
        assertEquals(
            setOf("add", "remove", "fork", "rename", "update_body", "set_parents", "import"),
            verbs,
            "action enum drift; got: $verbs",
        )
    }

    // ── body field: opaque genre payload ──────────────────────────

    @Test fun bodyAllowsAdditionalProperties() {
        // The marquee pin. `body` carries genre-specific shapes that
        // Core deliberately doesn't validate (per CLAUDE.md
        // anti-requirement: "在 Core 里硬编码某一个 genre 的 source schema").
        // Schema MUST allow arbitrary keys inside, otherwise
        // narrative.scene { description: "...", sceneId: "..." }
        // gets rejected at the LLM-facing schema layer.
        val body = props["body"]!!.jsonObject
        assertEquals("object", body["type"]!!.jsonPrimitive.content)
        val ap = body["additionalProperties"]
        assertNotNull(ap)
        assertEquals(
            true,
            ap.jsonPrimitive.boolean,
            "body MUST allow additionalProperties (genre payload is opaque to Core)",
        )
    }

    // ── revision-index fields ─────────────────────────────────────

    @Test fun restoreFromRevisionIndexHasMinimumZero() {
        // Pin: 0 = most-recent overwritten body. Negative values are
        // nonsense (no -1 revision exists) — schema-level rejection
        // beats dispatch-level rejection because it gives the LLM a
        // type-check failure rather than a runtime "no such revision".
        val field = props["restoreFromRevisionIndex"]!!.jsonObject
        assertEquals("integer", field["type"]!!.jsonPrimitive.content)
        assertEquals(0, field["minimum"]!!.jsonPrimitive.int)
    }

    @Test fun mergeFromRevisionIndexHasMinimumZero() {
        val field = props["mergeFromRevisionIndex"]!!.jsonObject
        assertEquals("integer", field["type"]!!.jsonPrimitive.content)
        assertEquals(0, field["minimum"]!!.jsonPrimitive.int)
    }

    @Test fun restoreAndMergeIndicesAreMutuallyExclusiveDocumented() {
        // The schema can't enforce mutual exclusion via JSON Schema's
        // `oneOf` here (the dispatcher checks at runtime), but the
        // descriptions MUST mention the exclusion so the LLM knows
        // not to set both. Pin: both descriptions reference the
        // exclusion explicitly.
        val restore = props["restoreFromRevisionIndex"]!!.jsonObject
        val restoreDesc = restore["description"]!!.jsonPrimitive.content
        assertTrue(
            "mergeFromRevisionIndex" in restoreDesc,
            "restore desc must reference mergeFromRevisionIndex; got: $restoreDesc",
        )
        assertTrue("body" in restoreDesc, "restore desc must reference body exclusion; got: $restoreDesc")

        val merge = props["mergeFromRevisionIndex"]!!.jsonObject
        val mergeDesc = merge["description"]!!.jsonPrimitive.content
        assertTrue(
            "restoreFromRevisionIndex" in mergeDesc,
            "merge desc must reference restoreFromRevisionIndex; got: $mergeDesc",
        )
        assertTrue("body" in mergeDesc, "merge desc must reference body exclusion")
    }

    // ── array typing ──────────────────────────────────────────────

    @Test fun parentIdsIsArrayOfStrings() {
        val field = props["parentIds"]!!.jsonObject
        assertEquals("array", field["type"]!!.jsonPrimitive.content)
        assertEquals("string", field["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun mergeFieldPathsIsArrayOfStrings() {
        // The kdoc says "top-level keys to copy" — string-typed
        // items match. A regression typing items as integer would
        // make the field unusable.
        val field = props["mergeFieldPaths"]!!.jsonObject
        assertEquals("array", field["type"]!!.jsonPrimitive.content)
        assertEquals("string", field["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    // ── import-verb specific fields ───────────────────────────────

    @Test fun importHasThreeMutuallyExclusivePathwaysDocumented() {
        // Pin: the import verb supports two pathways:
        //   a) cross-project live: fromProjectId + fromNodeId
        //   b) portable envelope: envelope (string)
        // Each side's description MUST mention the exclusion so
        // the LLM knows not to set both — the schema can't enforce
        // it at structure level.
        val fromProjectDesc = props["fromProjectId"]!!.jsonObject["description"]!!
            .jsonPrimitive.content
        val envelopeDesc = props["envelope"]!!.jsonObject["description"]!!
            .jsonPrimitive.content
        // fromProjectId description references the envelope exclusion.
        assertTrue(
            "envelope" in fromProjectDesc,
            "fromProjectId desc must reference envelope; got: $fromProjectDesc",
        )
        // envelope description references fromProjectId / fromNodeId.
        assertTrue(
            "fromProjectId" in envelopeDesc && "fromNodeId" in envelopeDesc,
            "envelope desc must reference fromProjectId + fromNodeId; got: $envelopeDesc",
        )
    }

    @Test fun importEnvelopeFieldIsString() {
        // Pin: envelope is a string, not an object — the export tool
        // emits a serialised JSON string the LLM can pass through
        // without re-parsing. A regression typing it as object would
        // force the LLM to escape JSON inside JSON.
        val envelope = props["envelope"]!!.jsonObject
        assertEquals("string", envelope["type"]!!.jsonPrimitive.content)
        assertTrue(
            "formatVersion checked" in envelope["description"]!!.jsonPrimitive.content,
            "envelope description must mention formatVersion validation",
        )
    }

    // ── verb-specific field documentation hints ───────────────────

    @Test fun nodeIdDescriptionListsApplicableActions() {
        // The kdoc surface for the LLM lists which actions need
        // nodeId. Pin so a refactor adding a new action that needs
        // nodeId is forced to update the description.
        val desc = props["nodeId"]!!.jsonObject["description"]!!.jsonPrimitive.content
        // 4 actions need nodeId per current code:
        //   add / remove / update_body / set_parents
        assertTrue("add" in desc, "must list 'add'; got: $desc")
        assertTrue("remove" in desc, "must list 'remove'; got: $desc")
        assertTrue("update_body" in desc, "must list 'update_body'; got: $desc")
        assertTrue("set_parents" in desc, "must list 'set_parents'; got: $desc")
    }

    @Test fun renameActionHasOldIdAndNewIdFields() {
        // Pin: rename uses two distinct fields (oldId for source,
        // newId for target). A regression collapsing them or using
        // generic nodeId/newNodeId from the fork verb would corrupt
        // the rename semantics.
        val oldId = props["oldId"]!!.jsonObject
        val newId = props["newId"]!!.jsonObject
        assertEquals("string", oldId["type"]!!.jsonPrimitive.content)
        assertEquals("string", newId["type"]!!.jsonPrimitive.content)
        assertTrue(
            "rename" in oldId["description"]!!.jsonPrimitive.content,
            "oldId desc must reference rename verb",
        )
        assertTrue(
            "rename" in newId["description"]!!.jsonPrimitive.content,
            "newId desc must reference rename verb",
        )
    }

    @Test fun forkUsesSourceNodeIdAndOptionalNewNodeId() {
        // Pin: fork uses `sourceNodeId` (NOT `oldId`) and `newNodeId`
        // (NOT `newId`). A refactor collapsing these into the
        // rename pair would silently change the LLM's input shape.
        val source = props["sourceNodeId"]!!.jsonObject
        val newNode = props["newNodeId"]!!.jsonObject
        assertEquals("string", source["type"]!!.jsonPrimitive.content)
        assertTrue(
            "fork" in source["description"]!!.jsonPrimitive.content,
            "sourceNodeId desc must reference fork; got: ${source["description"]}",
        )
        assertEquals("string", newNode["type"]!!.jsonPrimitive.content)
        // newNodeId is optional + shared between fork and import (per
        // current schema description).
        val newNodeDesc = newNode["description"]!!.jsonPrimitive.content
        assertTrue("fork" in newNodeDesc, "newNodeId desc must reference fork; got: $newNodeDesc")
        assertTrue(
            "import" in newNodeDesc,
            "newNodeId desc must reference import (shared use); got: $newNodeDesc",
        )
    }

    @Test fun kindFieldDescriptionEmphasizesGenreValidation() {
        // Pin the architectural boundary: the kdoc for `kind` MUST
        // mention "Genre-validated, not Core" so the LLM
        // understands that Core won't pre-validate the kind string.
        // CLAUDE.md anti-requirement #5 ("Core 不硬编码 genre 概念")
        // depends on this contract being clear at the LLM-facing
        // boundary too.
        val desc = props["kind"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "Genre-validated, not Core" in desc || ("genre" in desc.lowercase()),
            "kind desc must reference genre validation boundary; got: $desc",
        )
    }
}
