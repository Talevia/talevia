package io.talevia.core.permission

import io.talevia.core.JsonConfig
import io.talevia.core.domain.randomSuffix
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * Per-user storage for interactive "remember forever" [PermissionRule] grants.
 *
 * The CLI / Desktop app's interactive `[A]lways` reply lands a
 * `PermissionRule(permission, pattern, ALLOW)` in the runtime rules list so
 * subsequent same-permission requests resolve silently for the rest of the
 * process. Pre-cycle-53, that list evaporated on process restart — CLI users
 * re-answered the same prompts every startup. This abstraction persists the
 * user-added slice separately from `DefaultPermissionRuleset.rules` (which
 * is always present in code) so container init can merge the two at load.
 *
 * File shape: a single-element JSON array of [PermissionRule] at the path
 * the caller injects. `<userDataDir>/permission-rules.json` on CLI /
 * Desktop / Server; `<filesDir>/permission-rules.json` on Android; iOS
 * currently has no interactive permission flow, so its container passes
 * [Noop] — no persistence, no regression.
 *
 * Parallel to cycle-37's `FileSecretStore`: composition roots wire the
 * concrete file path once, callers inject the abstraction so tests /
 * headless rigs can pass [Noop].
 *
 * Invariants:
 *  - [load] never throws on missing / malformed file — returns empty list,
 *    logs nothing. A corrupted file should not brick the operator's
 *    CLI; worst case they re-grant the rules they care about.
 *  - [save] writes the entire list atomically (whole-file rewrite). The
 *    caller decides when to call save — typically right after appending a
 *    new rule in response to an `[A]lways` reply.
 *  - Rule order in the file mirrors the caller's list; duplicate entries
 *    are caller's responsibility (the rule-evaluator in
 *    [DefaultPermissionService.evaluate] is tolerant of duplicates).
 */
interface PermissionRulesPersistence {
    /**
     * Load the persisted user-granted rules. Returns empty on missing
     * file, I/O error, or decode failure — the absence of rules is a
     * valid state (new install, corrupted config, migration).
     */
    suspend fun load(): List<PermissionRule>

    /**
     * Persist the given list atomically. Callers pass the ENTIRE list of
     * currently-remembered rules (not a delta). Failures are swallowed so
     * a read-only filesystem doesn't break the interactive path — the
     * in-memory list still has the rule, the user just re-grants next
     * process.
     */
    suspend fun save(rules: List<PermissionRule>)

    companion object {
        /** No-op persistence — tests + platforms without a file lane. */
        val Noop: PermissionRulesPersistence = object : PermissionRulesPersistence {
            override suspend fun load(): List<PermissionRule> = emptyList()
            override suspend fun save(rules: List<PermissionRule>) = Unit
        }
    }
}

/**
 * Okio-based JSON persistence. Writes the file via a tmp-file +
 * atomic-rename pattern so a crashed write can't leave a corrupt
 * `permission-rules.json` behind (mirrors [FileProjectStore]'s
 * `atomicWrite` approach).
 */
class FilePermissionRulesPersistence(
    private val path: Path,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val json: Json = JsonConfig.default,
) : PermissionRulesPersistence {

    override suspend fun load(): List<PermissionRule> = runCatching {
        // fs.exists() itself can throw on a FakeFileSystem when the path's
        // parent isn't a directory; wrap it too so a pathological config
        // can't brick CLI init.
        if (!fs.exists(path)) return@runCatching emptyList()
        val text = fs.read(path) { readUtf8() }
        json.decodeFromString(ListSerializer(PermissionRule.serializer()), text)
    }.getOrDefault(emptyList())

    override suspend fun save(rules: List<PermissionRule>) {
        runCatching {
            path.parent?.let { fs.createDirectories(it) }
            val tmp = path.parent?.resolve("${path.name}.tmp.${randomSuffix()}")
                ?: error("cannot derive tmp path for $path (no parent)")
            fs.write(tmp) {
                writeUtf8(json.encodeToString(ListSerializer(PermissionRule.serializer()), rules))
            }
            fs.atomicMove(tmp, path)
        }
    }
}
