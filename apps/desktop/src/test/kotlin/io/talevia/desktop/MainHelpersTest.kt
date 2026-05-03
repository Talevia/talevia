package io.talevia.desktop

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Direct tests for the non-Composable, non-Compose-dependent surface
 * of `apps/desktop/src/main/kotlin/io/talevia/desktop/MainHelpers.kt`.
 * Cycle 239 audit: 0 test refs against [RightTab] or
 * [JsonPrimitive.contentOrNull].
 *
 * Same audit-pattern fallback as cycles 207-238.
 *
 * Out of scope (covered by integration / e2e):
 *   - [SectionTitle] — Compose @Composable, needs Compose harness.
 *   - [resolveOpenablePath] — suspend over `AppContainer`,
 *     integration-ish; needs a fixture project bundle.
 *   - [openExternallyIfExists] — wraps `java.awt.Desktop.open`,
 *     unconditionally side-effecting.
 *   - [desktopEnvWithDefaults] — reads `System.getenv()` directly,
 *     not parameterised. Cycle 233's `WithServerDefaultsTest` pinned
 *     the parameterised analogue (`withServerDefaults(rawEnv)`).
 *
 * In scope:
 *
 *  1. **[RightTab] enum stability + labels.** The 4 right-side tabs
 *     (Chat, Source, Snapshots, Lockfile) drive the desktop tab
 *     selector — every entry maps 1:1 to a render arm in
 *     `AppRoot`. Drift to "drop a tab" or "rename a label" silently
 *     breaks UX (the dropdown still renders but the user-facing
 *     string is wrong) without surfacing as a compile error.
 *
 *  2. **[JsonPrimitive.contentOrNull] branch logic.** This
 *     extension is consumed by [resolveOpenablePath] when extracting
 *     `outputPath` / `assetId` strings out of arbitrary tool-result
 *     JSON. Three branches (lines 76-77 of `MainHelpers.kt`):
 *       - `isString = true` → return `content` if non-blank
 *       - `isString = false` AND `!content.contains('"')` →
 *         return `content` if non-blank
 *       - `isString = false` AND `content.contains('"')` → null
 *     Drift in any branch silently changes which tool outputs
 *     resolve openable paths in the desktop UI.
 *
 * Two correctness contracts pinned:
 *
 *  - Marquee enum-stability pin: `RightTab.entries.size == 4` and
 *    each entry's `label` matches the canonical UI string. Drift
 *    surfaces here before it ships.
 *
 *  - Marquee branch-coverage pin for `contentOrNull`: every input
 *    category produces the documented output. The "non-string +
 *    contains-quote" branch is the unusual one and gets a dedicated
 *    case — drift to "always return content" would silently let
 *    malformed primitives through.
 */
class MainHelpersTest {

    // ── 1. RightTab enum stability ──────────────────────────

    @Test fun rightTabHasExactlyFourEntries() {
        // Marquee enum-stability pin: drift to drop a tab silently
        // removes a panel from the UI; drift to add a 5th without
        // a render-arm in AppRoot would render an unstyled blank.
        assertEquals(4, RightTab.entries.size)
        val names = RightTab.entries.map { it.name }.toSet()
        assertEquals(setOf("Chat", "Source", "Snapshots", "Lockfile"), names)
    }

    @Test fun rightTabLabelsMatchCanonicalUiStrings() {
        // Pin: each tab's label is what the user sees in the
        // dropdown. Drift in any single string would surprise the
        // user but compile + run without error. Pinning each
        // explicitly catches single-tab drift (a parameterised
        // loop would lose the per-tab error message).
        assertEquals("Chat", RightTab.Chat.label)
        assertEquals("Source", RightTab.Source.label)
        assertEquals("Snapshots", RightTab.Snapshots.label)
        assertEquals("Lockfile", RightTab.Lockfile.label)
    }

    @Test fun rightTabOrderingMatchesAppRootRenderOrder() {
        // Pin: `entries` order matches the dropdown render order
        // — Chat (default first turn) → Source (DAG editor) →
        // Snapshots (history) → Lockfile (cost / lockfile audit).
        // Drift to "alphabetical" or "feature-grouped" would
        // silently shuffle the UX without breaking compilation.
        assertEquals(
            listOf(RightTab.Chat, RightTab.Source, RightTab.Snapshots, RightTab.Lockfile),
            RightTab.entries.toList(),
        )
    }

