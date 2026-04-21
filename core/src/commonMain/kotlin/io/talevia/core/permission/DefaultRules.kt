package io.talevia.core.permission

/**
 * Sensible defaults for the M4 desktop demo. Real apps load these from config,
 * but starting from this set keeps the agent safe out of the box:
 *  - editing the timeline is silent
 *  - importing local files is silent (probing is read-only)
 *  - writing files / hitting the network always asks
 */
object DefaultPermissionRuleset {
    val rules: List<PermissionRule> = listOf(
        // Trivial / read-only
        PermissionRule(permission = "echo", pattern = "*", action = PermissionAction.ALLOW),
        // Agent scratchpad — purely local state, zero side effects, prompting
        // on every todo update would make the tool useless for multi-step intents.
        PermissionRule(permission = "todowrite", pattern = "*", action = PermissionAction.ALLOW),
        PermissionRule(permission = "media.import", pattern = "*", action = PermissionAction.ALLOW),
        PermissionRule(permission = "timeline.write", pattern = "*", action = PermissionAction.ALLOW),
        // Source-graph reads/writes are local-only state mutations (no I/O, no cost) — silent
        // by default so the agent can scaffold consistency bindings without prompting.
        PermissionRule(permission = "source.read", pattern = "*", action = PermissionAction.ALLOW),
        PermissionRule(permission = "source.write", pattern = "*", action = PermissionAction.ALLOW),
        // Project lifecycle: catalog reads + create are local-only. Delete is irreversible
        // (loses Source DAG / Timeline / Lockfile / RenderCache) — always confirm.
        PermissionRule(permission = "project.read", pattern = "*", action = PermissionAction.ALLOW),
        PermissionRule(permission = "project.write", pattern = "*", action = PermissionAction.ALLOW),
        // Session introspection — reading session metadata (list_sessions) is
        // local-only, no I/O, no cost. Silent by default.
        PermissionRule(permission = "session.read", pattern = "*", action = PermissionAction.ALLOW),
        // Session mutation (fork_session). Purely local state: a new SessionId plus
        // copied messages. No external cost, no network, no filesystem leak —
        // match `source.write` / `project.write` and default to ALLOW. Server
        // deployments can flip this to ASK if they want a paper-trail prompt.
        PermissionRule(permission = "session.write", pattern = "*", action = PermissionAction.ALLOW),
        // Destructive session mutation (delete_session). Permanently removes the
        // session row + every cascaded message and part. No un-delete lane.
        // Matches `project.destructive` — default ASK.
        PermissionRule(permission = "session.destructive", pattern = "*", action = PermissionAction.ASK),
        // Provider introspection (list_providers) — pure local container state,
        // no external call. Silent default matches the other `*.read` keywords.
        PermissionRule(permission = "provider.read", pattern = "*", action = PermissionAction.ALLOW),
        // Tool-registry introspection (list_tools). Pure local state; silent default.
        PermissionRule(permission = "tool.read", pattern = "*", action = PermissionAction.ALLOW),

        // Side-effectful — always confirm
        PermissionRule(permission = "media.export.write", pattern = "*", action = PermissionAction.ASK),
        PermissionRule(permission = "media.network.fetch", pattern = "*", action = PermissionAction.ASK),
        PermissionRule(permission = "media.network.upload", pattern = "*", action = PermissionAction.ASK),
        PermissionRule(permission = "timeline.destructive", pattern = "*", action = PermissionAction.ASK),
        PermissionRule(permission = "project.destructive", pattern = "*", action = PermissionAction.ASK),
        // AIGC providers incur external cost + seed-locked artifacts. Ask before running.
        PermissionRule(permission = "aigc.generate", pattern = "*", action = PermissionAction.ASK),
        // ML enhancement (ASR / future upscale / colorize) uploads media to a third-party
        // provider. Ask the user the same way we ask for AIGC.
        PermissionRule(permission = "ml.transcribe", pattern = "*", action = PermissionAction.ASK),
        // Vision describe lane — uploads image bytes to a multimodal provider.
        PermissionRule(permission = "ml.describe", pattern = "*", action = PermissionAction.ASK),

        // External filesystem access (read_file / write_file / list_directory / glob).
        // Always ASK because the LLM touches real user files by path; the pattern
        // gets populated with the exact path/glob pattern so "Always" rules scope
        // to that path rather than granting blanket fs access. Server containers
        // via ServerPermissionService auto-reject ASK so headless deployments
        // start deny-by-default; operators add ALLOW rules per path as needed.
        PermissionRule(permission = "fs.read", pattern = "*", action = PermissionAction.ASK),
        PermissionRule(permission = "fs.write", pattern = "*", action = PermissionAction.ASK),
        PermissionRule(permission = "fs.list", pattern = "*", action = PermissionAction.ASK),

        // Shell command execution via `bash` tool. Always ASK — arbitrary
        // shell access is the single biggest blast-radius capability the
        // agent has. Pattern is populated with the first command token
        // (`git`, `ls`, `./gradlew`) so an "Always" rule scopes cleanly.
        PermissionRule(permission = "bash.exec", pattern = "*", action = PermissionAction.ASK),

        // Web fetches (`web_fetch` tool). Pattern is populated with the URL
        // host, so an "Always" rule scopes to a single site (`github.com`,
        // `docs.kernel.org`). Server containers auto-reject ASK; operators
        // add ALLOW rules per host as needed.
        PermissionRule(permission = "web.fetch", pattern = "*", action = PermissionAction.ASK),

        // Web searches (`web_search` tool). Pattern is the lower-cased
        // query, so an "Always" rule scopes to that exact phrase rather
        // than blanket-granting search. Every call hits an external
        // (potentially metered) provider, so default to ASK; users that
        // want frictionless search can flip to ALLOW with pattern `*`.
        // Server containers auto-reject ASK by default.
        PermissionRule(permission = "web.search", pattern = "*", action = PermissionAction.ASK),
    )
}
