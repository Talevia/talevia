package io.talevia.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [withServerDefaults] —
 * `apps/server/src/main/kotlin/io/talevia/server/Main.kt:37-49`.
 * Cycle 233 audit: 0 direct test refs. The function fills in
 * `TALEVIA_PROJECTS_HOME` / `TALEVIA_RECENTS_PATH` bundle-path
 * defaults that `ServerContainer` reads at startup; drift would
 * silently route project bundles to a different filesystem location,
 * breaking server-restart continuity for every operator that didn't
 * explicitly set both env vars.
 *
 * Same audit-pattern fallback as cycles 207-232.
 *
 * Three correctness contracts pinned:
 *
 *  1. **Caller's explicit values win.** When the caller passes
 *     `TALEVIA_PROJECTS_HOME` and/or `TALEVIA_RECENTS_PATH` in
 *     `raw`, the function MUST NOT override them. Drift to
 *     "always overwrite" would silently relocate operator-
 *     configured paths.
 *
 *  2. **TALEVIA_DATA_DIR overrides `~/.talevia` as the default
 *     root.** A server deployment that sets
 *     `TALEVIA_DATA_DIR=/var/lib/talevia` expects bundle paths to
 *     land there, not in `$HOME/.talevia`. Drift to "ignore
 *     TALEVIA_DATA_DIR" would silently route to the wrong root.
 *
 *  3. **Blank values treated as absent.** Per `?.takeIf
 *     { it.isNotBlank() }` and `isNullOrBlank()` checks, an
 *     empty/whitespace value triggers the fallback path. Drift to
 *     "treat blank as literal `''`" would write bundles to a
 *     filesystem-root-relative path.
 *
 * Plus pins:
 *   - Returned map is a SUPERSET of the input — unrelated keys in
 *     `raw` (e.g. `TALEVIA_DB_PATH`, `TALEVIA_SERVER_TOKEN`) are
 *     preserved verbatim. Drift to "filter to known keys" would
 *     silently drop operator-passed config.
 *   - The caller's map is NOT mutated — `withServerDefaults` is a
 *     pure function over its input. Drift to "mutate raw in place"
 *     would surprise callers passing `System.getenv()` (immutable)
 *     or shared maps.
 *   - Default paths are absolute (use `.absolutePath`), so a CWD
 *     change post-startup doesn't relocate bundle resolution.
 */
class WithServerDefaultsTest {

    @Test fun emptyEnvFillsBothDefaultsUnderUserHome() {
        // Marquee fallback pin: empty env → both keys point to
        // ~/.talevia. Drift to "fall back to CWD" would silently
        // relocate.
        val home = System.getProperty("user.home")
        val out = withServerDefaults(emptyMap())
        assertEquals(
            File(home, ".talevia/projects").absolutePath,
            out["TALEVIA_PROJECTS_HOME"],
            "empty env → projects home defaults to ~/.talevia/projects",
        )
        assertEquals(
            File(home, ".talevia/recents.json").absolutePath,
            out["TALEVIA_RECENTS_PATH"],
            "empty env → recents path defaults to ~/.talevia/recents.json",
        )
    }

    @Test fun taleviaDataDirOverridesUserHome() {
        // Marquee TALEVIA_DATA_DIR pin: server deployments often
        // want bundles outside $HOME (think /var/lib/talevia under
        // systemd). Drift to "ignore TALEVIA_DATA_DIR" would silently
        // route to ~/.talevia even when the operator set the var.
        val out = withServerDefaults(mapOf("TALEVIA_DATA_DIR" to "/var/lib/talevia"))
        assertEquals(
            "/var/lib/talevia/projects",
            out["TALEVIA_PROJECTS_HOME"],
            "TALEVIA_DATA_DIR root overrides ~/.talevia for projects",
        )
        assertEquals(
            "/var/lib/talevia/recents.json",
            out["TALEVIA_RECENTS_PATH"],
            "TALEVIA_DATA_DIR root overrides ~/.talevia for recents",
        )
    }

    @Test fun explicitProjectsHomeWinsOverDataDir() {
        // Pin: caller's explicit value beats the default-fill, even
        // when TALEVIA_DATA_DIR is also set. Drift to "always
        // recompute from DATA_DIR" would silently overwrite the
        // operator's explicit choice.
        val out = withServerDefaults(
            mapOf(
                "TALEVIA_DATA_DIR" to "/var/lib/talevia",
                "TALEVIA_PROJECTS_HOME" to "/custom/projects",
            ),
        )
        assertEquals(
            "/custom/projects",
            out["TALEVIA_PROJECTS_HOME"],
            "explicit TALEVIA_PROJECTS_HOME wins over TALEVIA_DATA_DIR-derived default",
        )
        // Sibling key (recents) STILL takes the DATA_DIR-derived
        // default since it wasn't explicitly set.
        assertEquals(
            "/var/lib/talevia/recents.json",
            out["TALEVIA_RECENTS_PATH"],
            "unset sibling still gets DATA_DIR default",
        )
    }

