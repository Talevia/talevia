package io.talevia.core.agent.prompt

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Direct content tests for [PROMPT_EXTERNAL_LANE] —
 * `core/src/commonMain/kotlin/io/talevia/core/agent/prompt/PromptExternalLane.kt:15`.
 * Cycle 284 audit: 0 prior test refs. Closes the 4-lane
 * prompt-content family (cycle 281 PROMPT_DUAL_USER, cycle 282
 * PROMPT_AIGC_LANE, cycle 283 PROMPT_EDITING_LANE).
 *
 * Same audit-pattern fallback as cycles 207-283. Wrap-tolerance
 * idiom (`flat` whitespace-collapsed view) banked in cycle 281.
 *
 * `PROMPT_EXTERNAL_LANE` is in the static base prompt (every
 * turn). Teaches the agent the per-tool semantics for **9
 * sections** covering external resources + planning + binding +
 * rules + bias-toward-action:
 *
 *   - fs tools (read_file / write_file / edit_file / multi_edit /
 *     list_directory / glob / grep)
 *   - web_fetch (1 MB default, 5 MB cap, host-gated permission)
 *   - web_search (Tavily, 5 default / 20 max hits, query-gated
 *     permission)
 *   - bash (escape hatch, 30s default / 10min ceiling)
 *   - todowrite (multi-step scratchpad, one in_progress)
 *   - draft_plan (kubectl-diff-before-apply for consequential
 *     batches)
 *   - session-project binding (currentProjectId / switch_project /
 *     banner)
 *   - Rules
 *   - Bias toward action (execute, don't clarify)
 *
 * Drift signals:
 *   - **Drop "NEVER use these tools to read or edit the Project
 *     JSON" anti-pattern** → LLM bypasses typed Project tools,
 *     silently corrupts timeline-snapshot / staleness invariants.
 *   - **Soften the bash anti-pattern list ("Don't `cat` a file
 *     when you can `read_file`")** → LLM falls back to bash for
 *     ops the typed tools handle better, blowing through bash
 *     permission grants.
 *   - **Drop the bias-toward-action standing order** → LLM
 *     regresses to clarifying-question chains on "make me a vlog"
 *     intents.
 *   - **Drift in budget caps** (`1 MB` / `5 MB` / `30 seconds` /
 *     `10-minute`) → LLM mis-paces requests, hits caps it
 *     wouldn't have if the documented limits were known.
 *
 * Pins via marker-substring presence on the whitespace-flat
 * view.
 */
class PromptExternalLaneTest {

    private val flat: String = PROMPT_EXTERNAL_LANE.replace(Regex("\\s+"), " ")

    // ── Section headers ─────────────────────────────────────

    @Test fun allNineSectionHeadersPresent() {
        for (header in listOf(
            "# External files (fs tools)",
            "# Web fetch",
            "# Web search",
            "# Shell commands (bash)",
            "# Agent planning (todos)",
            "# Pre-commit plans (draft_plan)",
            "# Session-project binding (VISION §5.4)",
            "# Rules",
            "# Bias toward action",
        )) {
            assertTrue(
                header in flat,
                "lane MUST contain section header '$header'",
            )
        }
    }

    // ── # External files — fs tool roster + preferences ────

    @Test fun fsToolRosterAllListed() {
        // Marquee pin: complete fs tool roster present so the
        // LLM has the discoverable surface. Drift to drop one
        // would make it invisible at planning time.
        for (tool in listOf(
            "read_file", "write_file", "edit_file", "multi_edit",
            "list_directory", "glob", "grep",
        )) {
            assertTrue(
                tool in flat,
                "fs section MUST name $tool tool",
            )
        }
    }

    @Test fun editPreferenceLadderForFsWrites() {
        // Marquee preference pin: edit_file > write_file
        // (cheaper substring), multi_edit > chain of
        // edit_file (atomic). Drift would make LLM
        // re-emit whole files when surgical edits would do.
        assertTrue(
            "Prefer `edit_file` over `write_file`" in flat,
            "MUST anchor edit_file > write_file preference",
        )
        assertTrue(
            "prefer `multi_edit` over a chain" in flat,
            "MUST anchor multi_edit > chain-of-edit_file preference",
        )
        assertTrue(
            "all or nothing" in flat,
            "MUST anchor multi_edit atomicity invariant (all or nothing)",
        )
    }

    @Test fun neverUseFsToolsOnTaleviaState() {
        // Marquee anti-pattern pin: do NOT touch Project JSON
        // / Talevia DB / ~/.talevia/ via fs tools. Drift would
        // bypass typed tools, silently corrupting timeline-
        // snapshot / staleness / consistency-binding
        // invariants.
        assertTrue(
            "NEVER use these tools" in flat,
            "MUST contain the explicit NEVER directive",
        )
        assertTrue(
            "Project JSON" in flat,
            "MUST forbid fs access to Project JSON",
        )
        assertTrue(
            "~/.talevia/" in flat,
            "MUST forbid fs access under ~/.talevia/",
        )
        assertTrue(
            "silently corrupts invariants" in flat,
            "MUST justify the prohibition with the corruption argument",
        )
    }

    @Test fun fsPathsAlwaysAbsoluteAndGrepBeatsReadFile() {
        // Pin: paths absolute + grep > read_file when looking
        // for content.
        assertTrue(
            "always absolute" in flat,
            "MUST anchor absolute-paths invariant",
        )
        assertTrue(
            "tools reject relative paths" in flat,
            "MUST anchor that the tools reject relative at the boundary",
        )
        assertTrue(
            "prefer `grep` over reading each file" in flat,
            "MUST anchor grep > read_file preference for content lookup",
        )
        assertTrue(
            "Grep skips binary" in flat,
            "MUST anchor grep's auto-skip of binary / oversized files",
        )
        assertTrue(
            "Binary assets" in flat &&
                "import_media" in flat,
            "MUST redirect binary asset reads to import_media (not read_file)",
        )
    }

    // ── # Web fetch — caps + host-gated permission ─────────

    @Test fun webFetchCapsAndHostPermission() {
        // Marquee pin: 1 MB default / 5 MB hard cap + host-
        // gated permission. Drift in caps would mis-pace
        // requests; drift in host gating would over-grant.
        assertTrue("web_fetch" in flat, "MUST name web_fetch tool")
        assertTrue(
            "1 MB" in flat,
            "MUST document 1 MB default response cap",
        )
        assertTrue(
            "5 MB hard cap" in flat,
            "MUST document 5 MB hard ceiling",
        )
        assertTrue(
            "URL **host**" in flat ||
                "URL host" in flat,
            "MUST anchor host-level permission gating",
        )
        assertTrue(
            "Don't use `web_fetch` to \"browse\"" in flat,
            "MUST forbid using web_fetch as a browser",
        )
        assertTrue(
            "no pagination, no JS, no cookies" in flat,
            "MUST justify the no-browse rule with concrete capability gaps",
        )
    }

    // ── # Web search — Tavily + caps + don't-loop rule ─────

    @Test fun webSearchProviderCapsAndDontLoopRule() {
        // Marquee pins: backing provider name (Tavily), 5/20
        // hit caps, lowercase-query gating, don't-loop rule.
        assertTrue("web_search" in flat, "MUST name web_search tool")
        assertTrue(
            "Tavily when wired" in flat,
            "MUST name Tavily as the current backing search provider",
        )
        assertTrue(
            "lower-cased query" in flat,
            "MUST anchor lowercase-query permission gating",
        )
        assertTrue(
            "Default cap is 5 hits" in flat,
            "MUST document default hit cap (5)",
        )
        assertTrue(
            "max 20" in flat,
            "MUST document hit cap ceiling (20)",
        )
        assertTrue(
            "Don't loop on the same query" in flat,
            "MUST forbid loop-on-same-query anti-pattern",
        )
        assertTrue(
            "refine the query" in flat,
            "MUST direct toward query-refinement instead of more results",
        )
    }

    // ── # Shell — bash escape-hatch discipline ─────────────

    @Test fun bashAntiPatternListAndTimeoutCeiling() {
        // Marquee pin: do-NOT-when-typed-tool-exists list +
        // 30s default + 10-minute hard ceiling.
        assertTrue("bash" in flat, "MUST name bash tool")
        assertTrue(
            "Do NOT use `bash`" in flat,
            "MUST contain the explicit Do NOT directive against bash misuse",
        )
        for (anti in listOf(
            "Don't `cat` a file",
            "Don't `grep -r`",
            "Don't re-implement `export`",
        )) {
            assertTrue(
                anti in flat,
                "bash anti-pattern list MUST contain '$anti'",
            )
        }
        assertTrue(
            "30-second default" in flat,
            "MUST document 30s default timeout",
        )
        assertTrue(
            "10-minute hard ceiling" in flat,
            "MUST document 10-minute hard ceiling",
        )
        assertTrue(
            "no stdin" in flat,
            "MUST anchor no-stdin invariant",
        )
        assertTrue(
            "first command token" in flat,
            "MUST anchor first-token permission gating (approving git covers status/diff/log)",
        )
    }

    // ── # Agent planning — todowrite discipline ────────────

    @Test fun todowriteDisciplineAndStatusFlow() {
        // Marquee pin: one in_progress at a time + flip
        // immediately not batch + cancelled-not-dropped.
        // Drift would re-enable batched flips losing
        // mid-task progress signals.
        assertTrue("todowrite" in flat, "MUST name todowrite tool")
        assertTrue(
            "fully replaces the current plan" in flat,
            "MUST anchor full-replace semantic (not delta updates)",
        )
        assertTrue(
            "exactly one item `in_progress`" in flat,
            "MUST anchor exactly-one-in_progress invariant",
        )
        assertTrue(
            "rather than batching at the end" in flat,
            "MUST anchor flip-immediately-not-batch rule",
        )
        assertTrue(
            "use `cancelled` for items" in flat,
            "MUST anchor cancelled-not-dropped semantic for irrelevant items",
        )
        assertTrue(
            "Do NOT use it for single-call tasks" in flat,
            "MUST forbid todowrite for single-step tasks",
        )
    }

    // ── # Pre-commit plans — draft_plan flow ───────────────

    @Test fun draftPlanFourStepFlow() {
        // Marquee pin: 4-step flow + kubectl-diff metaphor +
        // pending_approval default.
        assertTrue("draft_plan" in flat, "MUST name draft_plan tool")
        assertTrue(
            "kubectl diff before apply" in flat,
            "MUST anchor the kubectl-diff-before-apply metaphor",
        )
        assertTrue(
            "approvalStatus=pending_approval" in flat,
            "MUST anchor pending_approval default",
        )
        assertTrue(
            "approvalStatus=approved" in flat,
            "MUST document approved status flip",
        )
        assertTrue(
            "approved_with_edits" in flat,
            "MUST document approved_with_edits status",
        )
        assertTrue(
            "pending → in_progress → completed" in flat,
            "MUST document the per-step status progression",
        )
        assertTrue(
            "approvalStatus=rejected" in flat,
            "MUST document rejected status + don't-retry rule",
        )
    }

    @Test fun draftPlanVsTodowriteDelimiter() {
        // Pin: lane MUST disambiguate the two planning tools
        // — drift to overlap them re-enables LLM picking the
        // wrong one for the wrong scope.
        assertTrue(
            "instead of (not alongside) `todowrite`" in flat,
            "MUST anchor draft_plan-instead-of-todowrite (not both)",
        )
        assertTrue(
            "skip `draft_plan`" in flat,
            "MUST anchor that 1-2 step requests skip draft_plan",
        )
        assertTrue(
            "consequential" in flat.lowercase(),
            "MUST anchor on consequential-batches threshold for draft_plan",
        )
    }

    // ── # Session-project binding ───────────────────────────

    @Test fun sessionProjectBindingBannerAndSwitchProject() {
        // Marquee pin: currentProjectId binding + per-turn
        // banner + switch_project verb + binding survives
        // turns + don't-guess-from-conversation rule.
        assertTrue(
            "currentProjectId" in flat,
            "MUST name the currentProjectId binding field",
        )
        assertTrue(
            "Current project: <id>" in flat,
            "MUST document the per-turn banner format",
        )
        assertTrue(
            "switch_project" in flat,
            "MUST name the switch_project tool",
        )
        assertTrue(
            "survives turns and app restarts" in flat,
            "MUST anchor binding persistence invariant",
        )
        assertTrue(
            "Don't guess a project id" in flat,
            "MUST forbid guess-from-conversation when banner is <none>",
        )
        assertTrue(
            "list_projects" in flat &&
                "create_project" in flat,
            "MUST list the two ways to resolve <none> banner (list_projects + switch / create_project)",
        )
    }

    @Test fun optionalProjectIdSubsetEnumerated() {
        // Pin: which tools take optional projectId vs which
        // require explicit. Drift would make LLM omit
        // projectId for tools that still need it.
        assertTrue(
            "`project_query`, `clip_action`, `describe_project`" in flat,
            "MUST enumerate the optional-projectId subset (project_query / clip_action / describe_project)",
        )
        assertTrue(
            "use the session binding" in flat,
            "MUST anchor that omitting projectId on the optional-subset uses session binding",
        )
    }

    // ── # Rules ─────────────────────────────────────────────

    @Test fun rulesUnregisteredToolFallback() {
        // Pin: the named-tool-not-in-toolset = unregistered
        // rule, plus the typical unregistered AIGC tools.
        assertTrue(
            "If a request needs a capability that doesn't exist as a Tool" in flat,
            "MUST anchor the missing-capability honest-disclosure rule",
        )
        assertTrue(
            "if a named tool isn't listed in your toolset, it is not available" in flat,
            "MUST anchor the toolset-as-source-of-truth rule",
        )
        assertTrue(
            "generate_music" in flat &&
                "upscale_asset" in flat,
            "MUST cite the typical unregistered AIGC tools (generate_music / upscale_asset)",
        )
    }

    // ── # Bias toward action ────────────────────────────────

    @Test fun biasTowardActionStandingOrder() {
        // Marquee pin: "make me a video" is execute-not-
        // clarify standing order. Drift here re-enables
        // clarifying-question chains.
        assertTrue(
            "Make me a <video | vlog | short | ad>" in flat,
            "MUST cite the canonical 'make me a' phrase (with CJK sister)",
        )
        assertTrue(
            "帮我做一个 X" in flat,
            "MUST cite the CJK sister phrase",
        )
        assertTrue(
            "standing order to **execute**" in flat ||
                "standing order to execute" in flat,
            "MUST anchor standing-order-to-execute framing",
        )
        assertTrue(
            "Pick sensible defaults and ship v1" in flat,
            "MUST anchor v1-then-iterate semantic",
        )
    }

    @Test fun atMostOneFollowUpAndSafeBetDefaults() {
        // Pin: "at most one follow-up" + "Never chain bullet-
        // list menus" + the three safe-bet style_bibles
        // (cinematic-warm / clean-minimal / soft-pastel).
        assertTrue(
            "at most **one** follow-up" in flat ||
                "at most one follow-up" in flat,
            "MUST anchor the at-most-one-follow-up rule",
        )
        assertTrue(
            "Never chain bullet-list menus" in flat,
            "MUST forbid chained bullet-list menus (sister of DUAL_USER lane)",
        )
        for (style in listOf("cinematic-warm", "clean-minimal", "soft-pastel")) {
            assertTrue(
                style in flat,
                "MUST list '$style' as a safe-bet style_bible default",
            )
        }
        assertTrue(
            "15 / 30 / 60s" in flat,
            "MUST list standard durations (15/30/60s)",
        )
        assertTrue(
            "16:9 unless the project implies vertical" in flat,
            "MUST anchor platform-default 16:9 with vertical exception",
        )
    }

    @Test fun askOnlyForUserOnlyInformation() {
        // Pin: ask only for things only the user has —
        // aesthetic prefs are explicitly NOT user-only.
        assertTrue(
            "Ask only when blocked on information only the user has" in flat,
            "MUST anchor the user-only-info rule for clarifying questions",
        )
        assertTrue(
            "Aesthetic preference is never user-only" in flat,
            "MUST explicitly disclaim aesthetic prefs as user-only",
        )
        assertTrue(
            "Report decisions inline" in flat,
            "MUST direct inline-report-decisions over front-loaded-questions",
        )
    }

    // ── Length + trim contracts ─────────────────────────────

    @Test fun lengthIsBoundedAndMeaningful() {
        val s = PROMPT_EXTERNAL_LANE
        assertTrue(
            s.length > 4000,
            "lane content MUST be > 4000 chars; got: ${s.length}",
        )
        assertTrue(
            s.length < 12_000,
            "lane content MUST be < 12000 chars; got: ${s.length}",
        )
    }

    @Test fun laneIsTrimmedNoLeadingOrTrailingBlankLines() {
        val s = PROMPT_EXTERNAL_LANE
        assertTrue(
            s == s.trim(),
            "lane MUST be trimmed (no leading/trailing whitespace)",
        )
    }
}
