package io.talevia.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
object JsonConfig {
    /** Single canonical [Json] instance — discriminator name pinned for forward compatibility. */
    val default: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }

    /**
     * Pretty-printed variant for git-friendly on-disk artefacts (talevia.json bundle file).
     * Same shape as [default] otherwise — class discriminator, unknown-key tolerance, default omission.
     */
    val prettyPrint: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}
