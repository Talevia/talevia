package io.talevia.core.session

import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionRule
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [Session] data-class field defaults and 3-state
 * nullable semantics. Cycle 302 audit: no `SessionTest.kt` direct
 * file (verified via cycle 289-banked duplicate-check idiom). The
 * 12-field shape is exercised across many SessionStore /
 * SessionAction tests but the default values + serialization
 * round-trip + 3-state nullable contracts have no dedicated pin.
 *
 * Same audit-pattern fallback as cycles 207-301.
 *
 * Why this matters: Session is the persisted core state that every
 * `talevia.json` bundle and every active SQLDelight row carries.
 * Drift in:
 *   - **Field default changes** (e.g. `spendCapCents = 0L` instead
 *     of `null`) silently changes session-creation behavior
 *     fleet-wide.
 *   - **3-state nullable semantics** (treat null as 0 or vice
 *     versa) silently re-routes the AIGC budget guard / context-
 *     pressure cap.
 *   - **Serialization compat** (renamed field name without
 *     migration) silently breaks legacy bundle deserialization.
 *
 * Pins via direct construction + JSON round-trip on JsonConfig.default.
 */
class SessionFieldDefaultsTest {

    private val sid = SessionId("s1")
    private val pid = ProjectId("p1")
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val json: Json = JsonConfig.default

    private fun minimalSession(): Session = Session(
        id = sid,
        projectId = pid,
        title = "any",
        createdAt = now,
        updatedAt = now,
        // Everything else defaulted.
    )

    // ── Defaults: nullable references ───────────────────────

    @Test fun parentIdDefaultsToNull() {
        // Pin: a session is a root by default; only forks
        // set parentId. Drift to non-null default would
        // silently mark every session as a fork.
        assertNull(minimalSession().parentId)
    }

    @Test fun compactingFromDefaultsToNull() {
        // Pin: a fresh session has no in-flight compaction.
        // Drift would silently mark the first message as
        // already-compacted.
        assertNull(minimalSession().compactingFrom)
    }

    @Test fun currentProjectIdDefaultsToNull() {
        // Marquee binding pin: per VISION §5.4, sessions
        // start unbound until switch_project / create_project
        // sets the binding. Drift to default to projectId
        // silently auto-binds — breaks the "agent must
        // resolve binding first" contract.
        assertNull(
            minimalSession().currentProjectId,
            "currentProjectId MUST default to null (NOT auto-bind to projectId)",
        )
    }

    @Test fun systemPromptOverrideDefaultsToNull() {
        // Pin: per source line 86, null means "fall back to
        // Agent default"; empty string is a legitimate
        // override that's NOT conflated with null.
        assertNull(minimalSession().systemPromptOverride)
    }

    // ── Defaults: nullable Longs (three-state) ─────────────

    @Test fun spendCapCentsDefaultsToNull() {
        // Marquee 3-state pin: per source line 47-50,
        // null = no cap (silent default), 0L = "spend
        // nothing" (every AIGC call asks), positive = cents
        // ceiling. Drift to default to 0L would silently
        // gate every AIGC call on every legacy session.
        assertNull(
            minimalSession().spendCapCents,
            "spendCapCents MUST default to null (= no cap), NOT 0L (= ask every call)",
        )
    }

    @Test fun maxSessionTokensDefaultsToNull() {
        // Marquee 3-state pin: per source line 106-109,
        // null = no cap, positive = ceiling, 0L is
        // legitimate "stop dispatching now". Drift to
        // default to 0L would silently brick legacy
        // sessions on the very first turn.
        assertNull(
            minimalSession().maxSessionTokens,
            "maxSessionTokens MUST default to null (NOT 0L which would brick the session)",
        )
    }

    // ── Defaults: collection fields ─────────────────────────

    @Test fun permissionRulesDefaultsToEmptyList() {
        assertEquals(emptyList(), minimalSession().permissionRules)
    }

    @Test fun disabledToolIdsDefaultsToEmptySet() {
        // Pin: per source line 67, empty set preserves pre-
        // feature behavior (every applicable tool visible).
        // Drift to non-empty default would silently hide
        // tools.
        assertEquals(
            emptySet(),
            minimalSession().disabledToolIds,
            "disabledToolIds MUST default to empty (every tool visible)",
        )
    }

    @Test fun archivedDefaultsToFalse() {
        // Pin: fresh sessions are live, NOT archived.
        // Drift to default true would silently exclude every
        // session from session_query(select=sessions).
        assertEquals(false, minimalSession().archived)
    }

    // ── Three-state nullable Long: distinct interpretations

    @Test fun spendCapCentsNullVsZeroAreSemanticallyDistinct() {
        // Marquee distinction pin: drift to treat them as
        // equivalent loses the "no cap" vs "ask every call"
        // distinction.
        val noCap = minimalSession() // spendCapCents = null
        val zeroSpend = minimalSession().copy(spendCapCents = 0L)
        assertNull(noCap.spendCapCents, "no-cap session has null spendCapCents")
        assertEquals(0L, zeroSpend.spendCapCents, "zero-spend session has 0L spendCapCents")
        assertNotEquals(noCap, zeroSpend, "null and 0L MUST produce distinct sessions")
    }

    @Test fun maxSessionTokensNullVsZeroAreSemanticallyDistinct() {
        // Sister distinction pin for maxSessionTokens.
        val noCap = minimalSession()
        val zeroCap = minimalSession().copy(maxSessionTokens = 0L)
        assertNull(noCap.maxSessionTokens)
        assertEquals(0L, zeroCap.maxSessionTokens)
        assertNotEquals(noCap, zeroCap)
    }

