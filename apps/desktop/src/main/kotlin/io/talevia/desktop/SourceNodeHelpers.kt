package io.talevia.desktop

import io.talevia.core.ProjectId
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `update_source_node_body` is full-replacement, not partial-patch: feed
 * it the node's current body with the edited fields overlaid. [overlay]
 * runs after every existing field of `node.body` has been copied into
 * the builder, so callers only have to describe what changed —
 * everything else round-trips unchanged.
 */
internal fun dispatchBodyUpdate(
    projectId: ProjectId,
    node: SourceNode,
    label: String,
    dispatch: (String, JsonObject, String) -> Unit,
    overlay: JsonObjectBuilder.() -> Unit,
) {
    val existing = node.body as? JsonObject ?: JsonObject(emptyMap())
    val newBody = buildJsonObject {
        existing.forEach { (k, v) -> put(k, v) }
        overlay()
    }
    dispatch(
        "update_source_node_body",
        buildJsonObject {
            put("projectId", projectId.value)
            put("nodeId", node.id.value)
            put("body", newBody)
        },
        label,
    )
}

/** Display-friendly name for a source node — uses its body `name` field when present. */
internal fun displayName(node: SourceNode): String {
    val obj = node.body as? JsonObject
    val name = (obj?.get("name") as? JsonPrimitive)?.content
    return name ?: node.id.value
}

/**
 * Primary editable secondary-field value for the inline edit form.
 * Returns a comma-joined string for array fields (brand_palette hexColors).
 */
internal fun nodeSecondaryField(node: SourceNode): String {
    val obj = node.body as? JsonObject ?: return ""
    return when (node.kind) {
        ConsistencyKinds.CHARACTER_REF -> (obj["visualDescription"] as? JsonPrimitive)?.content.orEmpty()
        ConsistencyKinds.STYLE_BIBLE -> (obj["description"] as? JsonPrimitive)?.content.orEmpty()
        ConsistencyKinds.BRAND_PALETTE ->
            (obj["hexColors"] as? JsonArray)?.joinToString(", ") { (it as? JsonPrimitive)?.content ?: "" }.orEmpty()
        else -> ""
    }
}

/** Label for the secondary TextField in the inline edit form. */
internal fun nodeSecondaryLabel(kind: String): String = when (kind) {
    ConsistencyKinds.CHARACTER_REF -> "Visual description"
    ConsistencyKinds.STYLE_BIBLE -> "Description"
    ConsistencyKinds.BRAND_PALETTE -> "Hex colors (comma-separated)"
    else -> "Value"
}

/**
 * Short descriptive blurb pulled from a node body — used as the tail of
 * "Generate" button prompts. Different kinds keep the relevant field
 * under different keys (character_ref: visualDescription; style_bible:
 * description; brand_palette: name only). Fall back to "" when the body
 * doesn't fit any expected shape.
 */
internal fun nodeDescription(node: SourceNode): String {
    val obj = node.body as? JsonObject ?: return ""
    val candidates = listOf("visualDescription", "description")
    for (key in candidates) {
        val v = (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        if (v != null) return v
    }
    return ""
}
