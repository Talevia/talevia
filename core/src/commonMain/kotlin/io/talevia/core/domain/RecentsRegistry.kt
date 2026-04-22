package io.talevia.core.domain

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Per-machine catalog of which project bundles this user knows about and
 * where they live on disk. The recents file is the source of truth for
 * `ProjectStore.list*()` and `NodesAllProjectsQuery.runNodesAllProjectsQuery`
 * — projects you've never `openAt` / `createAt` won't show up.
 *
 * Project IDs are stable across machines (stored inside `talevia.json`),
 * but [RecentsEntry.path] is local. Cloning the same bundle to two
 * different paths on the same machine is fine — the entry's path is
 * updated to whichever path was most recently opened.
 *
 * On-disk shape (`<userDataDir>/recents.json`):
 * ```
 * { "schemaVersion": 1, "entries": [ { "id": ..., "path": ..., "title": ..., "lastOpenedAtEpochMs": ... } ] }
 * ```
 *
 * Thread-safe: every write goes through an in-process [Mutex] and uses an
 * atomic move (tempfile + rename) so a partial write never replaces a
 * good file. No cross-process locking.
 */
class RecentsRegistry(
    private val path: Path,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val json: Json = JsonConfig.prettyPrint,
) {
    private val mutex = Mutex()

    /** All entries in last-opened-descending order. Stale entries (path doesn't exist) are kept. */
    suspend fun list(): List<RecentsEntry> = mutex.withLock {
        loadOrEmpty().entries.sortedByDescending { it.lastOpenedAtEpochMs }
    }

    /** Look up an entry by id, or null if not registered. */
    suspend fun get(id: ProjectId): RecentsEntry? = mutex.withLock {
        loadOrEmpty().entries.firstOrNull { it.id == id.value }
    }

    /**
     * Insert / refresh the entry for [id] at [path] with the given [title].
     * If [id] is already registered at a different path, the path is updated
     * to the new location (the project was moved or cloned). The
     * [lastOpenedAtEpochMs] timestamp is replaced unconditionally.
     */
    suspend fun upsert(id: ProjectId, path: Path, title: String, lastOpenedAtEpochMs: Long): Unit = mutex.withLock {
        val current = loadOrEmpty()
        val others = current.entries.filterNot { it.id == id.value }
        val updated = current.copy(
            entries = others + RecentsEntry(
                id = id.value,
                path = path.toString(),
                title = title,
                lastOpenedAtEpochMs = lastOpenedAtEpochMs,
            ),
        )
        persist(updated)
    }

    /** Remove the entry for [id]. Idempotent — does nothing if not registered. */
    suspend fun remove(id: ProjectId): Unit = mutex.withLock {
        val current = loadOrEmpty()
        val updated = current.copy(entries = current.entries.filterNot { it.id == id.value })
        if (updated.entries.size != current.entries.size) {
            persist(updated)
        }
    }

    /** Update only the title for [id]. No-op if not registered. */
    suspend fun setTitle(id: ProjectId, title: String, updatedAtEpochMs: Long): Unit = mutex.withLock {
        val current = loadOrEmpty()
        val updated = current.copy(
            entries = current.entries.map { entry ->
                if (entry.id == id.value) entry.copy(title = title, lastOpenedAtEpochMs = updatedAtEpochMs)
                else entry
            },
        )
        persist(updated)
    }

    private fun loadOrEmpty(): RecentsFile {
        if (!fs.exists(path)) return RecentsFile()
        val text = fs.read(path) { readUtf8() }
        return runCatching { json.decodeFromString(RecentsFile.serializer(), text) }
            .getOrElse { RecentsFile() }
    }

    private fun persist(content: RecentsFile) {
        path.parent?.let { fs.createDirectories(it) }
        val tmp = path.parent?.resolve("${path.name}.tmp.${randomSuffix()}")
            ?: "${path}.tmp.${randomSuffix()}".toPath()
        fs.write(tmp) { writeUtf8(json.encodeToString(RecentsFile.serializer(), content)) }
        fs.atomicMove(tmp, path)
    }

    private fun randomSuffix(): String =
        kotlin.random.Random.nextLong().toString(radix = 36).removePrefix("-")
}

@Serializable
internal data class RecentsFile(
    val schemaVersion: Int = 1,
    val entries: List<RecentsEntry> = emptyList(),
)

@Serializable
data class RecentsEntry(
    val id: String,
    val path: String,
    val title: String,
    val lastOpenedAtEpochMs: Long,
)
