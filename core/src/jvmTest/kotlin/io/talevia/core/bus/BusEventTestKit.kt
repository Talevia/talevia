package io.talevia.core.bus

import app.cash.turbine.test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Subscribe to [T] events on this [EventBus], run [trigger], await the next
 * matching emission, run [assert] on it. Wraps the cycle-148/155 Turbine
 * pattern that several test suites had to re-derive:
 *
 * ```
 * subscribe<T>().test {
 *   trigger()
 *   val event = awaitItem()
 *   assert(event)
 *   cancelAndIgnoreRemainingEvents()
 * }
 * ```
 *
 * The MutableSharedFlow + Dispatchers.Default fanout means the subscriber
 * must be registered before any publish lands; tests that call `publish`
 * first then `awaitItem()` race and flake non-deterministically (cycle
 * 148 PAIN_POINTS `ios-swift-validation-gap` recorded the historical recipe;
 * cycle 155's `SessionFullCapTest` had to inline the pattern again).
 *
 * The 5 s default [timeout] matches the existing per-test timeouts; tests
 * that need tighter / looser bounds pass an explicit value.
 */
internal suspend inline fun <reified T : BusEvent> EventBus.publishAndAwait(
    timeout: Duration = 5.seconds,
    crossinline trigger: suspend () -> Unit,
    crossinline assert: suspend (T) -> Unit,
) {
    subscribe<T>().test(timeout = timeout) {
        trigger()
        val event = awaitItem()
        assert(event)
        cancelAndIgnoreRemainingEvents()
    }
}
