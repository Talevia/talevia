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
    )
}
