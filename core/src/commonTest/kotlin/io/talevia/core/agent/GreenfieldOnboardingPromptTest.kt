package io.talevia.core.agent

import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.prompt.PROMPT_ONBOARDING_LANE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the `projectIsGreenfield` lane wiring in
 * [buildSystemPrompt]. End-to-end wiring (ProjectStore → executor →
 * LlmRequest.systemPrompt) is separately covered by
 * `GreenfieldOnboardingAgentTest`.
 */
class GreenfieldOnboardingPromptTest {

    private val base = "BASE_PROMPT_BODY"
    private val pid = ProjectId("p-onb")
    private val sid = SessionId("s-onb")

    @Test fun greenfieldBoundProjectInjectsOnboardingLaneBetweenBannerAndBase() {
        val out = buildSystemPrompt(base, pid, sid, projectIsGreenfield = true)
        assertNotNull(out)
        assertTrue(out!!.startsWith("Current project: p-onb"), "banner still leads, got:\n$out")
        val bannerEnd = out.indexOf("Current session: ${sid.value}")
        val onboardingStart = out.indexOf("# Greenfield onboarding")
        val baseStart = out.indexOf(base)
        assertTrue(bannerEnd in 0 until onboardingStart, "onboarding lane must follow banner")
        assertTrue(onboardingStart in 0 until baseStart, "onboarding lane must precede base prompt")
        assertTrue(PROMPT_ONBOARDING_LANE in out, "full onboarding fragment must land in the prompt")
    }

    @Test fun populatedProjectOmitsOnboardingLane() {
        val out = buildSystemPrompt(base, pid, sid, projectIsGreenfield = false)
        assertNotNull(out)
        assertFalse(
            "# Greenfield onboarding" in out!!,
            "onboarding lane must disappear once the project has any structure",
        )
        // Banner + base still join with a single blank line separator — nothing
        // (including the onboarding lane) sits between the session line's
        // trailing `)` and the base prompt body.
        assertTrue("never invent one)\n\n$base" in out, "banner → base boundary regressed:\n$out")
    }

    @Test fun unboundSessionNeverShowsOnboardingLaneEvenIfFlagLeaks() {
        // Defensive contract: if the executor ever mis-computes greenfield on a
        // null project (regression), we do NOT print onboarding for <none>.
        // The lane's advice ("scaffold a style_bible first") presupposes a
        // project context; without one, `list_projects / create_project` is
        // the right next step and the base prompt already says so.
        val out = buildSystemPrompt(base, currentProjectId = null, sessionId = sid, projectIsGreenfield = true)
        assertNotNull(out)
        assertFalse(
            "# Greenfield onboarding" in out!!,
            "onboarding lane must not fire without a bound project",
        )
        assertTrue(out.startsWith("Current project: <none>"))
    }

    @Test fun onboardingLaneMentionsSourceFirstDisciplineBeforeAIGC() {
        // The lane's load-bearing signal is: scaffold consistency nodes
        // before generate_*. If someone rewrites the text and drops that
        // ordering, it's no longer an onboarding lane — regression catches it.
        val lane = PROMPT_ONBOARDING_LANE
        val styleIdx = lane.indexOf("style_bible")
        val generateIdx = lane.indexOf("generate_image")
        assertTrue(styleIdx >= 0 && generateIdx >= 0, "lane must mention both style_bible and generate_image")
        assertTrue(
            styleIdx < generateIdx,
            "style_bible must be mentioned before generate_image so the fold-first discipline reads correctly",
        )
    }

    @Test fun greenfieldLaneAndPopulatedPromptDifferByExactlyTheLane() {
        // The delta between greenfield=true and greenfield=false (at the same
        // binding) must be exactly the onboarding lane plus its leading
        // blank-line separator. Nothing else should differ — that's the
        // invariant that keeps the token cost calculable and bounded.
        val withLane = buildSystemPrompt(base, pid, sid, projectIsGreenfield = true)!!
        val withoutLane = buildSystemPrompt(base, pid, sid, projectIsGreenfield = false)!!
        val expectedInsertion = "\n\n$PROMPT_ONBOARDING_LANE"
        assertTrue(
            withLane == withoutLane.replaceFirst("\n\n$base", expectedInsertion + "\n\n$base"),
            "greenfield-on vs greenfield-off must differ only by the onboarding lane segment",
        )
    }

    @Test fun nullBaseReturnsBannerOnlyWithoutTrailingSeparator() {
        // Cycle 74 follow-up: the `when` branch `base == null -> head`
        // pins the contract. Used by container wiring that doesn't have
        // a base prompt configured (rare but exercised in some test rigs).
        val out = buildSystemPrompt(base = null, currentProjectId = pid, sessionId = sid)
        assertNotNull(out)
        assertTrue(out!!.startsWith("Current project: p-onb"))
        assertTrue("Current session: s-onb" in out)
        assertFalse(out.endsWith("\n\n"), "no trailing blank line when there's no base prompt to separate from")
    }

    @Test fun blankBaseReturnsBannerOnlyTreatingWhitespaceAsNull() {
        // The `base.isBlank() -> head` branch: pure whitespace counts as
        // empty (matches Kotlin's String.isBlank semantics). Same shape
        // as null base — head only.
        val outEmpty = buildSystemPrompt(base = "", currentProjectId = pid, sessionId = sid)
        val outWhitespace = buildSystemPrompt(base = "   \n  \n  ", currentProjectId = pid, sessionId = sid)
        val outNull = buildSystemPrompt(base = null, currentProjectId = pid, sessionId = sid)
        assertNotNull(outEmpty)
        assertNotNull(outWhitespace)
        assertNotNull(outNull)
        // All three degenerate forms produce the same banner.
        assertTrue(outEmpty == outNull, "empty base must produce same banner as null base")
        assertTrue(outWhitespace == outNull, "blank-whitespace base must produce same banner as null base")
    }

    @Test fun nullSessionIdProducesProjectLineOnlyBannerNoSessionLine() {
        // Degenerate: a project-bound session that hasn't propagated
        // sessionId yet. The banner builds via `listOfNotNull(projectLine,
        // sessionLine)`, so a null sessionLine just drops it from the
        // banner without leaving an artefact.
        val out = buildSystemPrompt(base, currentProjectId = pid, sessionId = null)
        assertNotNull(out)
        assertTrue(out!!.startsWith("Current project: p-onb"))
        assertFalse(
            "Current session:" in out,
            "no session line when sessionId is null",
        )
        // Banner is exactly the project line; base separates with one blank line.
        assertTrue("(from session binding; call switch_project to change)\n\n$base" in out)
    }

    @Test fun bothNullProjectAndNullSessionWithNullBaseReturnsProjectNoneBanner() {
        // Most degenerate: only the "<none>" project line prints, since
        // sessionId is null AND base is null — head is the bare project
        // line by itself.
        val out = buildSystemPrompt(base = null, currentProjectId = null, sessionId = null)
        assertNotNull(out)
        assertTrue(out!!.startsWith("Current project: <none>"))
        assertFalse("Current session:" in out)
        // Single line, no separators.
        assertEquals(1, out.lines().size, "degenerate form is one line: project=<none>")
    }
}
