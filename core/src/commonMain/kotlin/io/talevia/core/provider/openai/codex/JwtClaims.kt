package io.talevia.core.provider.openai.codex

import io.talevia.core.JsonConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.decodeBase64

/**
 * Minimal JWT payload parser — base64url-nopad-decode segment 2, parse JSON. We
 * deliberately do **not** verify the signature: the official Codex CLI doesn't
 * either, and we accept the same security model (TLS + opaque token from the
 * issuer's own token endpoint).
 */
internal object JwtClaims {

    /**
     * Returns the parsed JWT payload as a [JsonObject], or null if the token is
     * malformed.
     */
    fun parsePayload(jwt: String): JsonObject? {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        // okio's decodeBase64 accepts both standard and URL-safe alphabets and
        // tolerates missing padding, so we don't need to normalise the segment.
        val bytes = parts[1].decodeBase64() ?: return null
        return runCatching { JsonConfig.default.parseToJsonElement(bytes.utf8()).jsonObject }.getOrNull()
    }

    /** `exp` claim (Unix epoch seconds), or null if absent / unparseable. */
    fun exp(jwt: String): Long? =
        parsePayload(jwt)?.get("exp")?.jsonPrimitive?.longOrNull

    /**
     * `chatgpt_account_id` from the custom `https://api.openai.com/auth` claim
     * object. This is the value that gets sent as the `ChatGPT-Account-ID`
     * header on Responses API calls.
     */
    fun chatgptAccountId(idToken: String): String? {
        val payload = parsePayload(idToken) ?: return null
        val auth = payload["https://api.openai.com/auth"] as? JsonObject ?: return null
        return (auth["chatgpt_account_id"] as? JsonPrimitive)?.contentOrNull
    }
}
