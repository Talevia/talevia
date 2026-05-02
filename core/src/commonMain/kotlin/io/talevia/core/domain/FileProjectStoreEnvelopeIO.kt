package io.talevia.core.domain

import io.talevia.core.domain.render.ClipRenderCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Bundle envelope written to `talevia.json`. Keeps title + schema separate
 * from the [Project] body so adding envelope-level metadata later doesn't
 * require touching the Project model.
 */
@Serializable
data class StoredProject(
    val schemaVersion: Int = 1,
    val title: String,
    /**
     * Epoch-millis when this bundle was first written. Preserved across
     * subsequent [FileProjectStore.upsert] calls so `summary().createdAtEpochMs`
     * reports a stable value even after the file is overwritten by an atomic
     * move (which would otherwise reset filesystem-level creation time on
     * many platforms / fake filesystems).
     *
     * Defaults to 0 for back-compat when decoding bundles that predate the
     * field; the store fills it in on the first upsert after read.
     */
    val createdAtEpochMs: Long = 0L,
    val project: Project,
)

/**
 * Envelope / cache read + write helpers for [FileProjectStore].
 *
 * Pure functions against an Okio [FileSystem] + [Path] + [Json]. Extracted
 * from `FileProjectStore.kt` as part of `debt-split-file-project-store`
 * (cycle-49, per cycle-48 backlog): the facade file was at 545 LOC after
 * cycle-45's source-history additions; envelope I/O + write-atomicity +
 * timestamp probes are the largest chunk of private-helper code and the
 * most stable (they rarely change across releases). Keeping them here
 * means the next `talevia.json` schema bump is a single-file change.
 *
 * Everything in this file is `internal` — callers outside
 * `io.talevia.core.domain` go through [FileProjectStore]'s `ProjectStore`
 * surface. The extraction is a refactor, not an API change.
 */

/**
 * Decode the bundle envelope at `<path>/talevia.json`.
 *
 * Throws on missing file or malformed JSON — callers inside [FileProjectStore]
 * have already verified existence before this helper is invoked.
 */
internal fun decodeStored(fs: FileSystem, path: Path, json: Json): StoredProject {
    val text = fs.read(path.resolve(FileProjectStore.TALEVIA_JSON)) { readUtf8() }
    return json.decodeFromString(StoredProject.serializer(), text)
}

/**
 * Best-effort read of the envelope's title without materialising the full
 * [Project]. Returns null on any I/O or decode failure — used by the
 * `openAt` recents-registry update where a missing title just falls back
 * to the project id.
 */
internal fun readTitle(fs: FileSystem, path: Path, json: Json): String? = runCatching {
    decodeStored(fs, path, json).title
}.getOrNull()

/**
 * Read the full bundle: envelope + `clip-render-cache.json` sidecar merged
 * onto the [Project] body. The render cache lives in `.talevia-cache/`
 * (gitignored, machine-local) so updates don't churn `talevia.json`'s
 * git-diff surface. A missing / malformed cache file degrades to
 * [ClipRenderCache.EMPTY] without erroring — cache is recoverable, envelope
 * isn't.
 */
internal fun readBundle(fs: FileSystem, path: Path, json: Json): Project {
    val stored = decodeStored(fs, path, json)
    val cachePath = path.resolve(FileProjectStore.CACHE_DIR).resolve(FileProjectStore.CLIP_RENDER_CACHE_FILE)
    val cache = if (fs.exists(cachePath)) {
        runCatching {
            json.decodeFromString(ClipRenderCache.serializer(), fs.read(cachePath) { readUtf8() })
        }.getOrDefault(ClipRenderCache.EMPTY)
    } else {
        ClipRenderCache.EMPTY
    }
    // `lockfile-extract-jsonl-phase1` (cycle 24): jsonl is authoritative
    // when present. Envelope's `lockfile` field is the migration fallback
    // for bundles that predate phase 1; the next mutate will dual-write
    // both, after which jsonl wins on every read.
    val lockfile = readLockfileJsonl(fs, path, json) ?: stored.project.lockfile
    return stored.project.copy(clipRenderCache = cache, lockfile = lockfile)
}

