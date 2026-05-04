package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Boundary + edge-case pins for [BundleMediaPathResolver]. The sibling
 * [BundleMediaPathResolverTest] (5 happy-path tests) covers each of the
 * 4 [MediaSource] variants + unknown-asset error. What's NOT covered
 * but load-bearing for production correctness:
 *
 * - **AssetId equality strictness**: lookup uses
 *   `project.assets.find { it.id == assetId }`. The `==` operator is
 *   exact equality on the underlying string. Drift to case-insensitive
 *   or whitespace-trimming match would silently widen lookup, leading
 *   to wrong-asset selection when two AssetIds differ only by case
 *   (e.g. `"clip-A"` vs `"clip-a"`).
 *
 * - **Multi-match `find` returns first**: if a project somehow has two
 *   assets with the same id (shouldn't happen — store should reject
 *   duplicates — but pin observed behaviour), `find` returns the
 *   first. Pin: this isn't a "last-write-wins" semantics; insertion
 *   order matters.
 *
 * - **`MediaSource.BundleFile` constructor defensive contract**:
 *   the constructor rejects path-traversal inputs (empty, leading
 *   `/`, `..` segment, backslash, Windows drive letter) before
 *   they reach the resolver. This is the actual security layer
 *   protecting against malicious / malformed paths. Pin each
 *   reject case so a refactor relaxing the constructor lands
 *   in test-red — the resolver itself trusts that BundleFile
 *   inputs are well-formed.
 *
 * - **Error-message string contracts**: the `Http` branch's
 *   "use import_media with copy_into_bundle=true" hint and the
 *   `Platform` branch's "needs a per-platform resolver" hint
 *   are user-visible UX surfaces. Drift here changes the
 *   error message users see without breaking any single-substring
 *   test.
 *
 * Same audit-pattern as cycles 313/314/318/320 — pin every
 * load-bearing literal + boundary so a refactor lands in test-red.
 */
class BundleMediaPathResolverDriftPinsTest {

