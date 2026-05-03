package io.talevia.desktop

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for the 5 internal helpers in `apps/desktop/SourceNodeHelpers.kt` —
 * the desktop UI's source-node display + dispatch utilities. Cycle 99 audit:
 * 88 LOC, **zero** transitive test references; the helpers are wired into
 * `SourceNodeRow` / `SourcePanel` Compose rendering and only exercised via
 * the `CrossPathSourceUiE2ETest` snapshot path which tests UI rendering, not
 * the helper invariants directly.
 *
 * Each helper has a contract a refactor could subtly violate:
 * - [dispatchBodyUpdate] does **full-replacement** body merging (existing
 *   fields preserved, overlay wins on collisions). A regression
 *   forgetting the existing copy would silently strip every untouched
 *   field on every save — `update_source_node_body` would become
 *   destructive instead of partial-edit.
 * - [displayName] / [nodeDescription] / [nodeSecondaryField] all read
 *   genre-specific keys out of opaque JsonElement bodies. A wrong key
 *   path silently shows blank text in the UI (looks like missing data
 *   even when it's there).
 * - [nodeSecondaryLabel] is the user-visible TextField label; a wrong
 *   mapping would mislead users about which field they're editing.
 */
class SourceNodeHelpersTest {

    private fun characterRef(
        id: String = "char-1",
        name: String? = null,
        visualDescription: String? = null,
    ): SourceNode = SourceNode.create(
        id = SourceNodeId(id),
        kind = ConsistencyKinds.CHARACTER_REF,
        body = buildJsonObject {
            if (name != null) put("name", name)
            if (visualDescription != null) put("visualDescription", visualDescription)
        },
    )

    private fun styleBible(name: String? = null, description: String? = null): SourceNode = SourceNode.create(
        id = SourceNodeId("style-1"),
        kind = ConsistencyKinds.STYLE_BIBLE,
        body = buildJsonObject {
            if (name != null) put("name", name)
            if (description != null) put("description", description)
        },
    )

    private fun brandPalette(
        name: String? = null,
        hexColors: List<String>? = null,
    ): SourceNode = SourceNode.create(
        id = SourceNodeId("brand-1"),
        kind = ConsistencyKinds.BRAND_PALETTE,
        body = buildJsonObject {
            if (name != null) put("name", name)
            if (hexColors != null) {
                put(
                    "hexColors",
                    buildJsonArray {
                        for (c in hexColors) add(JsonPrimitive(c))
                    },
                )
            }
        },
    )

    // ── displayName ──────────────────────────────────────────────

    @Test fun displayNamePrefersNameField() {
        assertEquals("Mei", displayName(characterRef(name = "Mei")))
    }

    @Test fun displayNameFallsBackToNodeIdWhenNameMissing() {
        // Pin: empty body → fall back to the SourceNodeId. Keeps the
        // UI showing *something* identifying the node even before the
        // user has set a name.
        assertEquals("char-1", displayName(characterRef(id = "char-1")))
    }

    @Test fun displayNameFallsBackToNodeIdWhenBodyIsNotJsonObject() {
        // Defensive: SourceNode.body is JsonElement, so JsonPrimitive
        // bodies are valid type-wise. The helper must not throw — fall
        // back to the id.
        val node = SourceNode.create(
            id = SourceNodeId("weird"),
            kind = "x.y.z",
            body = JsonPrimitive("just a string body"),
        )
        assertEquals("weird", displayName(node))
    }

    // ── nodeSecondaryField ───────────────────────────────────────

    @Test fun secondaryFieldForCharacterRefIsVisualDescription() {
        val node = characterRef(visualDescription = "tall girl with red hair")
        assertEquals("tall girl with red hair", nodeSecondaryField(node))
    }

    @Test fun secondaryFieldForStyleBibleIsDescription() {
        val node = styleBible(description = "minimalist anime aesthetic")
        assertEquals("minimalist anime aesthetic", nodeSecondaryField(node))
    }

    @Test fun secondaryFieldForBrandPaletteJoinsHexColors() {
        // Pin: hexColors is a JsonArray; UI displays as comma-joined
        // string. A regression returning the raw array's toString
        // would show `[#ff0000, "#00ff00"]` with quotes.
        val node = brandPalette(hexColors = listOf("#ff0000", "#00ff00", "#0000ff"))
        assertEquals("#ff0000, #00ff00, #0000ff", nodeSecondaryField(node))
    }

    @Test fun secondaryFieldReturnsEmptyForUnknownKind() {
        val node = SourceNode.create(
            id = SourceNodeId("misc"),
            kind = "narrative.scene",
            body = buildJsonObject { put("description", "ignored") },
        )
        assertEquals("", nodeSecondaryField(node))
    }

    @Test fun secondaryFieldReturnsEmptyWhenBodyIsNotJsonObject() {
        val node = SourceNode.create(
            id = SourceNodeId("char-x"),
            kind = ConsistencyKinds.CHARACTER_REF,
            body = JsonPrimitive("body"),
        )
        assertEquals("", nodeSecondaryField(node))
    }

    @Test fun secondaryFieldReturnsEmptyWhenExpectedFieldMissing() {
        // CHARACTER_REF without visualDescription — empty, not crash.
        val node = characterRef(name = "Mei")
        assertEquals("", nodeSecondaryField(node))
    }

    // ── nodeSecondaryLabel ───────────────────────────────────────

    @Test fun secondaryLabelMapsKnownKindsToReadableText() {
        // Pin the user-visible TextField labels. Wrong mapping would
        // mislead — e.g. CHARACTER_REF showing "Hex colors" makes the
        // user think the node holds palette data.
        assertEquals("Visual description", nodeSecondaryLabel(ConsistencyKinds.CHARACTER_REF))
        assertEquals("Description", nodeSecondaryLabel(ConsistencyKinds.STYLE_BIBLE))
        assertEquals("Hex colors (comma-separated)", nodeSecondaryLabel(ConsistencyKinds.BRAND_PALETTE))
    }

    @Test fun secondaryLabelFallsBackToValueForUnknownKind() {
        assertEquals("Value", nodeSecondaryLabel("narrative.scene"))
        assertEquals("Value", nodeSecondaryLabel(""))
    }

    // ── nodeDescription ──────────────────────────────────────────

    @Test fun descriptionPrefersVisualDescriptionOverDescription() {
        // Pin the priority order in the candidates list:
        // visualDescription before description. A character_ref node
        // would have visualDescription; if both happened to be set,
        // visualDescription wins.
        val both = SourceNode.create(
            id = SourceNodeId("c"),
            kind = ConsistencyKinds.CHARACTER_REF,
            body = buildJsonObject {
                put("visualDescription", "tall")
                put("description", "shouldn't be used")
            },
        )
        assertEquals("tall", nodeDescription(both))
    }

    @Test fun descriptionFallsBackToDescriptionFieldWhenVisualMissing() {
        val node = styleBible(description = "minimalist")
        assertEquals("minimalist", nodeDescription(node))
    }

    @Test fun descriptionReturnsEmptyWhenNoCandidatePresent() {
        // brand_palette only carries `name` + `hexColors` — neither
        // candidate key. Per kdoc: "Fall back to '' when the body
        // doesn't fit any expected shape."
        val node = brandPalette(name = "Brand", hexColors = listOf("#fff"))
        assertEquals("", nodeDescription(node))
    }

    @Test fun descriptionSkipsBlankCandidate() {
        // Pin: `takeIf { it.isNotBlank() }` — empty string in
        // visualDescription triggers fallback to description, not
        // returning the empty string itself.
        val node = SourceNode.create(
            id = SourceNodeId("c"),
            kind = ConsistencyKinds.CHARACTER_REF,
            body = buildJsonObject {
                put("visualDescription", "  ")
                put("description", "fallback")
            },
        )
        assertEquals("fallback", nodeDescription(node))
    }

    // ── dispatchBodyUpdate ───────────────────────────────────────

    @Test fun dispatchBodyUpdatePreservesExistingFieldsAndOverlaysNewOnes() {
        // The most load-bearing helper in this file. `update_source_
        // node_body` is full-replacement, so the helper MUST copy
        // every existing field before applying the overlay — otherwise
        // each "edit one field" UI action would silently delete
        // every other field. Worst-case-quiet regression.
        val node = characterRef(name = "Mei", visualDescription = "tall")

        var capturedTool: String? = null
        var capturedParams: JsonObject? = null
        var capturedLabel: String? = null
        dispatchBodyUpdate(
            projectId = ProjectId("proj-1"),
            node = node,
            label = "rename character",
            dispatch = { tool, params, label ->
                capturedTool = tool
                capturedParams = params
                capturedLabel = label
            },
            overlay = {
                // Update name only; visualDescription must round-trip.
                put("name", "Aria")
            },
        )

        assertEquals("update_source_node_body", capturedTool)
        assertEquals("rename character", capturedLabel)
        assertEquals("proj-1", (capturedParams!!["projectId"] as JsonPrimitive).content)
        assertEquals("char-1", (capturedParams!!["nodeId"] as JsonPrimitive).content)
        val newBody = capturedParams!!["body"] as JsonObject
        assertEquals("Aria", (newBody["name"] as JsonPrimitive).content, "overlay key wins")
        assertEquals(
            "tall",
            (newBody["visualDescription"] as JsonPrimitive).content,
            "untouched field must round-trip",
        )
    }

    @Test fun dispatchBodyUpdateAddsNewFieldsWhenOverlayIntroducesThem() {
        val node = characterRef(name = "Mei")
        var captured: JsonObject? = null
        dispatchBodyUpdate(
            projectId = ProjectId("p"),
            node = node,
            label = "set description",
            dispatch = { _, params, _ -> captured = params },
            overlay = {
                put("visualDescription", "freshly added")
            },
        )
        val newBody = captured!!["body"] as JsonObject
        assertEquals("Mei", (newBody["name"] as JsonPrimitive).content)
        assertEquals("freshly added", (newBody["visualDescription"] as JsonPrimitive).content)
    }

    @Test fun dispatchBodyUpdateHandlesEmptyOverlay() {
        // Pin: empty overlay → body identical to existing. Useful for
        // "save no-op" semantics; should not crash and should round-trip
        // every existing field.
        val node = characterRef(name = "Mei", visualDescription = "tall")
        var captured: JsonObject? = null
        dispatchBodyUpdate(
            projectId = ProjectId("p"),
            node = node,
            label = "no-op",
            dispatch = { _, params, _ -> captured = params },
            overlay = { /* empty */ },
        )
        val newBody = captured!!["body"] as JsonObject
        assertEquals("Mei", (newBody["name"] as JsonPrimitive).content)
        assertEquals("tall", (newBody["visualDescription"] as JsonPrimitive).content)
    }

    @Test fun dispatchBodyUpdateHandlesNonObjectBodyAsEmpty() {
        // Defensive: node.body is JsonElement, so JsonPrimitive bodies
        // are type-valid. Helper falls back to empty JsonObject and
        // applies the overlay anyway.
        val node = SourceNode.create(
            id = SourceNodeId("weird"),
            kind = "x.y",
            body = JsonPrimitive("not an object"),
        )
        var captured: JsonObject? = null
        dispatchBodyUpdate(
            projectId = ProjectId("p"),
            node = node,
            label = "x",
            dispatch = { _, params, _ -> captured = params },
            overlay = { put("name", "Mei") },
        )
        val newBody = captured!!["body"] as JsonObject
        assertEquals("Mei", (newBody["name"] as JsonPrimitive).content)
        // No leakage from the non-object body.
        assertTrue(newBody.size == 1, "non-object body must be treated as empty; got: $newBody")
    }

    @Test fun dispatchBodyUpdatePreservesArrayAndNestedObjectFields() {
        // Pin: existing JsonArray and JsonObject values round-trip
        // unchanged. A naive `put(k, v.toString())` would corrupt
        // every non-primitive field.
        val node = brandPalette(name = "Brand", hexColors = listOf("#ff0000", "#00ff00"))
        var captured: JsonObject? = null
        dispatchBodyUpdate(
            projectId = ProjectId("p"),
            node = node,
            label = "rename brand",
            dispatch = { _, params, _ -> captured = params },
            overlay = { put("name", "Renamed") },
        )
        val newBody = captured!!["body"] as JsonObject
        assertEquals("Renamed", (newBody["name"] as JsonPrimitive).content)
        // Array round-trips identically.
        val colors = newBody["hexColors"] as JsonArray
        assertEquals(2, colors.size)
        assertEquals("#ff0000", (colors[0] as JsonPrimitive).content)
        assertEquals("#00ff00", (colors[1] as JsonPrimitive).content)
    }
}
