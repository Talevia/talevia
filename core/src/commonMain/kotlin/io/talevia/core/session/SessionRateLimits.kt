package io.talevia.core.session

import kotlinx.serialization.Serializable

/**
 * **Placeholder** — carries the shape of future per-session rate limits on
 * AIGC usage without wiring any enforcement yet. Landed as a debt record in
 * 2026-04-21 (decision
 * `docs/decisions/2026-04-21-rate-limit-aigc-per-session.md`).
 *
 * The trigger conditions that would warrant implementing enforcement:
 *
 * 1. **Per-session cost ceiling** — stop dispatching AIGC tools once
 *    `sum(Assistant.cost.usd)` for the session exceeds [maxCostPerSessionUsd].
 * 2. **Per-minute call rate** — cap at [maxCallsPerMinute] dispatches of any
 *    AIGC-emitting tool (`generate_image` / `generate_music` / `generate_video`
 *    / `synthesize_speech` / `upscale_asset`) in a rolling one-minute window.
 * 3. **Hard call ceiling** — fail every AIGC dispatch after [maxTotalCalls]
 *    have landed on this session regardless of time.
 *
 * All three default to `null` = unlimited. Compose roots construct the default
 * instance implicitly (`SessionRateLimits.UNLIMITED`) until the debt is paid.
 *
 * **Not yet wired.** No tool reads this class; no `BusEvent` announces a
 * trip; no `Agent` path refuses dispatch. Future cycle:
 *  - Plumb through `Agent` constructor (next to `compactor` / `retryPolicy`).
 *  - Track running totals in a `SessionRateTracker` that consumes
 *    `AgentRunStateChanged` + `StepFinish` tokens + `ToolState.Completed`
 *    cost events.
 *  - Fail dispatch with a `PermissionDecision.Denied("rate limit …")` when
 *    any trigger fires.
 *  - Emit a new `BusEvent.SessionRateLimitTripped(sessionId, which, ...)`
 *    so UI can render "you've used $8.23 of your $10 budget".
 *
 * The class is a wire contract — its defaults shape the eventual API, and
 * having it land now means future cycles don't have to re-think field names
 * (or burn a serialization-migration cycle renaming them). Kept in
 * `commonMain` with zero platform deps.
 */
@Serializable
data class SessionRateLimits(
    /** Max sum of `Assistant.cost.usd` across the session before refusing AIGC. `null` = no cap. */
    val maxCostPerSessionUsd: Double? = null,
    /** Max AIGC tool dispatches per rolling 60s window. `null` = no cap. */
    val maxCallsPerMinute: Int? = null,
    /** Max AIGC tool dispatches total for the session's lifetime. `null` = no cap. */
    val maxTotalCalls: Int? = null,
) {
    /** True iff every cap is `null` — no enforcement possible in this config. */
    val isUnlimited: Boolean
        get() = maxCostPerSessionUsd == null && maxCallsPerMinute == null && maxTotalCalls == null

    companion object {
        /** Default config — no caps. What every session gets until the debt is paid. */
        val UNLIMITED: SessionRateLimits = SessionRateLimits()
    }
}
