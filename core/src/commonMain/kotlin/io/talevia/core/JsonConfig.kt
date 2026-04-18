package io.talevia.core

import kotlinx.serialization.json.Json

object JsonConfig {
    /** Single canonical [Json] instance — discriminator name pinned for forward compatibility. */
    val default: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }
}
