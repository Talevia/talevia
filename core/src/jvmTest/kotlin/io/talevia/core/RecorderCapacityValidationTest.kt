package io.talevia.core

import io.talevia.core.bus.BusEventTraceRecorder
import io.talevia.core.bus.EventBus
import io.talevia.core.permission.PermissionHistoryRecorder
import io.talevia.core.provider.RateLimitHistoryRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Defensive-constructor validation pins for the 3 ring-buffer recorder
 * classes that share the `capacityPerSession` / `capacityPerProvider`
 * pattern:
 *
 *   - [PermissionHistoryRecorder] (`capacityPerSession: Int = 256`)
 *   - [BusEventTraceRecorder] (`capacityPerSession: Int = 256`)
 *   - [RateLimitHistoryRecorder] (`capacityPerProvider: Int = 256`)
 *
 * Cycle 324 added `init { require(capacity > 0) }` to all 3. Pre-cycle
 * the recorders accepted 0 / negative silently:
 *
 *   - capacity = 0 → ring buffer never holds an entry; recorder
 *     becomes a write-only no-op. Detectable only via "why is my
 *     `permission_history` always empty?" mid-debug.
 *   - capacity < 0 → behaviour depended on `ArrayDeque` / list
 *     internals; could throw later at append-time, far from the
 *     misconfiguration source.
 *
 * Same defensive-constructor drift class as cycle 322
 * [PerModelCompactionThreshold] / [PerModelCompactionBudget] and
 * cycle 323 [TpmThrottle]. Audit-pattern principle: every public
 * constructor with user-supplied scalars should fail loud at
 * construction.
 *
 * Implementation note: each recorder spawns a `scope.launch` in its
 * `init` block that subscribes to the bus. The `require(...)`
 * statement runs BEFORE the launch — so a failed construction
 * doesn't even start the coroutine, leaving the scope clean.
 */
class RecorderCapacityValidationTest {

    private fun scope(): CoroutineScope = CoroutineScope(SupervisorJob())

    // ── PermissionHistoryRecorder ──────────────────────────────────

    @Test fun permissionRecorderRejectsZeroCapacity() {
        val s = scope()
        try {
            assertFailsWith<IllegalArgumentException> {
                PermissionHistoryRecorder(
                    bus = EventBus(),
                    scope = s,
                    capacityPerSession = 0,
                )
            }
        } finally {
            s.cancel()
        }
    }

    @Test fun permissionRecorderRejectsNegativeCapacity() {
        val s = scope()
        try {
            assertFailsWith<IllegalArgumentException> {
                PermissionHistoryRecorder(
                    bus = EventBus(),
                    scope = s,
                    capacityPerSession = -1,
                )
            }
        } finally {
            s.cancel()
        }
    }

    @Test fun permissionRecorderAcceptsCapacityOne() {
        // Boundary: 1 is the minimum valid value. Anti-pin against
        // a refactor tightening to `>= 16` or similar production
        // floor. Single-entry ring-buffer is degenerate but
        // legitimate (e.g. test rigs that only inspect the latest
        // permission ask).
        val s = scope()
        try {
            PermissionHistoryRecorder(
                bus = EventBus(),
                scope = s,
                capacityPerSession = 1,
            )
        } finally {
            s.cancel()
        }
    }

    // ── BusEventTraceRecorder ──────────────────────────────────────

    @Test fun busEventTraceRecorderRejectsZeroCapacity() {
        val s = scope()
        try {
            assertFailsWith<IllegalArgumentException> {
                BusEventTraceRecorder(
                    bus = EventBus(),
                    scope = s,
                    capacityPerSession = 0,
                )
            }
        } finally {
            s.cancel()
        }
    }

    @Test fun busEventTraceRecorderRejectsNegativeCapacity() {
        val s = scope()
        try {
            assertFailsWith<IllegalArgumentException> {
                BusEventTraceRecorder(
                    bus = EventBus(),
                    scope = s,
                    capacityPerSession = -100,
                )
            }
        } finally {
            s.cancel()
        }
    }

    @Test fun busEventTraceRecorderAcceptsCapacityOne() {
        val s = scope()
        try {
            BusEventTraceRecorder(
                bus = EventBus(),
                scope = s,
                capacityPerSession = 1,
            )
        } finally {
            s.cancel()
        }
    }

    // ── RateLimitHistoryRecorder ───────────────────────────────────

    @Test fun rateLimitRecorderRejectsZeroCapacity() {
        val s = scope()
        try {
            assertFailsWith<IllegalArgumentException> {
                RateLimitHistoryRecorder(
                    bus = EventBus(),
                    scope = s,
                    capacityPerProvider = 0,
                )
            }
        } finally {
            s.cancel()
        }
    }

    @Test fun rateLimitRecorderRejectsNegativeCapacity() {
        val s = scope()
        try {
            assertFailsWith<IllegalArgumentException> {
                RateLimitHistoryRecorder(
                    bus = EventBus(),
                    scope = s,
                    capacityPerProvider = -1,
                )
            }
        } finally {
            s.cancel()
        }
    }

    @Test fun rateLimitRecorderAcceptsCapacityOne() {
        val s = scope()
        try {
            RateLimitHistoryRecorder(
                bus = EventBus(),
                scope = s,
                capacityPerProvider = 1,
            )
        } finally {
            s.cancel()
        }
    }

    // ── All 3 recorders accept default capacity ───────────────────

    @Test fun allRecordersAcceptDefaultCapacity() {
        // Anti-pin tally: the default 256 is inside the valid range
        // for all 3 recorders. Catches a refactor tightening past
        // the default (e.g. `>= 1024`).
        val s = scope()
        try {
            PermissionHistoryRecorder(bus = EventBus(), scope = s)
            BusEventTraceRecorder(bus = EventBus(), scope = s)
            RateLimitHistoryRecorder(bus = EventBus(), scope = s)
        } finally {
            s.cancel()
        }
    }
}
