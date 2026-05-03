package io.talevia.core.tool.builtin.video.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct tests for [mezzanineDirFor] —
 * `core/tool/builtin/video/export/PerClipRender.kt:51`. The 7-LOC pure
 * function that computes the per-clip render-cache directory for a
 * given (outputPath, projectId) pair. Cycle 225 audit: 0 direct test
 * refs (sibling helpers `mimeTypeFor` / `fingerprintOf` / `provenanceOf`
 * each have direct test files; this one has been on its own since the
 * `debt-split-export-tool` extraction).
 *
 * Same audit-pattern fallback as cycles 207-224.
 *
 * Three correctness contracts pinned:
 *
 *  1. **Parent extraction.** `substringBeforeLast('/',
 *     missingDelimiterValue = ".")` — drift to "use empty string" or
 *     "throw" on a no-slash outputPath would write the cache directory
 *     under the JVM's CWD without any trailing slash, producing
 *     `.talevia-render-cache/...` colliding directories across
 *     different exports.
 *
 *  2. **Path-character sanitization on `projectId`.** `replace('/',
 *     '_').replace('\\', '_')`. Both forward AND backslash are scrubbed
 *     because per-kdoc the function must "survive `/` and `\`". Drift
 *     to "only scrub forward slash" would let a Windows-style projectId
 *     escape the intended directory; drift to "scrub colon too" would
 *     change the cache location for default-format JVM Uuid ids
 *     (which never contain colons but the kdoc explicitly mentions
 *     this concern is forward-looking).
 *
 *  3. **Result format.** `<parent>/.talevia-render-cache/<safeProjectId>`
 *     — three components in order, exactly one `/` between them.
 *     Drift to e.g. swap the segments would change where ALL existing
 *     mezzanines live, invalidating every cached export overnight.
 *
 * Plus determinism pin (same args → same string) and edge cases
 * (root-level path, deeply-nested path, projectId with mixed
 * separators).
 */
class MezzanineDirForTest {

    // ── 1. Parent extraction ─────────────────────────────────

    @Test fun typicalAbsoluteOutputPathYieldsItsParent() {
        // /Users/foo/exports/out.mp4 → /Users/foo/exports
        val dir = mezzanineDirFor(
            outputPath = "/Users/foo/exports/out.mp4",
            projectId = "p1",
        )
        assertEquals(
            "/Users/foo/exports/.talevia-render-cache/p1",
            dir,
            "deep absolute path → parent + .talevia-render-cache + projectId",
        )
    }

    @Test fun outputPathWithNoSlashFallsBackToCwdMarker() {
        // Pin: missingDelimiterValue = "." — a bare filename like
        // "out.mp4" yields the CWD marker as parent. Drift to "" would
        // produce "/.talevia-render-cache/<id>" rooted at filesystem
        // root, which is wrong for relative-path callers.
        val dir = mezzanineDirFor(outputPath = "out.mp4", projectId = "p1")
        assertEquals(
            "./.talevia-render-cache/p1",
            dir,
            "bare filename → '.' + .talevia-render-cache + projectId",
        )
    }

    @Test fun outputPathThatIsJustAFilenameInACwdLikePath() {
        // ./out.mp4 → . (substringBeforeLast on the last `/` — i.e. ".").
        val dir = mezzanineDirFor(outputPath = "./out.mp4", projectId = "p1")
        assertEquals(
            "./.talevia-render-cache/p1",
            dir,
            "./<file> → '.' parent (substringBeforeLast splits on the last `/`)",
        )
    }

    @Test fun deeplyNestedOutputPath() {
        // Pin: substringBeforeLast walks back to the LAST `/`, not the
        // first. A deep nest must keep all the upstream segments intact.
        val dir = mezzanineDirFor(
            outputPath = "/a/b/c/d/e/f/out.mp4",
            projectId = "p1",
        )
        assertEquals("/a/b/c/d/e/f/.talevia-render-cache/p1", dir)
    }

    @Test fun rootLevelOutputPath() {
        // Edge: /out.mp4 — single leading slash. substringBeforeLast on
        // '/' returns the empty string before the slash, NOT the
        // missingDelimiter fallback (the delimiter IS present).
        val dir = mezzanineDirFor(outputPath = "/out.mp4", projectId = "p1")
        assertEquals(
            "/.talevia-render-cache/p1",
            dir,
            "/<file> → empty parent + .talevia-render-cache (delimiter IS present)",
        )
    }

    // ── 2. ProjectId path-char sanitization ─────────────────

    @Test fun projectIdWithForwardSlashIsSanitized() {
        // Marquee path-traversal pin: a projectId containing `/` would
        // break out of the .talevia-render-cache directory and land
        // mezzanines in arbitrary locations (e.g. write to "../../.."
        // via "../../../escape"). Forward slash MUST be replaced.
        val dir = mezzanineDirFor(outputPath = "/out/dir/file.mp4", projectId = "a/b/c")
        assertEquals(
            "/out/dir/.talevia-render-cache/a_b_c",
            dir,
            "projectId with forward slashes must be _-escaped",
        )
        assertFalse(
            "a/b/c" in dir,
            "raw projectId with `/` must NOT appear unsanitized; got: $dir",
        )
    }

    @Test fun projectIdWithBackslashIsSanitized() {
        // Pin: kdoc explicitly mentions "colon on Windows, or the
        // default Uuid.toString format on JVM". Backslash is the
        // Windows path separator — drift to "skip the backslash"
        // would let a projectId escape on Windows the same way a
        // forward-slash one does on POSIX.
        val dir = mezzanineDirFor(outputPath = "/out/dir/file.mp4", projectId = "a\\b\\c")
        assertEquals(
            "/out/dir/.talevia-render-cache/a_b_c",
            dir,
            "projectId with backslashes must be _-escaped (Windows path-traversal guard)",
        )
        assertFalse(
            "\\" in dir,
            "raw projectId with `\\` must NOT appear unsanitized; got: $dir",
        )
    }