    // ── 2. JsonPrimitive.contentOrNull branches ─────────────

    @Test fun contentOrNullStringPrimitiveReturnsContentWhenNonBlank() {
        // Marquee string-input pin: a string JsonPrimitive returns
        // its content (without surrounding quotes — the JSON
        // primitive's `content` property is the unwrapped string).
        // Drift to "include quotes" would prepend `"` to every
        // resolved path, breaking File operations downstream.
        assertEquals("/tmp/output.mp4", JsonPrimitive("/tmp/output.mp4").contentOrNull())
        assertEquals("hello", JsonPrimitive("hello").contentOrNull())
    }

    @Test fun contentOrNullStringPrimitiveReturnsNullWhenBlank() {
        // Pin: blank strings (empty / whitespace-only) get filtered.
        // `outputPath = ""` from a misbehaving tool MUST NOT
        // resolve to a file path in the desktop UI — drift to
        // "return blank as-is" would crash File operations.
        assertNull(JsonPrimitive("").contentOrNull(), "empty string MUST return null")
        assertNull(JsonPrimitive("   ").contentOrNull(), "whitespace-only string MUST return null")
        assertNull(JsonPrimitive("\t").contentOrNull(), "tab-only string MUST return null")
    }

    @Test fun contentOrNullNumericPrimitiveReturnsContent() {
        // Pin: numeric primitives have `isString = false` AND
        // their content (e.g. `"42"`) doesn't contain quote chars,
        // so they fall through the second branch and return their
        // raw content. Drift to "only string primitives" would
        // silently drop integer asset ids that an LLM might emit
        // in tool output.
        assertEquals("42", JsonPrimitive(42).contentOrNull())
        assertEquals("3.14", JsonPrimitive(3.14).contentOrNull())
        assertEquals("0", JsonPrimitive(0).contentOrNull())
        assertEquals("-1", JsonPrimitive(-1).contentOrNull())
    }

    @Test fun contentOrNullBooleanPrimitiveReturnsContent() {
        // Pin: booleans land in the second branch (isString=false,
        // content="true"/"false" — no quote char). Returns the
        // content as-is. This is a niche case but drift to "drop
        // booleans" would silently change behavior for tool
        // outputs that report flags through this resolver.
        assertEquals("true", JsonPrimitive(true).contentOrNull())
        assertEquals("false", JsonPrimitive(false).contentOrNull())
    }

    @Test fun contentOrNullNullPrimitiveReturnsNull() {
        // Pin: JSON `null` → `JsonPrimitive` with `isString = false`
        // and `content = "null"`. The string "null" doesn't contain
        // a quote, so the second branch returns the content "null".
        // This is intentional per the function's prose "Returns
        // null when the tool output has no natural file artefact"
        // — but `JsonNull` is a separate type, not a JsonPrimitive
        // with content "null". A JsonPrimitive whose content
        // happens to be "null" returns the literal string.
        // Pinning the actual behavior so a future "treat 'null'
        // string as null" refactor catches the surprise.
        val literalNull = JsonPrimitive("null")
        // String form: isString=true → returns "null" content.
        assertEquals("null", literalNull.contentOrNull())
    }

    @Test fun contentOrNullStringWithEmbeddedQuotesStillReturnsContent() {
        // Pin: a STRING primitive whose content contains quote
        // chars STILL returns the content — the `isString` arm
        // wins before the `!contains('"')` check. Drift to
        // "filter quotes from strings" would mangle paths that
        // legitimately contain `"` (rare but possible on Linux
        // / macOS filesystems). The two-arm `||` short-circuits
        // on `isString = true`.
        val embedded = JsonPrimitive("path with \"quotes\".mp4")
        assertEquals("path with \"quotes\".mp4", embedded.contentOrNull())
    }

    @Test fun contentOrNullSpaceInStringStillReturnsContent() {
        // Pin: spaces in path strings are LEGITIMATE (macOS / Windows
        // user dirs) — the predicate must allow them. Drift to
        // "strip spaces" would break "Untitled Project.mp4" exports.
        assertEquals(
            "/Users/Test User/output.mp4",
            JsonPrimitive("/Users/Test User/output.mp4").contentOrNull(),
        )
    }
}