/**
 * Write the bundle atomically: ensure `<bundle>/`, `media/`, `.talevia-cache/`,
 * idempotent `.gitignore`, the `talevia.json` envelope (minus the machine-
 * local render cache), and the render cache sidecar file. Caller
 * ([FileProjectStore]) already holds its intra-process mutex AND the
 * cross-process bundle lock.
 *
 * Render cache travels in its own file so clip-mezzanine refreshes don't
 * churn `talevia.json` (→ noisy git diffs) and so `.talevia-cache/` can be
 * gitignored without silently amputating the [Project] model.
 */
internal fun writeBundleLocked(
    fs: FileSystem,
    path: Path,
    title: String,
    project: Project,
    createdAtEpochMs: Long,
    json: Json,
) {
    fs.createDirectories(path)
    fs.createDirectories(path.resolve(FileProjectStore.MEDIA_DIR))
    fs.createDirectories(path.resolve(FileProjectStore.CACHE_DIR))

    // Auto-write .gitignore once.
    val gitignore = path.resolve(FileProjectStore.GITIGNORE)
    if (!fs.exists(gitignore)) {
        atomicWrite(fs, gitignore) { writeUtf8(FileProjectStore.AUTO_GITIGNORE_BODY) }
    }

    // `lockfile-extract-jsonl-phase1` (cycle 24): write JSONL **first**
    // so on crash between this and the envelope write, the more-recent
    // lockfile state is recoverable from JSONL. Envelope's `lockfile`
    // field still gets written below (dual-write during phase 1) — phase 2
    // will drop the envelope's field entirely.
    writeLockfileJsonl(fs, path, project.lockfile, json)

    // talevia.json carries everything except the (machine-local) clip render cache.
    val slim = project.copy(clipRenderCache = ClipRenderCache.EMPTY)
    val stored = StoredProject(title = title, createdAtEpochMs = createdAtEpochMs, project = slim)
    writeStoredEnvelope(fs, path, stored, json)

    // Cache lives separately so updates to clip render mezzanines don't
    // touch talevia.json (and so the cache can be gitignored without
    // silently amputating the project model).
    val cachePath = path.resolve(FileProjectStore.CACHE_DIR).resolve(FileProjectStore.CLIP_RENDER_CACHE_FILE)
    atomicWrite(fs, cachePath) {
        writeUtf8(json.encodeToString(ClipRenderCache.serializer(), project.clipRenderCache))
    }
}

/** Overwrite `<path>/talevia.json` with the given envelope, atomically. */
internal fun writeStoredEnvelope(fs: FileSystem, path: Path, stored: StoredProject, json: Json) {
    atomicWrite(fs, path.resolve(FileProjectStore.TALEVIA_JSON)) {
        writeUtf8(json.encodeToString(StoredProject.serializer(), stored))
    }
}

/**
 * Write via tmp-file + atomic rename. Handles the Kotlin/Native caveat
 * that Okio's `appendingSink().use { }` fails to resolve (Sink isn't
 * java.lang.AutoCloseable on Native and the `use` extension isn't wired);
 * [atomicWrite] is a plain lambda-receiving helper usable from both JVM
 * and Native impls.
 */
internal fun atomicWrite(fs: FileSystem, target: Path, write: BufferedSink.() -> Unit) {
    target.parent?.let { fs.createDirectories(it) }
    val tmp = target.parent?.resolve("${target.name}.tmp.${randomSuffix()}")
        ?: "${target}.tmp.${randomSuffix()}".toPath()
    fs.write(tmp) { write() }
    fs.atomicMove(tmp, target)
}

/**
 * Return `(createdAt, updatedAt)` epoch-millis from the filesystem
 * metadata of `talevia.json`. Falls back to [fallback] for either value
 * if the metadata lookup fails (FakeFileSystem doesn't always populate
 * both fields).
 */
internal fun bundleTimestamps(fs: FileSystem, taleviaJson: Path, fallback: Long): Pair<Long, Long> {
    val meta = runCatching { fs.metadata(taleviaJson) }.getOrNull()
    val created = meta?.createdAtMillis ?: fallback
    val updated = meta?.lastModifiedAtMillis ?: fallback
    return created to updated
}

/** Non-leaking temp-file suffix. Random-long → base36, no negative sign. */
internal fun randomSuffix(): String =
    kotlin.random.Random.nextLong().toString(radix = 36).removePrefix("-")
