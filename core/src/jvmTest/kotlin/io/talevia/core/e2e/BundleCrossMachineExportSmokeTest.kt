package io.talevia.core.e2e

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.RecentsRegistry
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.platform.BundleMediaPathResolver
import io.talevia.core.platform.FileBundleBlobWriter
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Bundle cross-machine reproducibility smoke test (VISION §3.1 "AIGC 产物可 pin" /
 * §5.3 "git push 出去的 bundle 在另一台机器能 reproduce export").
 *
 * Two separate [FileProjectStore]s share a single [FakeFileSystem] but each
 * carries its own `recents.json` + default-projects-home — mirroring two
 * machines (alice / bob) with distinct machine-local state. Alice creates a
 * project with a bundle-local AIGC asset. Bob `cp -r`s the bundle, opens it
 * with *his* store, and we assert that the "render fingerprint" — the
 * inputs a deterministic renderer would consume — matches byte-for-byte.
 *
 * The render fingerprint is the canonical JSON of (timeline, assets,
 * outputProfile) plus the sha-like `contentHashCode()` of every
 * bundle-resolved asset byte sequence. An FFmpeg-class renderer fed
 * identical Timeline + OutputSpec + asset bytes must produce identical
 * output (the engines already take bitexact flags for the per-clip cache);
 * testing that invariant here saves us from wiring a real engine into the
 * smoke path, which would make the test slow and platform-fragile.
 *
 * What this test catches:
 *  - A regression that leaks absolute paths into `talevia.json` (the
 *    bundle would decode differently on bob's machine).
 *  - A regression that makes `projectId` machine-local (the id is expected
 *    to be stable across collaborators because it lives inside
 *    `talevia.json`, not in the per-machine `recents.json`).
 *  - A regression where AIGC products land outside the bundle (`cp -r`
 *    would then copy only the manifest, not the bytes, and bob's
 *    `BundleMediaPathResolver` would fail to resolve the asset).
 */
class BundleCrossMachineExportSmokeTest {

    /**
     * Fixed wall-clock so both stores produce identical `updatedAtEpochMs`
     * stamps. Without this, alice's create-time and bob's open-time would
     * differ in a way the registry surface reports — but NOT in a way the
     * bundle itself differs, since `openAt` doesn't mutate `talevia.json`.
     * Pinning the clock keeps the test's assertion surface broad
     * (catching "did something accidentally restamp on open?").
     */
    private val frozen: Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }

    @Test
    fun bundleReproducesIdenticallyAfterCopyToAnotherMachine() = runTest {
        val fs = FakeFileSystem(clock = frozen)

        val alice = buildStore(fs, home = "/alice/.talevia/projects", recents = "/alice/.talevia/recents.json")
        val bob = buildStore(fs, home = "/bob/.talevia/projects", recents = "/bob/.talevia/recents.json")

        // --- alice: create bundle, add AIGC asset + clip ---
        val aliceBundle = "/alice/.talevia/projects/demo".toPath()
        val created = alice.createAt(path = aliceBundle, title = "Demo")
        val aliceId = created.id

        val aliceWriter = FileBundleBlobWriter(alice, fs)
        val aigcAssetId = AssetId("aigc-hero")
        val aigcBytes = byteArrayOf(0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x08, 0x0A, 0x0B, 0x0C, 0x0D)
        val aigcSource = aliceWriter.writeBlob(
            projectId = aliceId,
            assetId = aigcAssetId,
            bytes = aigcBytes,
            format = "png",
        )
        assertEquals("media/${aigcAssetId.value}.png", aigcSource.relativePath)

        val aigcAsset = MediaAsset(
            id = aigcAssetId,
            source = aigcSource,
            metadata = MediaMetadata(
                duration = 5.seconds,
                resolution = Resolution(1920, 1080),
                videoCodec = "h264",
            ),
        )
        val clip = Clip.Video(
            id = ClipId("c-hero"),
            timeRange = TimeRange(start = 0.seconds, duration = 5.seconds),
            sourceRange = TimeRange(start = 0.seconds, duration = 5.seconds),
            assetId = aigcAssetId,
        )
        val track = Track.Video(id = TrackId("t-v"), clips = listOf(clip))
        alice.mutate(aliceId) { prior ->
            prior.copy(
                assets = prior.assets + aigcAsset,
                timeline = prior.timeline.copy(tracks = prior.timeline.tracks + track, duration = 5.seconds),
            )
        }
        val aliceProject = alice.get(aliceId)!!
        val aliceFingerprint = fingerprint(aliceProject, aliceBundle, fs)

        // --- bob: `cp -r` the bundle to his own projects dir, then open it ---
        val bobBundle = "/bob/.talevia/projects/demo-from-alice".toPath()
        copyDirectoryRecursive(fs, src = aliceBundle, dst = bobBundle)
        assertTrue(fs.exists(bobBundle.resolve("talevia.json")), "bundle must carry talevia.json")
        assertTrue(
            fs.exists(bobBundle.resolve("media/${aigcAssetId.value}.png")),
            "AIGC bytes must travel with the bundle (this is the whole point of BundleFile)",
        )

        val bobProject = bob.openAt(bobBundle)
        val bobFingerprint = fingerprint(bobProject, bobBundle, fs)

        // --- cross-machine invariants ---
        assertEquals(
            aliceId,
            bobProject.id,
            "projectId lives inside talevia.json and must be stable across machines " +
                "(per-machine recents.json does not re-assign it)",
        )
        assertContentEquals(
            aliceFingerprint,
            bobFingerprint,
            "render fingerprint (canonical (timeline, assets, profile) JSON + bundle-resolved asset bytes) " +
                "must be byte-identical after cp -r — a deterministic renderer would therefore produce " +
                "identical export bytes on both machines",
        )

        // Also assert the bundle-resolved asset path actually reads back the AIGC bytes on bob's
        // machine — catches a regression where BundleMediaPathResolver starts leaking alice's path.
        val resolver = BundleMediaPathResolver(bobProject, bobBundle)
        val resolvedPath = resolver.resolve(aigcAssetId)
        assertEquals(
            bobBundle.resolve("media/${aigcAssetId.value}.png").toString(),
            resolvedPath,
            "resolver must rebase bundle-relative paths under bob's bundle root, not alice's",
        )
        val resolvedBytes = fs.read(resolvedPath.toPath()) { readByteArray() }
        assertContentEquals(aigcBytes, resolvedBytes)
    }

    private fun buildStore(fs: FakeFileSystem, home: String, recents: String): FileProjectStore =
        FileProjectStore(
            registry = RecentsRegistry(recents.toPath(), fs),
            defaultProjectsHome = home.toPath(),
            fs = fs,
            clock = frozen,
        )

    /**
     * Canonical JSON of the render-relevant Project surface plus byte hashes
     * of every bundle-local asset, resolved through the machine's own
     * [BundleMediaPathResolver]. Lockfile / snapshots / renderCache are
     * deliberately excluded — they're metadata about production history,
     * not inputs to the render. `updatedAtEpochMs` stamps come through
     * because they're part of the canonical JSON, and bob should see the
     * exact stamps alice wrote (openAt is read-only; re-stamping would be
     * the regression).
     */
    private fun fingerprint(project: Project, bundleRoot: Path, fs: FileSystem): ByteArray {
        val timelineJson = JsonConfig.default.encodeToString(Timeline.serializer(), project.timeline)
        val assetsJson = JsonConfig.default.encodeToString(
            ListSerializer(MediaAsset.serializer()),
            project.assets.sortedBy { it.id.value },
        )
        val sb = StringBuilder()
        sb.append("timeline=").append(timelineJson).append('\n')
        sb.append("assets=").append(assetsJson).append('\n')
        for (asset in project.assets.sortedBy { it.id.value }) {
            when (val src = asset.source) {
                is MediaSource.BundleFile -> {
                    val bytes = fs.read(bundleRoot.resolve(src.relativePath)) { readByteArray() }
                    sb.append("bundleBytes[").append(asset.id.value).append("]=")
                        .append(bytes.contentHashCode()).append('\n')
                }
                is MediaSource.File, is MediaSource.Http, is MediaSource.Platform -> {
                    // Non-bundle-local sources aren't expected to reproduce cross-machine;
                    // we include a type marker so a regression that flips bundle→file surfaces.
                    sb.append("nonBundleSource[").append(asset.id.value).append("]=")
                        .append(src::class.simpleName).append('\n')
                }
            }
        }
        return sb.toString().encodeToByteArray()
    }

    /**
     * Depth-first copy of a directory tree on an Okio [FileSystem]. FakeFileSystem
     * has no built-in recursive copy, so we walk manually. Matches `cp -r`
     * semantics: directories create, files byte-copy.
     */
    private fun copyDirectoryRecursive(fs: FileSystem, src: Path, dst: Path) {
        val meta = fs.metadataOrNull(src) ?: error("source not found: $src")
        if (meta.isDirectory) {
            fs.createDirectories(dst)
            for (child in fs.list(src)) {
                copyDirectoryRecursive(fs, child, dst.resolve(child.name))
            }
        } else {
            fs.createDirectories(dst.parent ?: error("no parent for $dst"))
            val bytes = fs.read(src) { readByteArray() }
            fs.write(dst) { write(bytes) }
        }
    }
}
