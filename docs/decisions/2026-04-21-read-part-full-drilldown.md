## 2026-04-21 — read_part full drill-down (VISION §5.4 专家路径)

Commit: `962eb3e`

**Context.** `describe_message` (earlier loop) returns one `PartSummary`
per part with an ±80-char preview per kind — enough to orient within a
turn, too terse for the debug flows: "what was the full compaction
summary the agent generated?", "what did this tool call actually
return?", "what's the complete text in this reasoning block?". The
describe_message kdoc explicitly flagged this as a follow-up: "drill
further through a future part-level tool." This cycle delivers that
tool.

**Decision.** `ReadPartTool(partId)` — fetches a single `Part` via
`SessionStore.getPart`, serializes it through the sealed-class
`Part.serializer()` + canonical `JsonConfig.default`, and returns the
JSON payload as a `JsonObject` on `data.payload`. Common fields
(`partId`, `messageId`, `sessionId`, `kind`, `createdAtEpochMs`,
`compactedAtEpochMs`) are hoisted onto the Output flat record for
ergonomic access; the kind-specific fields live in `payload` with the
`type` discriminator included so callers can dispatch at their layer.

No pagination / truncation on the payload. A `Part.TimelineSnapshot`
can carry a 20 KB Timeline blob; a `Part.Compaction.summary` is
typically ~1 KB; `Part.Tool` state can be much larger. The caller
asked for a specific partId (they know the kind from a prior
`describe_message` call) and signed up for the full content. If
bounded reads become a real flow, `read_part_prefix(limit)` or
paginated-payload variants are natural follow-ups.

Reuses `session.read` permission. Missing partId fails loudly with a
`describe_message` hint so the agent can rediscover valid ids.

**Alternatives considered.**

1. *Return the raw serialized string rather than a parsed JsonObject.*
   Rejected — a string output forces the caller to re-parse, which
   the wire layer will already do via the tool result JSON. Returning
   a `JsonObject` lets the tool result schema remain strictly typed
   and the LLM sees structured output it can query by field path.
2. *Handcraft a per-kind Output subtype for each Part variant.*
   Rejected — 10 Part subtypes × nested ToolState × TimelineSnapshot
   already requires Timeline's full DTO. Mirroring the whole sealed
   hierarchy in a tool-specific DTO is cargo-culting the data model.
   The raw serialized shape is what the store persists anyway; using
   it directly is the honest surface.
3. *Bound the payload to N KB and return a truncation marker beyond
   that.* Rejected for v1 — requires a design decision on the bound
   (what's "safe"? depends on the LLM's context budget which the tool
   can't know). The agent already calls `describe_message` first to
   see the kind + preview; if the preview suggests a huge payload,
   the agent can skip calling read_part. That's the industry norm
   for drill-down tools (`git cat-file -p` doesn't auto-truncate;
   `jq` doesn't; Postgres `SELECT *` on a large row doesn't).
4. *Fold read_part into describe_message as an `includePayload=true`
   flag.* Rejected — two verbs for two mental models (orient vs.
   fully-read-one-thing), matching the rest of the session lane
   pattern (list/describe pairs, list_lockfile_entries vs.
   describe_lockfile_entry).

**Coverage.** `ReadPartToolTest` — five tests: text part returns full
text verbatim (proves no truncation); compaction part returns the
full summary + the replaced-range fields; `compactedAtEpochMs`
surfaces when the part has been compacted; missing partId fails loud
with a `describe_message` hint; `payload` carries the `type`
discriminator from the sealed-class serialization.

**Registration.** `ReadPartTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. No new permission
(reuses `session.read`).
