package io.talevia.core.tool.builtin.session

import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import kotlinx.serialization.Serializable

/**
 * Schema identifier embedded in every JSON session-export envelope.
 * Bumped on breaking changes so `session_action(action="import")`
 * refuses unknown versions — silent tolerance risks corrupting the
 * target session store when Message / Part schemas evolve.
 *
 * Cycle 145 lifted this out of `ExportSessionTool.FORMAT_VERSION`
 * when the standalone tool folded into
 * `session_action(action="export")`. The new export handler and the
 * existing import handler both reference it at top-level so neither
 * has to import the deleted tool's companion.
 */
const val SESSION_EXPORT_FORMAT_VERSION: String = "talevia-session-export-v1"

/**
 * On-the-wire envelope for `session_action(action="export", format="json")`
 * (and the symmetric `action="import"` consumer). Stable JSON shape —
 * additive changes only; breaking changes bump
 * [SESSION_EXPORT_FORMAT_VERSION].
 */
@Serializable
data class SessionEnvelope(
    val formatVersion: String,
    val session: Session,
    val messages: List<Message>,
    val parts: List<Part>,
)
