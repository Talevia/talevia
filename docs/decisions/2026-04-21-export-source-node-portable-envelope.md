## 2026-04-21 — export_source_node + import_source_node_from_json (VISION §5.1 portable source reuse)

Commit: `2ab9d1f`

**Context.** VISION §5.1 asks "Source 能不能序列化、版本化、跨 project 复用?"
The intra-instance leg was already covered by `import_source_node`, which
copies a character_ref / style_bible / brand_palette between two projects
*on the same Talevia store*. The cross-instance leg was still missing: a
user who wanted to back up a hand-tuned Mei character_ref, share a
style_bible with a collaborator running their own Talevia, or check a
brand_palette into git alongside brand assets had no portable artifact to
work with. `fork_project` / `save_project_snapshot` only help within one DB,
and `describe_source_node` emits a summary, not a round-trippable payload.

**Decision.** A pair of tools that meet in the middle through a versioned
JSON envelope (`SourceNodeEnvelope`, `formatVersion` = `talevia-source-export-v1`):

- `export_source_node(projectId, nodeId, prettyPrint?)` — walks the leaf + its
  transitive parents in topological order (parents-first, same order as
  `ImportSourceNodeTool.topoCollect`) and serializes the chain with
  `JsonConfig.default` + optional pretty-print. Output exposes the envelope as a
  JSON string on `data.envelope` so the agent can pipe it to `write_file` or
  hand it back to the user.
- `import_source_node_from_json(toProjectId, envelope, newNodeId?)` — decodes
  the envelope and replays it with the same content-addressed dedup semantics
  as the existing intra-instance importer. Rejects unknown formatVersions
  loudly to future-proof against silent schema drift.

Both tools accept identical dedup / collision / rename conventions as the
intra-instance pair so the agent only has to learn one mental model.

**Alternatives considered.**

1. *Extend `import_source_node` with an optional `envelopeJson` arg that makes
   `fromProjectId` / `fromNodeId` optional.* Rejected — JSON Schema `oneOf` +
   `required` patterns are brittle across providers, and the help text would
   have to juggle two mutually exclusive flows. Two focused tools each answer
   one question.
2. *Serialize the raw `SourceNode` with its default serializer directly.*
   Rejected — `SourceNode` carries `revision` and `contentHash`, which are
   implementation details that would either hard-fail or subtly poison the
   importer if the remote instance's hash algorithm ever drifted. The
   envelope strips those to `(id, kind, body, parents)`, the same canonical
   inputs `SourceNode.create` uses to recompute the hash on the receiving side.
   Industry precedent: npm's pack tarball vs. its in-memory tree, Bazel's
   BEP vs. its action graph.
3. *A binary format (protobuf / MsgPack).* Rejected — we have zero cross-tool
   binary needs, human-readable JSON round-trips through `write_file` /
   `read_file` without extra ceremony, and the agent can inspect the envelope
   in chat for debugging. If payload size ever becomes a concern, a v2 format
   can layer compression transparently (the `formatVersion` handshake already
   exists).
4. *Embed the lockfile snapshot alongside.* Rejected for now — lockfile
   entries are keyed on contentHash, so cache hits transfer *automatically*
   once the node is re-imported. Bundling the lockfile would create an
   implicit AIGC replay contract (does the importer also copy the asset
   bytes? Does it revalidate provenance?) that deserves its own design pass.

**Coverage.** `SourceNodeExportImportToolsTest` — round-trip body fidelity,
content-addressed dedup on re-import, topological parent walk, root rename
against a collision, collision-without-rename fails loudly, unknown
formatVersion rejection, malformed JSON rejection, missing-node export
failure, and a compact-vs-pretty size sanity check.

**Registration.** Registered both tools in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`, `apps/ios/Talevia/Platform/AppContainer.swift`.
