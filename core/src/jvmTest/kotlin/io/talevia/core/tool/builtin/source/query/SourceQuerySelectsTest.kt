package io.talevia.core.tool.builtin.source.query

import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [SOURCE_QUERY_SELECTS] /
 * [SOURCE_QUERY_SELECTS_BY_ID] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/source/query/SourceQuerySelects.kt`.
 * Cycle 259 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-258.
 *
 * `SOURCE_QUERY_SELECTS` is the cycle-154 plugin-shape registry
 * for `source_query`'s 10 single-project selects (`nodes` /
 * `dag_summary` / `dot` / `ascii_tree` / `orphans` / `leaves` /
 * `descendants` / `ancestors` / `history` / `node_detail`).
 * The dispatcher reads it at execute() time to look up the
 * select handler; `SOURCE_QUERY_SELECTS_BY_ID` is the indexed
 * form for O(1) lookup.
 *
 * Drift signals:
 *   - New select object created but NOT added to the registry
 *     → silently never dispatched (`runFooQuery` becomes dead
 *     code).
 *   - Two selects sharing the same `id` → `associateBy` keeps
 *     the LAST occurrence; first becomes dead code without a
 *     compile error.
 *   - Per-select id drifts from `SourceQueryTool.SELECT_*`
 *     constant → user-facing input (`select="nodes"`) never
 *     matches the registry key.
 *   - `rowSerializer` drifts from the documented row class →
 *     deserialization of stored rows fails at runtime.
 *
 * Pins three correctness contracts:
 *
 *  1. **Registry has exactly 10 entries** — the kdoc-canonical
 *     count for the single-project select family. Drift to add
 *     a select silently surfaces here before dispatch ships.
 *
 *  2. **Each select's `id` matches the canonical
 *     `SourceQueryTool.SELECT_*` constant** — these constants
 *     are the source of truth for filter-validation arms +
 *     LLM-visible select strings. Drift in either direction
 *     silently de-syncs them.
 *
 *  3. **`SOURCE_QUERY_SELECTS_BY_ID` integrity**: 10 entries
 *     (no id collisions); every id resolves to the right
 *     select object instance. Drift to "duplicate id collapses
 *     entries" would surface here as size != 10.
 *
 * Plus per-select rowSerializer pins matching the documented
 * row class (NodeRow shared by Nodes/Descendants/Ancestors;
 * other selects each have a dedicated row type).
 */
class SourceQuerySelectsTest {

    // ── 1. Registry size + canonical id set ─────────────────

    @Test fun registryHasExactlyTenEntries() {
        // Marquee count pin: 10 single-project selects per the
        // kdoc. Drift to add an 11th without test coverage
        // surfaces here.
        assertEquals(
            10,
            SOURCE_QUERY_SELECTS.size,
            "SOURCE_QUERY_SELECTS MUST have exactly 10 entries (the canonical single-project select family)",
        )
    }

    @Test fun registryIdsCoverAllCanonicalSelectConstants() {
        // Marquee canonical-set pin: the 10 ids in the registry
        // match exactly the 10 SourceQueryTool.SELECT_* constants
        // used in the registry definition. Drift in either side
        // silently de-syncs them.
        val ids = SOURCE_QUERY_SELECTS.map { it.id }.toSet()
        assertEquals(
            setOf(
                SourceQueryTool.SELECT_NODES,
                SourceQueryTool.SELECT_DAG_SUMMARY,
                SourceQueryTool.SELECT_DOT,
                SourceQueryTool.SELECT_ASCII_TREE,
                SourceQueryTool.SELECT_ORPHANS,
                SourceQueryTool.SELECT_LEAVES,
                SourceQueryTool.SELECT_DESCENDANTS,
                SourceQueryTool.SELECT_ANCESTORS,
                SourceQueryTool.SELECT_HISTORY,
                SourceQueryTool.SELECT_NODE_DETAIL,
            ),
            ids,
            "registry ids MUST match the 10 canonical SELECT_* constants",
        )
    }

    @Test fun registryHasNoDuplicateIds() {
        // Pin: drift to "two selects share an id" would let
        // `associateBy` collapse one entry — the survivor wins
        // dispatch silently. Pinning the no-dup invariant
        // catches it before dispatch ships.
        val ids = SOURCE_QUERY_SELECTS.map { it.id }
        assertEquals(
            ids.size,
            ids.toSet().size,
            "registry MUST NOT have duplicate ids (associateBy would silently drop one); got: $ids",
        )
    }

    // ── 2. Per-select id matches canonical constant ─────────

    @Test fun nodesSelectIdMatchesSelectNodes() {
        assertEquals(SourceQueryTool.SELECT_NODES, NodesSourceQuerySelect.id)
    }

    @Test fun dagSummarySelectIdMatchesSelectDagSummary() {
        assertEquals(SourceQueryTool.SELECT_DAG_SUMMARY, DagSummarySourceQuerySelect.id)
    }

    @Test fun dotSelectIdMatchesSelectDot() {
        assertEquals(SourceQueryTool.SELECT_DOT, DotSourceQuerySelect.id)
    }

    @Test fun asciiTreeSelectIdMatchesSelectAsciiTree() {
        assertEquals(SourceQueryTool.SELECT_ASCII_TREE, AsciiTreeSourceQuerySelect.id)
    }

    @Test fun orphansSelectIdMatchesSelectOrphans() {
        assertEquals(SourceQueryTool.SELECT_ORPHANS, OrphansSourceQuerySelect.id)
    }

    @Test fun leavesSelectIdMatchesSelectLeaves() {
        assertEquals(SourceQueryTool.SELECT_LEAVES, LeavesSourceQuerySelect.id)
    }

    @Test fun descendantsSelectIdMatchesSelectDescendants() {
        assertEquals(SourceQueryTool.SELECT_DESCENDANTS, DescendantsSourceQuerySelect.id)
    }

    @Test fun ancestorsSelectIdMatchesSelectAncestors() {
        assertEquals(SourceQueryTool.SELECT_ANCESTORS, AncestorsSourceQuerySelect.id)
    }

    @Test fun historySelectIdMatchesSelectHistory() {
        assertEquals(SourceQueryTool.SELECT_HISTORY, HistorySourceQuerySelect.id)
    }

    @Test fun nodeDetailSelectIdMatchesSelectNodeDetail() {
        assertEquals(SourceQueryTool.SELECT_NODE_DETAIL, NodeDetailSourceQuerySelect.id)
    }

    // ── 3. SOURCE_QUERY_SELECTS_BY_ID integrity ─────────────

    @Test fun byIdMapHasTenEntries() {
        // Pin: associateBy preserves the 10 entries (no id
        // collisions). Drift would surface as size != 10.
        assertEquals(
            10,
            SOURCE_QUERY_SELECTS_BY_ID.size,
            "SOURCE_QUERY_SELECTS_BY_ID MUST have exactly 10 entries (no id collisions)",
        )
    }

    @Test fun byIdMapResolvesEachCanonicalIdToExpectedSelect() {
        // Marquee dispatch-correctness pin: looking up the
        // canonical SELECT_* constant returns the matching
        // select object. Drift in associateBy or in the per-
        // select id would surface here.
        assertEquals(
            NodesSourceQuerySelect,
            SOURCE_QUERY_SELECTS_BY_ID[SourceQueryTool.SELECT_NODES],
        )
        assertEquals(
            DagSummarySourceQuerySelect,
            SOURCE_QUERY_SELECTS_BY_ID[SourceQueryTool.SELECT_DAG_SUMMARY],
        )
        assertEquals(
            DotSourceQuerySelect,
            SOURCE_QUERY_SELECTS_BY_ID[SourceQueryTool.SELECT_DOT],
        )
        assertEquals(
            AsciiTreeSourceQuerySelect,
            SOURCE_QUERY_SELECTS_BY_ID[SourceQueryTool.SELECT_ASCII_TREE],
        )
        assertEquals(
            OrphansSourceQuerySelect,
            SOURCE_QUERY_SELECTS_BY_ID[SourceQueryTool.SELECT_ORPHANS],
        )
        assertEquals(
            LeavesSourceQuerySelect,
            SOURCE_QUERY_SELECTS_BY_ID[SourceQueryTool.SELECT_LEAVES],
        )
        assertEquals(
            DescendantsSourceQuerySelect,
            SOURCE_QUERY_SELECTS_BY_ID[SourceQueryTool.SELECT_DESCENDANTS],
        )
        assertEquals(
            AncestorsSourceQuerySelect,
            SOURCE_QUERY_SELECTS_BY_ID[SourceQueryTool.SELECT_ANCESTORS],
        )
        assertEquals(
            HistorySourceQuerySelect,
            SOURCE_QUERY_SELECTS_BY_ID[SourceQueryTool.SELECT_HISTORY],
        )
        assertEquals(
            NodeDetailSourceQuerySelect,
            SOURCE_QUERY_SELECTS_BY_ID[SourceQueryTool.SELECT_NODE_DETAIL],
        )
    }

    @Test fun byIdMapReturnsNullForUnknownId() {
        // Pin: lookup MUST return null for unknown ids (not
        // crash, not return a fallback). Drift to "default to
        // first entry" would silently mis-route unknown selects.
        assertEquals(
            null,
            SOURCE_QUERY_SELECTS_BY_ID["unknown"],
        )
        assertEquals(
            null,
            SOURCE_QUERY_SELECTS_BY_ID[""],
        )
        assertEquals(
            null,
            SOURCE_QUERY_SELECTS_BY_ID["NODES"], // case-sensitive
        )
    }

    // ── 4. Per-select rowSerializer pins ────────────────────

    @Test fun nodesSelectUsesNodeRowSerializer() {
        // Marquee row-shape pin: drift to a different serializer
        // would silently break deserialization of stored rows.
        assertEquals(
            NodeRow.serializer().descriptor.serialName,
            NodesSourceQuerySelect.rowSerializer.descriptor.serialName,
        )
    }

    @Test fun nodeRowSerializerSharedAcrossThreeSelects() {
        // Pin: NodeRow is shared by Nodes / Descendants /
        // Ancestors per the kdoc-implied design (they all emit
        // the same row shape). Drift to a different serializer
        // for any of these would silently break consumers
        // expecting the shared shape.
        val nodeRowSerialName = NodeRow.serializer().descriptor.serialName
        for (sel in listOf(
            NodesSourceQuerySelect,
            DescendantsSourceQuerySelect,
            AncestorsSourceQuerySelect,
        )) {
            assertEquals(
                nodeRowSerialName,
                sel.rowSerializer.descriptor.serialName,
                "${sel.id} MUST use NodeRow serializer (shared shape)",
            )
        }
    }

    @Test fun perSelectRowSerializersMatchDocumentedRowClasses() {
        // Sister to the previous pin — the dedicated row types
        // for each non-Node-shape select.
        val expectedByIdName = mapOf(
            DagSummarySourceQuerySelect.id to DagSummaryRow.serializer().descriptor.serialName,
            DotSourceQuerySelect.id to DotRow.serializer().descriptor.serialName,
            AsciiTreeSourceQuerySelect.id to AsciiTreeRow.serializer().descriptor.serialName,
            OrphansSourceQuerySelect.id to OrphanRow.serializer().descriptor.serialName,
            LeavesSourceQuerySelect.id to LeafRow.serializer().descriptor.serialName,
            HistorySourceQuerySelect.id to BodyRevisionRow.serializer().descriptor.serialName,
            NodeDetailSourceQuerySelect.id to NodeDetailRow.serializer().descriptor.serialName,
        )
        for ((id, expectedName) in expectedByIdName) {
            val select = SOURCE_QUERY_SELECTS_BY_ID[id]
            assertNotNull(select, "select $id MUST be in registry")
            assertEquals(
                expectedName,
                select.rowSerializer.descriptor.serialName,
                "select $id MUST use rowSerializer with serialName '$expectedName'",
            )
        }
    }

    // ── 5. Object instances are stable singletons ───────────

    @Test fun selectObjectsAreSameInstanceInRegistryAndDirectAccess() {
        // Pin: per `internal object FooSourceQuerySelect : ...`
        // each select is a Kotlin singleton object — the
        // registry list MUST contain the SAME instance. Drift
        // to "registry constructs new instances" would silently
        // change identity (no functional break, but breaks
        // dispatcher's assumption that select objects are
        // shareable singletons).
        val byObject = SOURCE_QUERY_SELECTS.associateBy { it.id }
        assertTrue(
            byObject[SourceQueryTool.SELECT_NODES] === NodesSourceQuerySelect,
            "registry's NodesSourceQuerySelect entry MUST be the SAME singleton instance",
        )
        assertTrue(
            byObject[SourceQueryTool.SELECT_HISTORY] === HistorySourceQuerySelect,
            "registry's HistorySourceQuerySelect entry MUST be the SAME singleton instance",
        )
    }
}
