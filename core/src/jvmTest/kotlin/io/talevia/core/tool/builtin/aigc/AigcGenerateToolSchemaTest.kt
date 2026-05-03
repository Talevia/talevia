package io.talevia.core.tool.builtin.aigc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [buildOneOfInputSchema] — the LLM-facing JSON Schema
 * for `aigc_generate(kind=image|video|music|speech, ...)`. Cycle 103
 * audit: 158 LOC of hand-written schema, **zero** transitive test
 * references; the kdoc commits to "the LLM-facing schema is hand-built
 * so per-field descriptions land in the prompt" but the field set
 * itself was unprotected.
 *
 * Three correctness contracts a regression could silently break:
 *
 * 1. **Per-variant field isolation.** Each `oneOf` branch lists ONLY
 *    the fields valid for that kind — a regression that pasted the
 *    `width/height` props into the `music` branch would let the LLM
 *    pass nonsense (width=512 to a music gen) and the tool would
 *    reject at dispatch time, wasting a turn. The `kitchen-sink
 *    optional bag` anti-pattern is the canonical regression here.
 *
 * 2. **`additionalProperties: false` per branch.** Without this, the
 *    LLM could pass arbitrary keys that the validator silently
 *    accepts, then the dispatch layer fails with confusing "unknown
 *    field" errors. Each branch must commit explicitly.
 *
 * 3. **`required: [kind, prompt]` per branch.** The kind discriminator
 *    + prompt are mandatory on every variant. A regression dropping
 *    either from a branch's required list would let the LLM submit
 *    incomplete inputs that fail late.
 *
 * Plus the shared-props pin: `seed / projectId / consistencyBindingIds
 * / variantCount` must appear on EVERY branch (`sharedProps()` call).
 * If a refactor accidentally calls `sharedProps()` on only some
 * branches, that variant silently loses access to fundamental knobs.
 */
class AigcGenerateToolSchemaTest {

    private val schema = buildOneOfInputSchema()

    private val branches: List<JsonObject> by lazy {
        schema["oneOf"]!!.jsonArray.map { it.jsonObject }
    }

    private fun branchByKind(kind: String): JsonObject = branches.single {
        val props = it["properties"]!!.jsonObject
        val kindObj = props["kind"]!!.jsonObject
        kindObj["const"]?.jsonPrimitive?.content == kind
    }