    @Test fun projectIdWithBothSeparatorsScrubbed() {
        // Mixed: `a/b\c` — both forward + backslash present.
        val dir = mezzanineDirFor(outputPath = "/out/file.mp4", projectId = "a/b\\c")
        assertEquals(
            "/out/.talevia-render-cache/a_b_c",
            dir,
            "mixed `/` + `\\` separators in projectId both scrubbed to `_`",
        )
    }

    @Test fun projectIdWithColonOrDotsLeftAlone() {
        // Pin: per kdoc "we only need to survive `/` and `\` here".
        // Other potentially-problematic chars (colon on Windows, dots
        // for path traversal) are NOT scrubbed — the file system /
        // export tool is expected to handle those upstream. Drift to
        // "scrub more chars" would silently change the cache directory
        // for existing projects with dotted ids (UUIDs are dot-free,
        // but the function takes any String).
        val dir = mezzanineDirFor(outputPath = "/out/file.mp4", projectId = "p1.v2:beta")
        assertEquals(
            "/out/.talevia-render-cache/p1.v2:beta",
            dir,
            "non-separator chars (`.` / `:`) preserved verbatim",
        )
    }

    @Test fun projectIdWithDotsForTraversalNotScrubbed() {
        // Adversarial-but-not-blocked: a projectId of "../escape"
        // doesn't contain `/` or `\` per se, only `.` chars. The
        // function leaves it intact (this is the caller's problem to
        // sanitize upstream — the function's contract is narrow).
        val dir = mezzanineDirFor(outputPath = "/out/file.mp4", projectId = "..escape..")
        assertEquals(
            "/out/.talevia-render-cache/..escape..",
            dir,
            "leading/trailing dots NOT scrubbed (kdoc: only `/` and `\\` are guarded)",
        )
    }

    @Test fun projectIdWithSlashAndDotTraversalCombinedAtLeastSeparatorsScrubbed() {
        // "../../escape" → forward slashes scrubbed, dots intact.
        val dir = mezzanineDirFor(outputPath = "/out/file.mp4", projectId = "../../escape")
        assertEquals(
            "/out/.talevia-render-cache/.._.._escape",
            dir,
            "slashes in `../../escape` scrubbed to `_`; dots intact",
        )
        assertFalse(
            "/escape" in dir.substringAfter(".talevia-render-cache/"),
            "post-scrub no embedded `/escape` segment in projectId portion",
        )
    }

    // ── 3. Result format invariant ──────────────────────────

    @Test fun resultFormatIsParentSlashCacheDirSlashProjectId() {
        // Pin: the format `<parent>/.talevia-render-cache/<safeProjectId>`
        // — 3 components, 2 slashes. Drift to swap order or use a
        // different cache-dir name would invalidate all cached
        // mezzanines.
        val dir = mezzanineDirFor(outputPath = "/x/y/z.mp4", projectId = "abc")
        assertTrue(dir.startsWith("/x/y/.talevia-render-cache/"), "format: parent + cache-dir prefix")
        assertTrue(dir.endsWith("/abc"), "format: trailing safeProjectId")
        // Exactly one `/.talevia-render-cache/` segment.
        assertEquals(
            1,
            dir.split("/.talevia-render-cache/").size - 1,
            "exactly one `.talevia-render-cache` segment",
        )
    }

    @Test fun cacheDirIsLiteralDotTaleviaRenderCache() {
        // Pin: the literal segment name. Drift would change where every
        // cached mezzanine lives across the codebase. Includes the
        // leading `.` (so it's hidden on POSIX listings).
        val dir = mezzanineDirFor(outputPath = "/p/file.mp4", projectId = "id")
        assertTrue(
            "/.talevia-render-cache/" in dir,
            "cache directory segment must be literal `.talevia-render-cache` (leading dot, hidden)",
        )
    }

    // ── 4. Determinism / no hidden state ─────────────────────

    @Test fun multipleCallsWithSameArgsProduceIdenticalResults() {
        val a = mezzanineDirFor("/x/y/out.mp4", "p")
        val b = mezzanineDirFor("/x/y/out.mp4", "p")
        val c = mezzanineDirFor("/x/y/out.mp4", "p")
        assertEquals(a, b, "deterministic: 1st == 2nd")
        assertEquals(b, c, "deterministic: 2nd == 3rd")
    }

    @Test fun differentOutputPathsYieldDifferentDirs() {
        val a = mezzanineDirFor("/a/out.mp4", "p")
        val b = mezzanineDirFor("/b/out.mp4", "p")
        assertEquals("/a/.talevia-render-cache/p", a)
        assertEquals("/b/.talevia-render-cache/p", b)
        assertFalse(a == b, "different outputPath parents → different cache dirs")
    }

    @Test fun differentProjectIdsYieldDifferentDirs() {
        val a = mezzanineDirFor("/x/out.mp4", "alpha")
        val b = mezzanineDirFor("/x/out.mp4", "beta")
        assertFalse(a == b, "different projectIds → different cache dirs")
    }

    @Test fun emptyProjectIdProducesParentSlashCacheDirSlash() {
        // Edge: empty projectId. The function's contract doesn't reject
        // it — drift to "throw on empty" would break tests that don't
        // care about projectId scoping.
        val dir = mezzanineDirFor("/out/file.mp4", "")
        assertEquals("/out/.talevia-render-cache/", dir, "empty projectId → trailing slash")
    }
}