    private fun project(vararg assets: MediaAsset): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(),
        assets = assets.toList(),
    )

    private fun asset(id: String, source: MediaSource): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = source,
        metadata = MediaMetadata(duration = 5.seconds),
    )

    // ── AssetId equality strictness pins ───────────────────────────

    @Test fun lookupIsCaseSensitiveOnAssetId() = runTest {
        // Pin: AssetId("clip-a") MUST NOT match AssetId("clip-A").
        // Drift to case-insensitive match would break legitimate
        // case-distinct ids (which are common when using kebab-case
        // for human-readable + uppercase variant for "the same
        // clip but at a different transform").
        val resolver = BundleMediaPathResolver(
            project(asset("clip-a", MediaSource.File("/a.mp4"))),
            "/projects/foo".toPath(),
        )
        val ex = assertFailsWith<IllegalStateException> {
            resolver.resolve(AssetId("clip-A")) // uppercase A
        }
        assertTrue(ex.message!!.contains("clip-A"), "error mentions the missing id: ${ex.message}")
    }

    @Test fun lookupIsWhitespaceSensitiveOnAssetId() = runTest {
        // Pin: AssetId(" clip ") MUST NOT match AssetId("clip").
        // Drift to whitespace-trim match would let pasted ids with
        // copy-paste artefacts silently match — unsafe (different
        // ids with same trimmed value would collide).
        val resolver = BundleMediaPathResolver(
            project(asset("clip", MediaSource.File("/a.mp4"))),
            "/projects/foo".toPath(),
        )
        assertFailsWith<IllegalStateException> {
            resolver.resolve(AssetId(" clip "))
        }
        assertFailsWith<IllegalStateException> {
            resolver.resolve(AssetId("clip "))
        }
    }

    @Test fun lookupReturnsFirstMatchOnDuplicateAssetIds() = runTest {
        // Two assets with same AssetId — store layer SHOULD prevent
        // this but pin observed behaviour at the resolver layer.
        // `find` returns the first match. Pin protects against
        // refactor to "last-write-wins" semantics that would
        // change which path renders.
        val resolver = BundleMediaPathResolver(
            project(
                asset("dupe", MediaSource.File("/first.mp4")),
                asset("dupe", MediaSource.File("/second.mp4")),
            ),
            "/projects/foo".toPath(),
        )
        assertEquals(
            "/first.mp4",
            resolver.resolve(AssetId("dupe")),
            "Duplicate AssetId MUST return first match (insertion order)",
        )
    }

    // ── MediaSource.BundleFile constructor defensive contract ─────

    @Test fun bundleFileRejectsBlankPath() {
        // Pin: empty / whitespace path rejected at construction.
        // This is the FIRST line of defense — resolver downstream
        // trusts the constructor.
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("") }
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("   ") }
    }

    @Test fun bundleFileRejectsLeadingSlash() {
        // Pin: leading `/` rejected. Without this guard, Okio's
        // `resolve(absolutePath)` would replace bundleRoot, silently
        // escaping the bundle (e.g. BundleFile("/etc/passwd")).
        // The constructor blocks it before the resolver ever sees
        // it — same security shape as cycle 318 glob escape pins.
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("/etc/passwd") }
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("/foo.mp3") }
    }

    @Test fun bundleFileRejectsParentDirSegments() {
        // Pin: any segment equal to `..` is rejected. Without this
        // guard, BundleFile("../escape.txt") would join with
        // bundleRoot to produce a path that escapes when the OS
        // resolves it. The constructor splits on `/` and rejects
        // any segment matching `..` — surface drift if the
        // segment-split is dropped.
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("..") }
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("../escape.txt") }
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("media/../etc/passwd") }
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("a/b/../c") }
    }

    @Test fun bundleFileRejectsBackslashes() {
        // Pin: backslash rejected. Windows-style separators would
        // confuse Okio's POSIX-only path semantics, and the
        // bundle convention is POSIX forward-slash. A refactor
        // dropping this guard would let cross-platform path
        // bugs slip through.
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("media\\file.mp3") }
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("\\foo") }
    }

    @Test fun bundleFileRejectsWindowsDriveLetterPaths() {
        // Pin: `C:foo`, `D:\bar`, etc. are rejected. Without this
        // guard, BundleFile("C:Windows/System32") could escape to
        // the C: drive on Windows. Pin every common drive letter
        // shape (case-sensitive regex `^[A-Za-z]:.*`).
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("C:Windows") }
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("D:foo") }
        assertFailsWith<IllegalArgumentException> { MediaSource.BundleFile("z:bar") } // lowercase
    }

    @Test fun bundleFileAcceptsLegalRelativePath() {
        // Inverse pin: confirm a normal-looking relative path
        // constructs without throwing. Anti-pin against a
        // refactor that over-tightens validation (e.g. rejecting
        // dots in filenames, rejecting hyphens, etc.).
        MediaSource.BundleFile("media/a.mp3")
        MediaSource.BundleFile("media/sub/dir/file.mp3")
        MediaSource.BundleFile("file-with-dashes_and_underscores.mp4")
        MediaSource.BundleFile("media/中文.mp4") // unicode
        MediaSource.BundleFile(".hidden") // leading dot allowed (NOT `..` segment)
        MediaSource.BundleFile("a.b.c.d") // multiple dots in single segment OK
    }

    @Test fun nestedRelativePathJoinsCorrectly() = runTest {
        // Happy path with nested relative path — pin the join
        // separator behaviour. Multi-segment paths should produce
        // the conventional `/`-separated output.
        val resolver = BundleMediaPathResolver(
            project(asset("a", MediaSource.BundleFile("media/sub/dir/file.mp3"))),
            "/projects/foo".toPath(),
        )
        assertEquals("/projects/foo/media/sub/dir/file.mp3", resolver.resolve(AssetId("a")))
    }

    // ── Error-message string contracts ─────────────────────────────

    @Test fun unknownAssetErrorMessageContainsAssetIdAndProjectId() = runTest {
        // Pin: the error message includes both the missing
        // AssetId AND the ProjectId. Both are load-bearing for
        // debug surface — drift dropping either makes "asset
        // not found" errors much harder to triage.
        val resolver = BundleMediaPathResolver(
            project(),
            "/projects/foo".toPath(),
        )
        val ex = assertFailsWith<IllegalStateException> {
            resolver.resolve(AssetId("missing-asset-id"))
        }
        assertTrue(
            ex.message!!.contains("missing-asset-id"),
            "error MUST mention missing AssetId; got: ${ex.message}",
        )
        assertTrue(
            ex.message!!.contains("p"), // project id
            "error MUST mention ProjectId; got: ${ex.message}",
        )
    }

    @Test fun httpSourceErrorMessageContainsCopyIntoBundleHint() = runTest {
        // Pin: HTTP-source error includes the migration hint
        // `import_media with copy_into_bundle=true`. This is
        // the user's primary "what do I do now?" signal —
        // drift dropping the hint silently degrades UX.
        val resolver = BundleMediaPathResolver(
            project(asset("a", MediaSource.Http("https://example.com/foo.mp4"))),
            "/projects/foo".toPath(),
        )
        val ex = assertFailsWith<IllegalStateException> { resolver.resolve(AssetId("a")) }
        assertTrue(
            ex.message!!.contains("import_media"),
            "Http error MUST mention 'import_media'; got: ${ex.message}",
        )
        assertTrue(
            ex.message!!.contains("copy_into_bundle"),
            "Http error MUST mention 'copy_into_bundle'; got: ${ex.message}",
        )
    }

    @Test fun platformSourceErrorMessageContainsScheme() = runTest {
        // Pin: Platform-source error message includes the
        // specific scheme (e.g. "ios.phasset"). Without the
        // scheme, multi-platform projects can't tell which
        // resolver they need to add.
        val resolver = BundleMediaPathResolver(
            project(asset("a", MediaSource.Platform("ios.phasset", "ABC123"))),
            "/projects/foo".toPath(),
        )
        val ex = assertFailsWith<IllegalStateException> { resolver.resolve(AssetId("a")) }
        assertTrue(
            ex.message!!.contains("ios.phasset"),
            "Platform error MUST include scheme; got: ${ex.message}",
        )
        assertTrue(
            ex.message!!.contains("per-platform resolver"),
            "Platform error MUST mention 'per-platform resolver' guidance; got: ${ex.message}",
        )
    }

    // ── File source verbatim-pass invariant ─────────────────────────

    @Test fun fileSourcePathIsReturnedExactlyVerbatim() = runTest {
        // Pin: File-source path is returned byte-for-byte unchanged.
        // No normalization, no path-separator conversion, no quoting.
        // The render engine downstream is responsible for any path
        // handling — the resolver MUST be a transparent pass-through.
        // Pin tricky cases: spaces, unicode, special chars.
        for (path in listOf(
            "/Users/alice/My Movies/clip with spaces.mp4",
            "/tmp/中文路径/file.mp4",
            "/path/with-dashes_and_underscores.mp4",
            "C:\\Windows\\Path\\file.mp4", // backslash path
        )) {
            val resolver = BundleMediaPathResolver(
                project(asset("a", MediaSource.File(path))),
                "/projects/foo".toPath(),
            )
            assertEquals(
                path,
                resolver.resolve(AssetId("a")),
                "File path MUST be returned verbatim; got drift on '$path'",
            )
        }
    }
}
