package io.talevia.core.tool

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRequest
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.Part
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * A Tool is the unit of action the agent can dispatch. Generic over typed input/output
 * to give Kotlin call sites compile-time safety, while exposing JSON Schema to the LLM.
 *
 * Inspired by OpenCode's `Tool.Def` (`packages/opencode/src/tool/tool.ts`).
 */
interface Tool<I : Any, O : Any> {
    val id: String
    /** Human-readable help sent to the LLM as the tool's `description` field. */
    val helpText: String
    val inputSchema: JsonObject
    val inputSerializer: KSerializer<I>
    val outputSerializer: KSerializer<O>
    val permission: PermissionSpec

    /**
     * When this tool should be exposed to the model. Defaults to [ToolApplicability.Always]
     * so the ~90 existing tools keep compiling without touching a thing. Override to hide
     * a tool from the LLM's schema bundle when a precondition isn't met — e.g. a timeline
     * mutator that needs a `currentProjectId` binding to mean anything. Filtering happens
     * at [ToolRegistry.specs]; dispatch itself still works (a cached spec from a prior
     * turn remains callable).
     *
     * Motivation: a single turn bundles every tool's JSON schema into the provider request,
     * and at ~100 tools that payload is a non-trivial contributor to TPM usage. Context-aware
     * filtering lets us trim obviously-inapplicable tools (project mutators pre-bind) without
     * the brittleness of hand-maintained allowlists in each composition root.
     */
    val applicability: ToolApplicability get() = ToolApplicability.Always

    suspend fun execute(input: I, ctx: ToolContext): ToolResult<O>
}

/**
 * Declares when a [Tool] should appear in the LLM-facing spec bundle.
 *
 * Consumers ask via [isAvailable]; [ToolRegistry.specs] passes a [ToolAvailabilityContext]
 * at request-assembly time. Purely declarative — no side effects, no I/O — so the decision
 * is safe to recompute per turn.
 */
sealed interface ToolApplicability {
    fun isAvailable(ctx: ToolAvailabilityContext): Boolean

    /** Always visible — the default for tools that work regardless of session state. */
    object Always : ToolApplicability {
        override fun isAvailable(ctx: ToolAvailabilityContext): Boolean = true
    }

    /**
     * Visible only when the session is bound to a current project. Use for tools whose
     * only useful projectId is the binding default — exposing them in an unbound session
     * just lures the model into calling them with a fabricated projectId, failing, and
     * burning a turn. `list_projects` / `create_project` / `switch_project` must stay
     * [Always] so the model has a path *to* a binding.
     */
    object RequiresProjectBinding : ToolApplicability {
        override fun isAvailable(ctx: ToolAvailabilityContext): Boolean =
            ctx.currentProjectId != null
    }

    /**
     * Visible only when the session is bound AND the current project has at least one
     * imported media asset. Use for tools whose essential input is an `assetId` or which
     * operate on existing clips (which in turn need assets) — `add_clip`, `apply_filter`,
     * `apply_lut`, `add_transition`. In an empty project these trap the model into
     * calling with placeholder ids (we saw `assetId="missing"` in real logs); hiding them
     * nudges the agent toward `import_media` / `generate_*` first.
     *
     * Narrower than [RequiresProjectBinding] — a project-bound but asset-empty session
     * still exposes source-graph and track-creation tools.
     */
    object RequiresAssets : ToolApplicability {
        override fun isAvailable(ctx: ToolAvailabilityContext): Boolean =
            ctx.currentProjectId != null && ctx.projectHasAssets
    }
}

/**
 * Minimal snapshot of session state relevant to tool visibility. Intentionally narrow —
 * only holds what [ToolApplicability] checks actually read — so new predicates extend
 * this rather than growing [ToolContext].
 */
data class ToolAvailabilityContext(
    val currentProjectId: io.talevia.core.ProjectId?,
    /**
     * True when the current project has at least one imported [io.talevia.core.domain.MediaAsset].
     * Defaults to false so callers that don't (or can't) cheaply load project state get the
     * conservative answer — [ToolApplicability.RequiresAssets] tools stay hidden, and the
     * model is steered toward `import_media`. The AgentTurnExecutor populates this from
     * an injected ProjectStore when one is available.
     */
    val projectHasAssets: Boolean = false,
)

