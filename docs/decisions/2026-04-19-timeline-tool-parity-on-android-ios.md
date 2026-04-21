## 2026-04-19 — Timeline tool parity on Android + iOS

**Context.** Desktop and server containers register `ApplyFilterTool`,
`AddSubtitleTool`, and `AddTransitionTool`; Android and iOS did not. The
result: the agent on mobile couldn't express "apply a vignette", "add a
line of subtitle text", or "cross-dissolve between clips A and B", even
though all three tools are pure commonMain state mutators on
`Project.timeline` with no platform-specific plumbing whatsoever.

**Decision.** Register all three on `AndroidAppContainer` and iOS
`AppContainer.swift`. Zero new code, just composition.

**Why this isn't blocked by the known Android/iOS engine gap.**
CLAUDE.md calls out that `Media3VideoEngine` and `AVFoundationVideoEngine`
currently fall back to no-op for the filter / transition render passes.
That's an **export-time** engine gap, not an **authoring-time** tool gap.
Tool dispatch mutates `Project.timeline` inside core; it has no engine
call. The tools can be authored today; when the engines catch up, existing
project state will render without any project migration. The alternative
(withholding the tools until the engines catch up) would create a lopsided
agent where mobile can't even express intent that desktop can realize.

**Why not also wire AIGC tools (`generate_image`, `synthesize_speech`,
`transcribe_asset`) on mobile.** Those have real platform wiring — an
`HttpClient`, a platform-appropriate `MediaBlobWriter`, secret storage for
the API key. Android has an in-memory `SecretStore` stub; iOS has none.
Wiring AIGC on mobile needs those prerequisites first. Out of scope for
this commit; tracked for a follow-up when mobile secret stores land.

**Surface area.** Two files touched, three `register(…)` calls each.
Compiles against `:apps:android:compileDebugKotlin` and
`:core:compileKotlinIosSimulatorArm64`.

---
