## 2026-04-19 — FFmpeg subtitle rendering via `drawtext`

**Context.** `add_subtitle` / `add_subtitles` had been shipping for a
while — they wrote `Clip.Text` entries onto a `Track.Subtitle` in the
canonical timeline and stored the styling. But the FFmpeg engine's
`render()` only ever iterated the Video track: audio tracks were
silently dropped (for Subtitle too). So the UX looked like "the agent
captioned the video" but the exported mp4 had no burned-in text.
Closest VISION gap: §5.2 compiler lane — a source-kind exists (captions)
but the compiler pass that turns it into an artifact wasn't wired.

**Decision.** Extend `FfmpegVideoEngine.render()` to collect every
`Clip.Text` on every `Track.Subtitle` and chain an ffmpeg `drawtext`
filter per clip after the `concat=…` step. Each drawtext is gated by
`enable='between(t,start,end)'` using the clip's timeline range, so
captions appear/disappear on the correct timeline beat. No splitting
of the timeline; drawtext overlays are composable with the existing
per-clip filter chain because they attach *after* concat.

**Positioning / font defaults.** Centered horizontally
(`x=(w-text_w)/2`), anchored near the bottom
(`y=h-text_h-<margin>`). Margin scales with output height
(`height * 48/1080`, floor 16 pixels) so the caption sits ~4.4% from
the frame edge regardless of resolution — matches how broadcast
lower-thirds behave. No `fontfile=` option is passed: that forces a
per-platform font path and breaks anyone with a different fontconfig
setup; leaving it off lets ffmpeg fall back to its built-in default
font, which works across Linux/macOS/Windows ffmpeg builds. The
`TextStyle.fontFamily` field is preserved in Core but treated as a
hint at render time — adding true custom-font support is a
follow-up (tool needs to register font assets via `import_media` and
expose them through `MediaPathResolver`).

**What TextStyle fields flow through today.**
- `fontSize` → `fontsize=`. Passed through unchanged.
- `color` → `fontcolor=`. `#RRGGBB` is normalised to ffmpeg's
  `0xRRGGBB` form; anything else passes through (named colors like
  `red` or `white` work; malformed values fail loud at render time).
- `backgroundColor` → adds `box=1:boxcolor=…:boxborderw=10` so the
  text gets a solid padding box when the user supplies a background.
  Omitted (null) → no box, transparent overlay.
- `bold` / `italic` → *not yet* applied; ffmpeg needs a font file for
  those variants. Future work when custom fonts land.

**Filtergraph escape strategy — `'` is the hard part.** Inside
single-quoted filter-option values, ffmpeg treats `:` `,` `;` `[`
`]` `\` as literal, which makes quoting the best default. The catch
is that apostrophes can't appear inside the quoted section at all —
the standard ffmpeg idiom is to close the quote, backslash-escape
the apostrophe, then reopen: `'hi'\''there'` → `hi'there`. The tool
also escapes `%` as `\%` because drawtext's text value runs through
the `%{…}` expansion pass after filter-arg parsing; an unescaped `%`
would otherwise trigger an expansion and fail loud or render wrong
output. `enable=` values (expressions like `between(t,1,2)`)
continue to use the outside-quotes backslash-escape form for
commas because expression syntax doesn't need quote-wrapping and
the flat form is easier to read in logs.

**Why `drawtext` and not SRT sidecar / burn-in-before-concat.**
Considered (a) writing an SRT file alongside the output and adding a
soft-subtitle stream, and (b) burning each clip's subtitles into the
per-clip video stream before the concat step.
- SRT rejected: "soft" captions aren't rendered until a player
  decodes them; the export artifact shown to the user would appear to
  have no captions, which defeats the VISION §2 promise that the
  compiler produces a finished artifact. Also: many players ignore
  mp4 soft-subs.
- Burn-before-concat rejected: subtitles aren't owned by individual
  video clips — they're a parallel track with their own timeline
  positions. Mapping each subtitle to "the video clip it falls
  inside" requires time-window joins that get wrong at clip edges,
  and adds ordering constraints on the filter chain (one drawtext per
  clip-segment). A single post-concat drawtext chain is simpler and
  is exactly how professional editors describe captions conceptually
  ("a track on top of the composite"). The `enable=` gate replaces
  per-clip scoping without needing to know clip boundaries at all.

**E2E test: auto-skip on ffmpeg builds without libfreetype.** The
`drawtext` filter is only compiled into ffmpeg when libfreetype is
linked in. Many minimal builds (e.g. the default homebrew bottle as
of ffmpeg 8.1 on macOS) omit freetype, making the filter simply
unavailable ("No such filter: 'drawtext'"). Rather than make the
CI/dev test fail on every machine with a stripped ffmpeg, the new
`renderWithSubtitleProducesVideo` E2E probes `ffmpeg -filters` for
drawtext and skips if it's missing. Unit tests
(`DrawtextChainTest`) verify the filtergraph string generation
without needing ffmpeg to be complete, so escape-bug regressions
still get caught in CI. The gap is noted in CLAUDE.md "Known
incomplete" so future contributors know what a caption-less export
means.

**Stderr surfacing improvement (piggyback).** The render loop now
keeps a rolling tail (~40 lines) of non-progress stderr lines and
includes the filtered subset (error/AVFilterGraph/invalid-argument
lines) in `RenderProgress.Failed.errorMessage`. Previously failures
surfaced as just "ffmpeg exited 8", forcing the user to re-run by
hand with `-loglevel debug`. Adding the tail caught the drawtext
gap immediately during this task's E2E dev loop — cheap enough to
justify landing in the same commit.

**Cross-platform status.** FFmpeg now renders subtitles. Media3
(Android) and AVFoundation (iOS) engines still ignore Subtitle
tracks — same gap shape as filter rendering on those engines.
Those are separate tasks on the §5.2 compiler-parity track.

---
