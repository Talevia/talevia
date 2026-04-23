package io.talevia.server

/**
 * Max length for user-supplied text fields (prompts, titles). Anything
 * above this is almost certainly an accident or an attack — the largest
 * Anthropic context window is 200k tokens ≈ 600k chars, and we never
 * want a single request body to approach that. 128KB is the break-point:
 * real prompts stay well under, and an adversary can't stuff unbounded
 * JSON into a single field.
 */
internal const val MAX_TEXT_FIELD_LENGTH = 128 * 1024

/** Max length for free-form short strings like session titles. */
internal const val MAX_TITLE_LENGTH = 256

internal fun requireLength(text: String, max: Int, fieldName: String) {
    require(text.length <= max) { "$fieldName exceeds max length ($max); was ${text.length}" }
}

/**
 * Project / session IDs are used in URL paths and SQL — reject anything
 * that isn't a short, filename-safe string.
 */
internal fun requireReasonableId(value: String, fieldName: String) {
    require(value.isNotEmpty() && value.length <= 128) { "$fieldName must be 1..128 chars" }
    require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
        "$fieldName must be alphanumeric plus -_."
    }
}
