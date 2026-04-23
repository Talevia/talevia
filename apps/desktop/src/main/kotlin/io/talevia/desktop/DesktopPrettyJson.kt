package io.talevia.desktop

import io.talevia.core.JsonConfig
import kotlinx.serialization.json.Json

/**
 * Shared pretty-printing [Json] for Desktop panels. Centralised by
 * `debt-centralise-pretty-json-desktop` (2026-04-23) — `LockfilePanel`,
 * `TimelineClipRow`, and `SourceNodeRow` each previously held their own
 * near-identical instance (`PrettyJson` / `TimelinePrettyJson` /
 * `SourcePrettyJson`), with one of them missing the `JsonConfig.default`
 * base. That was harmless at the time (the two `JsonObject` call sites
 * don't care about `classDiscriminator`) but it was drift waiting to
 * surprise the next reader.
 *
 * Base: [JsonConfig.default] (`classDiscriminator = "type"` /
 * `ignoreUnknownKeys = true`) — required by the `Clip` serializer call
 * in `TimelineClipRow` since `Clip`'s subtypes are polymorphic. Pure
 * `JsonElement` / `JsonObject` call sites are unaffected by those
 * flags, so the unification is behaviour-preserving for the
 * Lockfile / SourceNode printers too.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal val DesktopPrettyJson: Json = Json(JsonConfig.default) {
    prettyPrint = true
    prettyPrintIndent = "  "
}
