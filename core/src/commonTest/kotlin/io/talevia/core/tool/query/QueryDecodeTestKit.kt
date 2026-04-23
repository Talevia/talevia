package io.talevia.core.tool.query

import io.talevia.core.JsonConfig
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * Decode the `Output.rows` [JsonArray] into a typed list using the passed
 * row [serializer]. Collapses the `JsonConfig.default.decodeFromJsonElement(
 * ListSerializer(XRow.serializer()), out.rows)` three-liner the query-tool
 * tests used to repeat in ~80 call sites.
 *
 * Canonical decode path — same [JsonConfig.default] the production wire
 * uses — so tests fail identically to what a real consumer would see.
 */
fun <T> JsonArray.decodeRowsAs(serializer: KSerializer<T>): List<T> =
    JsonConfig.default.decodeFromJsonElement(ListSerializer(serializer), this)

/**
 * Decode [rows] using whatever serializer the dispatcher publishes for
 * [select] via [QueryDispatcher.rowSerializerFor]. Registry-keyed variant
 * of [decodeRowsAs] — useful for smoke tests that exercise every select
 * on a dispatcher without re-importing each concrete row type.
 *
 * Returns `List<Any?>` because the registry is existential; callers that
 * know the concrete row type should prefer [decodeRowsAs] with the
 * explicit serializer for compile-time safety.
 */
@Suppress("UNCHECKED_CAST")
fun decodeRowsByRegistry(
    dispatcher: QueryDispatcher<*, *>,
    select: String,
    rows: JsonArray,
): List<Any?> {
    val rowSerializer = dispatcher.rowSerializerFor(select) as KSerializer<Any?>
    return rows.decodeRowsAs(rowSerializer)
}
