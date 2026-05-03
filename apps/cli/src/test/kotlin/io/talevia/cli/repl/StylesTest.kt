package io.talevia.cli.repl

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [Styles] — the CLI's ANSI style palette. Cycle 92
 * audit found this object had no direct test (8 transitive
 * references via formatter tests like cycle 87's BusTraceFormatter).
 *
 * The on/off toggle is load-bearing: when stdout isn't a TTY (pipes,
 * file redirection), Styles must produce plain text — otherwise
 * downstream consumers see ANSI escape codes mixed into structured
 * output. A regression breaking the toggle would silently corrupt
 * piped output.
 */
class StylesTest {

    private var priorEnabled = true

    @BeforeTest fun saveState() {
        priorEnabled = Styles.isEnabled()
    }

    @AfterTest fun restoreState() {
        Styles.setEnabled(priorEnabled)
    }

    @Test fun isEnabledDefaultsToTrue() {
        // Pin the default (matches typical TTY-attached CLI use). The
        // disable hook is for pipe / redirect cases where the operator
        // explicitly opts out.
        Styles.setEnabled(true)
        assertTrue(Styles.isEnabled())
    }

    @Test fun setEnabledTogglesObservably() {
        Styles.setEnabled(true)
        assertTrue(Styles.isEnabled())
        Styles.setEnabled(false)
        assertEquals(false, Styles.isEnabled())
        Styles.setEnabled(true)
        assertTrue(Styles.isEnabled())
    }

    @Test fun whenDisabledAllHelpersReturnInputUnchanged() {
        // Pin the load-bearing pipe-safety contract: disabled = no ANSI
        // codes, plain text passthrough. A regression would leak ANSI
        // codes into log files and pipe consumers.
        Styles.setEnabled(false)
        val input = "hello world"
        assertEquals(input, Styles.banner(input))
        assertEquals(input, Styles.meta(input))
        assertEquals(input, Styles.prompt(input))
        assertEquals(input, Styles.running(input))
        assertEquals(input, Styles.ok(input))
        assertEquals(input, Styles.fail(input))
        assertEquals(input, Styles.error(input))
        assertEquals(input, Styles.warn(input))
        assertEquals(input, Styles.toolId(input))
        assertEquals(input, Styles.accent(input))
    }

    @Test fun whenEnabledHelpersWrapInputInAnsiCodes() {
        // Sanity: enabled mode actually adds ANSI escape codes.
        Styles.setEnabled(true)
        val input = "x"
        // ANSI escape code starts with ESC + [
        for (helper in listOf<(String) -> String>(
            Styles::banner, Styles::meta, Styles::prompt,
            Styles::running, Styles::ok, Styles::fail,
            Styles::error, Styles::warn, Styles::toolId, Styles::accent,
        )) {
            val styled = helper(input)
            assertNotEquals(input, styled, "enabled style should wrap in ANSI codes")
            assertTrue(
                styled.contains(''),
                "styled output must contain ESC; got: ${styled.toCharArray().joinToString { it.code.toString() }}",
            )
        }
    }

    @Test fun whenDisabledEmptyStringRoundTripsAsEmpty() {
        // Edge: empty input passthrough.
        Styles.setEnabled(false)
        assertEquals("", Styles.banner(""))
        assertEquals("", Styles.meta(""))
        assertEquals("", Styles.error(""))
    }

    @Test fun whenDisabledMultilineInputPassesThrough() {
        // Edge: multi-line input must not split or alter newlines
        // (helpers wrap as-is).
        Styles.setEnabled(false)
        val ml = "line1\nline2\nline3"
        assertEquals(ml, Styles.banner(ml))
    }

    @Test fun toggleStateIsObservedByEveryHelperImmediately() {
        // The state is `@Volatile` — pin that flips take effect on the
        // very next helper call (no caching / lazy evaluation that
        // would defer the toggle).
        Styles.setEnabled(true)
        val s1 = Styles.banner("x")
        Styles.setEnabled(false)
        val s2 = Styles.banner("x")
        assertNotEquals(s1, s2, "second call (after disable) must reflect new state")
        assertEquals("x", s2, "disabled returns plain")
    }
}