    @Test fun explicitRecentsPathWinsOverDataDir() {
        val out = withServerDefaults(
            mapOf(
                "TALEVIA_DATA_DIR" to "/var/lib/talevia",
                "TALEVIA_RECENTS_PATH" to "/custom/recents.json",
            ),
        )
        assertEquals("/custom/recents.json", out["TALEVIA_RECENTS_PATH"])
        assertEquals(
            "/var/lib/talevia/projects",
            out["TALEVIA_PROJECTS_HOME"],
            "unset sibling still gets DATA_DIR default",
        )
    }

    @Test fun bothExplicitWinOverEverything() {
        val out = withServerDefaults(
            mapOf(
                "TALEVIA_DATA_DIR" to "/should/not/matter",
                "TALEVIA_PROJECTS_HOME" to "/explicit/projects",
                "TALEVIA_RECENTS_PATH" to "/explicit/recents.json",
            ),
        )
        assertEquals("/explicit/projects", out["TALEVIA_PROJECTS_HOME"])
        assertEquals("/explicit/recents.json", out["TALEVIA_RECENTS_PATH"])
    }

    // ── Blank handling ─────────────────────────────────────

    @Test fun blankProjectsHomeFallsBackToDefault() {
        // Pin: `isNullOrBlank()` treats `""` and whitespace-only as
        // missing. Drift to "literal blank string passes through"
        // would route bundles to filesystem-root-relative paths.
        val home = System.getProperty("user.home")
        for (blank in listOf("", "   ", "\t")) {
            val out = withServerDefaults(mapOf("TALEVIA_PROJECTS_HOME" to blank))
            assertEquals(
                File(home, ".talevia/projects").absolutePath,
                out["TALEVIA_PROJECTS_HOME"],
                "blank '${blank.replace("\t", "\\t")}' must fall back to default",
            )
        }
    }

    @Test fun blankRecentsPathFallsBackToDefault() {
        val home = System.getProperty("user.home")
        val out = withServerDefaults(mapOf("TALEVIA_RECENTS_PATH" to ""))
        assertEquals(
            File(home, ".talevia/recents.json").absolutePath,
            out["TALEVIA_RECENTS_PATH"],
        )
    }

    @Test fun blankTaleviaDataDirFallsBackToUserHome() {
        // Pin: TALEVIA_DATA_DIR's blank fallback chain — `?.takeIf
        // { it.isNotBlank() }` returns null on blank, then the
        // `?: ` fallback supplies `~/.talevia`. Drift to "treat blank
        // DATA_DIR as literal `''`" would resolve to filesystem root.
        val home = System.getProperty("user.home")
        val out = withServerDefaults(mapOf("TALEVIA_DATA_DIR" to "   "))
        assertEquals(
            File(home, ".talevia/projects").absolutePath,
            out["TALEVIA_PROJECTS_HOME"],
            "blank TALEVIA_DATA_DIR must fall back to ~/.talevia",
        )
    }

    // ── Map preservation ───────────────────────────────────

    @Test fun unrelatedKeysArePreservedVerbatim() {
        // Marquee map-superset pin: unrelated keys (TALEVIA_DB_PATH,
        // TALEVIA_SERVER_TOKEN, etc.) MUST round-trip. Drift to
        // "filter to known bundle keys" would silently drop
        // operator config and break unrelated wiring.
        val raw = mapOf(
            "TALEVIA_DB_PATH" to "/db/talevia.db",
            "TALEVIA_SERVER_TOKEN" to "secret",
            "ANTHROPIC_API_KEY" to "sk-ant-test",
            "UNRELATED_VAR" to "value",
        )
        val out = withServerDefaults(raw)
        assertEquals("/db/talevia.db", out["TALEVIA_DB_PATH"])
        assertEquals("secret", out["TALEVIA_SERVER_TOKEN"])
        assertEquals("sk-ant-test", out["ANTHROPIC_API_KEY"])
        assertEquals("value", out["UNRELATED_VAR"])
        // The two filled-in keys are also present.
        assertTrue("TALEVIA_PROJECTS_HOME" in out)
        assertTrue("TALEVIA_RECENTS_PATH" in out)
    }

    @Test fun callerInputIsNotMutatedInPlace() {
        // Pin: `withServerDefaults` is pure on its input. Drift to
        // "raw[key] = ..." mutation would surprise callers passing
        // System.getenv() (immutable on JVM, would throw) or shared
        // map references.
        val raw = mapOf("TALEVIA_DATA_DIR" to "/var/lib/talevia")
        val before = raw.toMap()
        withServerDefaults(raw)
        assertEquals(
            before,
            raw,
            "raw input must NOT be mutated by withServerDefaults",
        )
    }

    @Test fun defaultPathsAreAbsoluteSoCwdChangesDoNotRelocate() {
        // Pin: defaults use `.absolutePath` so a `cd` post-startup
        // doesn't move bundle resolution. Drift to relative paths
        // would create silent relocation under any process that
        // changes CWD (notable: launchd / systemd respawn).
        val out = withServerDefaults(mapOf("TALEVIA_DATA_DIR" to "/var/lib/talevia"))
        assertTrue(
            out["TALEVIA_PROJECTS_HOME"]!!.startsWith("/"),
            "projects home must be absolute path; got ${out["TALEVIA_PROJECTS_HOME"]}",
        )
        assertTrue(
            out["TALEVIA_RECENTS_PATH"]!!.startsWith("/"),
            "recents path must be absolute path; got ${out["TALEVIA_RECENTS_PATH"]}",
        )
    }
}