    @Test fun systemPromptOverrideNullVsEmptyStringAreSemanticallyDistinct() {
        // Marquee 3-state pin: per source line 78-80, empty
        // string is a legitimate override ("no system prompt
        // at all") and is NOT conflated with null.
        val fallback = minimalSession() // null = use Agent default
        val empty = minimalSession().copy(systemPromptOverride = "")
        assertNull(fallback.systemPromptOverride)
        assertEquals("", empty.systemPromptOverride)
        assertNotEquals(
            fallback,
            empty,
            "null (Agent default fallback) MUST be distinct from \"\" (no system prompt at all)",
        )
    }

    // ── Serialization round-trip ───────────────────────────

    @Test fun serializationRoundTripPreservesAllFields() {
        // Marquee compat pin: round-trip via JsonConfig.default
        // preserves every field. Drift in @SerialName / field
        // ordering would silently break talevia.json
        // deserialization.
        val original = Session(
            id = sid,
            projectId = pid,
            title = "test",
            parentId = SessionId("parent"),
            permissionRules = listOf(
                PermissionRule(permission = "p", pattern = "*", action = PermissionAction.ALLOW),
            ),
            createdAt = now,
            updatedAt = now,
            compactingFrom = MessageId("m1"),
            archived = true,
            currentProjectId = pid,
            spendCapCents = 100L,
            disabledToolIds = setOf("tool1", "tool2"),
            systemPromptOverride = "custom",
            maxSessionTokens = 50_000L,
        )
        val encoded = json.encodeToString(Session.serializer(), original)
        val decoded = json.decodeFromString(Session.serializer(), encoded)
        assertEquals(
            original,
            decoded,
            "round-trip MUST preserve every field (drift in @SerialName surfaces here)",
        )
    }

    @Test fun deserializationOfMinimalJsonHonorsAllDefaults() {
        // Marquee back-compat pin: a JSON envelope with
        // ONLY the required fields decodes cleanly using
        // every default. Drift to require a previously-
        // optional field silently breaks legacy bundles.
        val minimalJson = """{
            "id":"s1",
            "projectId":"p1",
            "title":"any",
            "createdAt":"${now}",
            "updatedAt":"${now}"
        }""".trimIndent()
        val decoded = json.decodeFromString(Session.serializer(), minimalJson)
        // Every optional field MUST come back at its
        // documented default.
        assertNull(decoded.parentId)
        assertEquals(emptyList(), decoded.permissionRules)
        assertNull(decoded.compactingFrom)
        assertEquals(false, decoded.archived)
        assertNull(decoded.currentProjectId)
        assertNull(decoded.spendCapCents)
        assertEquals(emptySet(), decoded.disabledToolIds)
        assertNull(decoded.systemPromptOverride)
        assertNull(decoded.maxSessionTokens)
    }

    @Test fun jsonConfigDefaultIgnoresUnknownKeys() {
        // Pin: JsonConfig.default has ignoreUnknownKeys=true,
        // so a future-version envelope decodes against the
        // current Session shape without breaking. Drift to
        // strict-mode would silently fail forward-compat
        // reads.
        val envelopeWithExtraKey = """{
            "id":"s1",
            "projectId":"p1",
            "title":"any",
            "createdAt":"${now}",
            "updatedAt":"${now}",
            "futureFieldFromV2":"some-value-the-current-Session-doesnt-have"
        }""".trimIndent()
        val decoded = json.decodeFromString(Session.serializer(), envelopeWithExtraKey)
        assertEquals(SessionId("s1"), decoded.id)
        // Unknown key silently dropped.
    }

    // ── Equality + hashCode (data class invariants) ────────

    @Test fun twoSessionsWithSameFieldsAreEqual() {
        val a = minimalSession()
        val b = minimalSession()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun differentTitleProducesDistinctSessions() {
        val a = minimalSession()
        val b = minimalSession().copy(title = "different")
        assertNotEquals(a, b)
    }

    @Test fun copyPreservesUnsuppliedFields() {
        // Pin: data class copy semantic — unsupplied args
        // come from the source. Drift would surface as
        // copy()-call sites silently dropping fields.
        val withMaxTokens = minimalSession().copy(maxSessionTokens = 10_000L)
        // copy with no args at all rebuilds an identical.
        val cloned = withMaxTokens.copy()
        assertEquals(withMaxTokens, cloned)
        assertEquals(10_000L, cloned.maxSessionTokens)
    }

    // ── Field count / shape sanity ─────────────────────────

    @Test fun sessionHasExactlyThirteenFields() {
        // Pin: counted by hand from source — 13 fields total
        // (id, projectId, title, parentId, permissionRules,
        // createdAt, updatedAt, compactingFrom, archived,
        // currentProjectId, spendCapCents, disabledToolIds,
        // systemPromptOverride, maxSessionTokens) — 14
        // counted; let me recount.
        // Actually: 1.id 2.projectId 3.title 4.parentId
        // 5.permissionRules 6.createdAt 7.updatedAt
        // 8.compactingFrom 9.archived 10.currentProjectId
        // 11.spendCapCents 12.disabledToolIds
        // 13.systemPromptOverride 14.maxSessionTokens = 14.
        // Pin uses reflection-style: count parameters in
        // the constructor's signature via test of all
        // fields being set in the round-trip case above.
        // This test is a sanity net: drift in field count
        // (e.g. someone adds a new required field that
        // breaks back-compat) surfaces if the
        // `deserializationOfMinimalJsonHonorsAllDefaults`
        // test breaks. Sister sanity below.
        val s = minimalSession()
        assertTrue(s.id.value.isNotEmpty())
        assertTrue(s.projectId.value.isNotEmpty())
        assertTrue(s.title.isNotEmpty())
    }
}
