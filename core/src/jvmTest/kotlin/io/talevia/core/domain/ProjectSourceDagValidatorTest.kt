package io.talevia.core.domain

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit-level coverage of the shared DAG-only validator. Separate from
 * `ValidateProjectToolTest` because load-path warnings rely on this
 * narrow subset — we want regressions here to surface immediately, not
 * via the full tool's broader clip/asset check.
 */
class ProjectSourceDagValidatorTest {

    private fun node(id: String, parents: List<String> = emptyList()): SourceNode =
        SourceNode.create(
            id = SourceNodeId(id),
            kind = "test.kind",
            body = JsonObject(emptyMap()),
            parents = parents.map { SourceRef(SourceNodeId(it)) },
        )

    @Test fun cleanDagReturnsEmpty() {
        var source: Source = Source.EMPTY
        source = source.addNode(node("a"))
        source = source.addNode(node("b", parents = listOf("a")))
        source = source.addNode(node("c", parents = listOf("b")))

        val issues = ProjectSourceDagValidator.validate(source)
        assertTrue(issues.isEmpty(), "clean linear DAG should produce no issues, got: $issues")
    }

    @Test fun emptySourceReturnsEmpty() {
        val issues = ProjectSourceDagValidator.validate(Source.EMPTY)
        assertTrue(issues.isEmpty())
    }

    @Test fun danglingParentReported() {
        var source: Source = Source.EMPTY
        // Child references "ghost" which never existed. addNode itself doesn't reject this.
        source = source.addNode(node("child", parents = listOf("ghost")))

        val issues = ProjectSourceDagValidator.validate(source)
        assertEquals(1, issues.size)
        assertTrue("child" in issues[0], issues[0])
        assertTrue("ghost" in issues[0], issues[0])
        assertTrue("missing parent" in issues[0], issues[0])
    }

    @Test fun cycleReported() {
        // a → b → c → a — cycle of 3 nodes.
        var source: Source = Source.EMPTY
        source = source.addNode(node("a", parents = listOf("b")))
        source = source.addNode(node("b", parents = listOf("c")))
        source = source.addNode(node("c", parents = listOf("a")))

        val issues = ProjectSourceDagValidator.validate(source)
        // At least one cycle message. Exact text varies with DFS start node but
        // should identify the cycle members.
        assertTrue(issues.any { "cycle" in it }, "expected cycle message, got: $issues")
        val cycleMsg = issues.first { "cycle" in it }
        for (id in listOf("a", "b", "c")) {
            assertTrue(id in cycleMsg, "cycle message should name $id, got: $cycleMsg")
        }
    }

    @Test fun multipleIssuesCombined() {
        // Combination: one dangling + one cycle, both should be reported.
        var source: Source = Source.EMPTY
        source = source.addNode(node("orphan", parents = listOf("ghost")))
        source = source.addNode(node("a", parents = listOf("b")))
        source = source.addNode(node("b", parents = listOf("a")))

        val issues = ProjectSourceDagValidator.validate(source)
        assertTrue(issues.any { "missing parent" in it }, issues.toString())
        assertTrue(issues.any { "cycle" in it }, issues.toString())
    }

    @Test fun cycleOnlyReportedOncePerDistinctLoop() {
        // Self-cycle — a → a. Even though DFS could hit it twice (once from
        // a as start, once if we ever re-enter), report-once set dedupes.
        var source: Source = Source.EMPTY
        source = source.addNode(node("a", parents = listOf("a")))
        val issues = ProjectSourceDagValidator.validate(source)
        val cycleMessages = issues.filter { "cycle" in it }
        assertEquals(1, cycleMessages.size, "expected single cycle message, got: $cycleMessages")
    }
}