class ToolContext(
    val sessionId: SessionId,
    val messageId: MessageId,
    val callId: CallId,
    val askPermission: suspend (PermissionRequest) -> PermissionDecision,
    /** Publish an intermediate Part (e.g. RenderProgress) that the UI can stream. */
    val emitPart: suspend (Part) -> Unit,
    /** Read-only history snapshot at the moment dispatch began. */
    val messages: List<MessageWithParts>,
    /**
     * The session's `currentProjectId` at dispatch time, or `null` if the
     * session isn't yet bound to a project (VISION §5.4). Tools whose input
     * carries an optional `projectId` default from this when the agent
     * omits the arg — see [resolveProjectId]. Defaulted to `null` so
     * existing `ToolContext(…)` call sites keep compiling without change.
     */
    val currentProjectId: ProjectId? = null,
    /**
     * Publish a coarse [io.talevia.core.bus.BusEvent] (NOT a streaming Part —
     * use [emitPart] for those). Default no-op so test harnesses that don't
     * wire a bus keep compiling. Production dispatch from
     * [io.talevia.core.agent.AgentTurnExecutor] plumbs this to the same
     * `EventBus` that agent / session signals flow through, so metrics sinks
     * and SSE subscribers see tool-side events (e.g. `AigcCostRecorded`)
     * without bespoke wiring per tool.
     */
    val publishEvent: suspend (io.talevia.core.bus.BusEvent) -> Unit = { },
    /**
     * Signals this dispatch is part of a `replay_lockfile` run — AIGC tools
     * must skip their `AigcPipeline.findCached` lookup so a fresh provider
     * call happens even when an identical [LockfileEntry] already exists.
     * Without this a replay would always fall through to the cache (same
     * inputs → same inputHash → same cached asset id) and never re-exercise
     * the provider, defeating VISION §5.2 "相同 source + 相同 toolchain
     * 重跑产物是否 bit-identical". Default `false` so every existing tool
     * call site keeps the normal cache-first behaviour. See
     * [ReplayLockfileTool][io.talevia.core.tool.builtin.aigc.ReplayLockfileTool]
     * for the one dispatch site that flips this.
     */
    val isReplay: Boolean = false,
    /**
     * Cents cap the session's AIGC spend should not exceed — snapshot of
     * `Session.spendCapCents` at turn start, plumbed here so tools can
     * enforce the gate without re-loading the session record per call.
     * `null` = no cap (guard is a no-op). `0L` = "spend nothing"; positive
     * Long = cents cap. See
     * [AigcBudgetGuard][io.talevia.core.tool.builtin.aigc.AigcBudgetGuard].
     * Defaulted to null so existing `ToolContext(…)` call sites (tests,
     * one-off harnesses) keep compiling.
     */
    val spendCapCents: Long? = null,
) {
    /**
     * Resolve a project id for a tool that accepts an optional explicit
     * `projectId` input. Explicit `input` wins; otherwise fall back to the
     * session's [currentProjectId]; otherwise fail loud with a
     * session-binding hint. Centralises the same 4-line block that used to
     * live in every `projectId`-taking tool's `execute` path (see
     * `docs/decisions/2026-04-21-tool-input-default-projectid-from-context.md`).
     */
    fun resolveProjectId(input: String?): ProjectId = when {
        input != null -> ProjectId(input)
        currentProjectId != null -> currentProjectId
        else -> error(
            "No projectId provided and this session has no current project binding. " +
                "Call switch_project to bind a project to the session, or pass projectId explicitly.",
        )
    }

    /**
     * Session-side mirror of [resolveProjectId]. Session tools whose input
     * carries an optional `sessionId` default from the owning [sessionId]
     * when the agent omits the arg. Unlike [resolveProjectId] there is no
     * error arm — the dispatch always knows which session it is running
     * under, so a missing explicit input always resolves.
     */
    fun resolveSessionId(input: String?): SessionId =
        if (input != null) SessionId(input) else sessionId

    /**
     * Return a new [ToolContext] identical to this one but with [isReplay] =
     * `true`. Used exclusively by `replay_lockfile` to re-dispatch an AIGC
     * tool while telling it to bypass the lockfile cache.
     */
    fun forReplay(): ToolContext = ToolContext(
        sessionId = sessionId,
        messageId = messageId,
        callId = callId,
        askPermission = askPermission,
        emitPart = emitPart,
        messages = messages,
        currentProjectId = currentProjectId,
        publishEvent = publishEvent,
        isReplay = true,
        spendCapCents = spendCapCents,
    )
}

data class ToolResult<O>(
    val title: String,
    /** String passed back to the LLM as `tool_result.content`. */
    val outputForLlm: String,
    /** Typed payload for UI consumption. */
    val data: O,
    val attachments: List<MediaAttachment> = emptyList(),
    val metadata: JsonObject? = null,
)

/**
 * Typed reference to a media artifact produced by a tool — e.g. the mp4 an
 * ExportTool writes. Consumers (UI previewers, subsequent tools, telemetry)
 * can act on it without re-probing the file.
 *
 * `source` is typically a filesystem path or platform URI; `widthPx`/`heightPx`/
 * `durationMs`/`sizeBytes` are best-effort and may be null when unknown.
 */
data class MediaAttachment(
    val mimeType: String,
    val source: String,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
)