    private fun JsonObject.requiredKeys(): Set<String> =
        (this["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet()

    private fun JsonObject.propKeys(): Set<String> =
        (this["properties"]!!.jsonObject).keys

    // ── top-level shape ───────────────────────────────────────────

    @Test fun topLevelTypeIsObject() {
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
    }

    @Test fun oneOfHasExactlyFourBranches() {
        // Pin: 4 AIGC kinds — image, video, music, speech. A new
        // kind landing without a matching branch would silently
        // miss LLM exposure (the LLM sees only the schema, the
        // dispatch lives in `AigcGenerateToolDispatchers`).
        // Conversely, dropping a branch silently makes that kind
        // un-callable by the LLM even if the dispatch is wired.
        assertEquals(4, branches.size)
    }

    @Test fun oneOfBranchKindsCoverImageVideoMusicSpeech() {
        val kinds = branches.map {
            it["properties"]!!.jsonObject["kind"]!!.jsonObject["const"]!!.jsonPrimitive.content
        }.toSet()
        assertEquals(setOf("image", "video", "music", "speech"), kinds)
    }

    @Test fun topLevelRequiredIsKindAndPrompt() {
        // Pin: top-level required is `[kind, prompt]`. Both branches
        // also list these as required individually, but the
        // top-level shape is what the LLM strictly validates first.
        assertEquals(setOf("kind", "prompt"), schema.requiredKeys())
    }

    // ── per-branch invariants ─────────────────────────────────────

    @Test fun everyBranchIsTypeObject() {
        for (b in branches) {
            assertEquals(
                "object",
                b["type"]!!.jsonPrimitive.content,
                "every oneOf branch must be type=object",
            )
        }
    }

    @Test fun everyBranchHasAdditionalPropertiesFalse() {
        // Pin: each branch closes the field set so the LLM can't
        // pass arbitrary keys that bypass validation.
        for (b in branches) {
            val ap = b["additionalProperties"]
            assertNotNull(ap, "branch must declare additionalProperties; got: $b")
            assertEquals(
                false,
                ap.jsonPrimitive.boolean,
                "every branch must set additionalProperties=false",
            )
        }
    }

    @Test fun everyBranchRequiresKindAndPrompt() {
        for (b in branches) {
            val required = b.requiredKeys()
            assertTrue(
                "kind" in required,
                "branch ${b["properties"]} must require 'kind'; got required=$required",
            )
            assertTrue(
                "prompt" in required,
                "branch ${b["properties"]} must require 'prompt'; got required=$required",
            )
        }
    }

    @Test fun everyBranchHasPromptStringProperty() {
        for (b in branches) {
            val props = b["properties"]!!.jsonObject
            val prompt = props["prompt"]
            assertNotNull(prompt, "branch must define prompt; got: $b")
            assertEquals("string", prompt.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    // ── kind-specific properties ──────────────────────────────────

    @Test fun imageBranchHasWidthAndHeightAndModel() {
        // Pin: image-specific knobs. A regression copying video's
        // durationSeconds into image's branch would let the LLM
        // submit nonsense like {kind: "image", durationSeconds: 5}.
        val image = branchByKind("image")
        val keys = image.propKeys()
        assertTrue("width" in keys)
        assertTrue("height" in keys)
        assertTrue("model" in keys)
        // Pin: image MUST NOT have video/music/speech-only fields.
        assertTrue("durationSeconds" !in keys, "image must not have durationSeconds")
        assertTrue("voice" !in keys, "image must not have voice")
        assertTrue("speed" !in keys, "image must not have speed")
        assertTrue("format" !in keys, "image must not have format")
    }

    @Test fun videoBranchHasWidthHeightDurationSeconds() {
        val video = branchByKind("video")
        val keys = video.propKeys()
        assertTrue("width" in keys)
        assertTrue("height" in keys)
        assertTrue("durationSeconds" in keys)
        // No music/speech-specific fields.
        assertTrue("voice" !in keys, "video must not have voice")
        assertTrue("format" !in keys, "video must not have format")
    }

    @Test fun musicBranchHasDurationSecondsButNotResolution() {
        // Music has no width/height — a regression that copied the
        // image schema would silently expose nonsense knobs.
        val music = branchByKind("music")
        val keys = music.propKeys()
        assertTrue("durationSeconds" in keys)
        assertTrue("width" !in keys, "music must not have width")
        assertTrue("height" !in keys, "music must not have height")
        assertTrue("voice" !in keys, "music must not have voice")
    }

    @Test fun speechBranchHasVoiceFormatSpeedLanguage() {
        // Speech-specific knobs. Pin all 4 of voice / format / speed
        // / language so a refactor dropping any one silently loses
        // user control.
        val speech = branchByKind("speech")
        val keys = speech.propKeys()
        assertTrue("voice" in keys)
        assertTrue("format" in keys)
        assertTrue("speed" in keys)
        assertTrue("language" in keys)
        // No image/video/music-only fields.
        assertTrue("width" !in keys, "speech must not have width")
        assertTrue("height" !in keys, "speech must not have height")
        assertTrue("durationSeconds" !in keys, "speech must not have durationSeconds")
    }

    // ── shared properties (sharedProps) ───────────────────────────

    @Test fun everyBranchExposesSeedProjectIdConsistencyBindingIdsVariantCount() {
        // Pin: sharedProps() applies to EVERY branch. If a refactor
        // accidentally calls sharedProps() only on some branches,
        // the missing variant silently loses fundamental controls
        // (consistencyBindingIds!), corrupting the source-fold
        // workflow without any compile-time signal.
        val sharedKeys = setOf("seed", "projectId", "consistencyBindingIds", "variantCount")
        for (b in branches) {
            val keys = b.propKeys()
            for (key in sharedKeys) {
                assertTrue(
                    key in keys,
                    "branch ${b["properties"]!!.jsonObject["kind"]} must expose '$key' (sharedProps)",
                )
            }
        }
    }

    @Test fun seedIsIntegerType() {
        for (b in branches) {
            val seed = b["properties"]!!.jsonObject["seed"]!!.jsonObject
            assertEquals("integer", seed["type"]!!.jsonPrimitive.content)
        }
    }

    @Test fun variantCountIsIntegerType() {
        // Variant count MUST be integer — float would let the LLM
        // pass 1.5 → ceiling/floor ambiguity at the dispatch layer.
        for (b in branches) {
            val vc = b["properties"]!!.jsonObject["variantCount"]!!.jsonObject
            assertEquals("integer", vc["type"]!!.jsonPrimitive.content)
        }
    }

    @Test fun consistencyBindingIdsIsArrayOfStrings() {
        // Pin: source-node ids are strings; the array's `items` is
        // a string-typed schema. A regression typed as e.g. integer
        // would silently break the LLM's ability to pass
        // consistency bindings.
        for (b in branches) {
            val cb = b["properties"]!!.jsonObject["consistencyBindingIds"]!!.jsonObject
            assertEquals("array", cb["type"]!!.jsonPrimitive.content)
            assertEquals(
                "string",
                cb["items"]!!.jsonObject["type"]!!.jsonPrimitive.content,
                "items.type must be string for SourceNodeId values",
            )
        }
    }

    @Test fun variantCountDescriptionIncludesMaxVariantCountAndPinSyntax() {
        // Pin: the variantCount description tells the LLM both the
        // upper bound (so it doesn't try variantCount=100 and get
        // rejected) AND the recovery action (project_action(kind="pin"))
        // so it knows what to do AFTER picking from variants. Both
        // pieces are user-facing UX through the LLM — losing either
        // means the LLM has to either guess the bound or doesn't
        // know how to commit a chosen variant.
        for (b in branches) {
            val vc = b["properties"]!!.jsonObject["variantCount"]!!.jsonObject
            val desc = vc["description"]!!.jsonPrimitive.content
            assertTrue(
                AigcGenerateTool.MAX_VARIANT_COUNT.toString() in desc,
                "variantCount description must mention MAX_VARIANT_COUNT (${AigcGenerateTool.MAX_VARIANT_COUNT}); got: $desc",
            )
            assertTrue(
                "project_action" in desc,
                "variantCount description must mention pin guidance; got: $desc",
            )
            assertTrue(
                "pin" in desc,
                "variantCount description must mention 'pin' kind; got: $desc",
            )
        }
    }

    @Test fun consistencyBindingIdsDescriptionExplainsThreeStateSemantic() {
        // Pin the kdoc tri-state semantic the LLM relies on:
        // null = auto-fold all consistency nodes; [] = no folding;
        // non-empty = fold only listed. Without this in the
        // description, the LLM defaults to ambiguous behaviour
        // (might submit [] thinking it means "default" → silently
        // disables fold).
        for (b in branches) {
            val cb = b["properties"]!!.jsonObject["consistencyBindingIds"]!!.jsonObject
            val desc = cb["description"]!!.jsonPrimitive.content
            assertTrue(
                "null" in desc,
                "description must explain null semantics; got: $desc",
            )
            assertTrue(
                "[]" in desc || "empty" in desc,
                "description must explain empty-array semantics; got: $desc",
            )
            assertTrue(
                "auto-fold" in desc,
                "description must mention auto-fold default; got: $desc",
            )
        }
    }

    @Test fun everyPropertyHasADescription() {
        // Pin: every per-field description lands in the LLM prompt
        // (per kdoc rationale for hand-building the schema). A
        // refactor that adds a new field without `description`
        // would silently regress prompt clarity. Walk every
        // property in every branch and verify a non-empty
        // description string.
        val allowedNoDesc = setOf("kind") // kind is `const`, no description needed
        for (b in branches) {
            val props = b["properties"]!!.jsonObject
            for ((name, value) in props) {
                if (name in allowedNoDesc) continue
                val desc = value.jsonObject["description"]?.jsonPrimitive?.content
                assertNotNull(
                    desc,
                    "field '$name' in branch ${props["kind"]} must have a description",
                )
                assertTrue(
                    desc.isNotBlank(),
                    "field '$name' in branch ${props["kind"]} must have a non-blank description",
                )
            }
        }
    }

    @Test fun kindIsConstStringPerBranch() {
        // Pin: each kind property is `{type: "string", const: "<kind>"}`
        // — the JSON Schema oneOf discriminator. A regression dropping
        // `const` would let the LLM submit any string for `kind`,
        // making the variants ambiguous.
        val expected = mapOf(
            "image" to "image", "video" to "video", "music" to "music", "speech" to "speech",
        )
        for ((kind, expectedConst) in expected) {
            val b = branchByKind(kind)
            val kindProp = b["properties"]!!.jsonObject["kind"]!!.jsonObject
            assertEquals("string", kindProp["type"]!!.jsonPrimitive.content)
            assertEquals(expectedConst, kindProp["const"]!!.jsonPrimitive.content)
        }
    }
}
