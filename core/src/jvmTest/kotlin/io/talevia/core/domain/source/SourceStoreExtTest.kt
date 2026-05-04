package io.talevia.core.domain.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.ProjectSummary
import io.talevia.core.domain.Timeline
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Direct tests for [mutateSource] —
 * `core/src/commonMain/kotlin/io/talevia/core/domain/source/SourceStoreExt.kt:14`.
 * Cycle 289 audit: 169 indirect call sites in tests, but
 * **zero direct test file** for the helper. Tests its
 * preserve-non-source / pass-current-source / return-post-
 * mutate-Project / mutex-via-mutate contracts.
 *
 * Same audit-pattern fallback as cycles 207-288.
 *
 * `mutateSource` is the only "Source-only mutation" entry
 * point that all source-side tools (`source_node_action` /
 * `update_source_node_body` / `set_source_node_parents`)
 * funnel through. It's a 1-line extension over
 * `ProjectStore.mutate(...)` that narrows the block scope
 * from `(Project) -> Project` to `(Source) -> Source` while
 * preserving everything else.
 *
 * Drift signals:
 *   - **Drift to mutate non-Source fields** (e.g. drop
 *     `it.copy(source = ...)` for plain `it.copy(...)`)
 *     would silently let timeline / assets / lockfile drift
 *     when source-side tools fire.
 *   - **Drift to skip ProjectStore.mutate**, calling
 *     `block(project.source)` directly without going through
 *     the store, would lose the mutex serialisation that
 *     parallel source-mutating tools rely on.
 *   - **Drift to return the OLD Project instead of the
 *     post-mutate one** would break callers that depend on
 *     reading back the new revision number.
 *
 * Pins via an in-memory [ProjectStore] fake.
 */
class SourceStoreExtTest {

    private val pid = ProjectId("p1")

    /**
     * Minimal `ProjectStore` that stores a single project in
     * a mutable holder. `mutate` runs the block synchronously
     * — sufficient to test `mutateSource` because both
     * functions are sequential against the same call.
     */
    private class FakeProjectStore(initial: Project) : ProjectStore {
        var project: Project = initial
        override suspend fun get(id: ProjectId): Project? =
            if (id == project.id) project else null
        override suspend fun upsert(title: String, project: Project) = error("not used")
        override suspend fun list(): List<Project> = listOf(project)
        override suspend fun delete(id: ProjectId, deleteFiles: Boolean) = error("not used")
        override suspend fun setTitle(id: ProjectId, title: String) = error("not used")
        override suspend fun summary(id: ProjectId): ProjectSummary? = null
        override suspend fun listSummaries(): List<ProjectSummary> = emptyList()
        override suspend fun mutate(
            id: ProjectId,
            block: suspend (Project) -> Project,
        ): Project {
            require(id == project.id) { "mutate of unknown project id" }
            project = block(project)
            return project
        }
    }

    private fun newProject(
        timeline: Timeline = Timeline(tracks = emptyList()),
        source: Source = Source.EMPTY,
        assets: List<MediaAsset> = emptyList(),
    ): Project = Project(
        id = pid,
        timeline = timeline,
        source = source,
        assets = assets,
    )

    private fun nodeWithBody(id: String, body: JsonObject): SourceNode = SourceNode(
        id = SourceNodeId(id),
        kind = "narrative.scene",
        body = body,
        parents = emptyList(),
    )

    // ── Block receives current source ──────────────────────

    @Test fun blockReceivesTheCurrentSource() = runTest {
        // Pin: the block argument MUST be the project's current
        // Source (not a fresh / empty one). Drift here would
        // make read-mutate-write incorrectly start from empty.
        val initialNode = nodeWithBody("a", buildJsonObject { put("k", "v") })
        val initialSource = Source(revision = 7, nodes = listOf(initialNode))
        val store = FakeProjectStore(newProject(source = initialSource))

        var observedSource: Source? = null
        store.mutateSource(pid) { current ->
            observedSource = current
            current
        }

        assertEquals(
            initialSource,
            observedSource,
            "block MUST receive the current Source verbatim",
        )
    }

    @Test fun blockResultBecomesNewSource() = runTest {
        // Marquee replacement pin: the block's return value
        // is what lands as the new Source. Drift to ignoring
        // the return would silently lose the mutation.
        val store = FakeProjectStore(newProject())

        val newSource = Source(
            revision = 1,
            nodes = listOf(nodeWithBody("x", buildJsonObject { put("a", 1) })),
        )
        store.mutateSource(pid) { newSource }

        assertEquals(
            newSource,
            store.project.source,
            "post-mutate Source MUST equal the block's return value",
        )
    }

    // ── Non-source fields preserved ────────────────────────

    @Test fun timelineAssetsAndOtherFieldsPreservedAcrossMutate() = runTest {
        // Marquee preservation pin: drift to plain `it.copy()`
        // (without explicit `source = ...`) would leave timeline
        // / assets unchanged BUT also leave Source unchanged —
        // this test asserts both: Source moves, timeline +
        // assets stay.
        val originalTimeline = Timeline(tracks = emptyList())
        val originalAssets = emptyList<MediaAsset>() // Use empty — assets construction needs many fields
        val store = FakeProjectStore(
            newProject(timeline = originalTimeline, assets = originalAssets),
        )

        store.mutateSource(pid) { current ->
            current.copy(revision = 99, nodes = listOf(nodeWithBody("n1", JsonObject(emptyMap()))))
        }

        assertSame(
            originalTimeline,
            store.project.timeline,
            "timeline reference MUST be preserved across source-only mutate",
        )
        assertEquals(
            originalAssets,
            store.project.assets,
            "assets MUST be preserved across source-only mutate",
        )
        assertEquals(99L, store.project.source.revision, "Source revision MUST land")
        assertEquals(1, store.project.source.nodes.size)
    }

    @Test fun outputProfileLockfileRenderCachePreserved() = runTest {
        // Sister preservation pin: defaults for outputProfile
        // / lockfile / renderCache MUST also be preserved.
        // Drift to drop `copy(source = ...)` in favor of full
        // copy would surface here as default values getting
        // re-instantiated.
        val initial = newProject()
        val store = FakeProjectStore(initial)

        store.mutateSource(pid) { current ->
            current.copy(revision = 1)
        }

        // Reference identity proves the same defaults were
        // carried forward — drift to `it.copy()` would build a
        // new instance.
        assertSame(initial.outputProfile, store.project.outputProfile)
        assertSame(initial.lockfile, store.project.lockfile)
        assertSame(initial.renderCache, store.project.renderCache)
    }

    // ── Return value identity ──────────────────────────────

    @Test fun returnsPostMutateProject() = runTest {
        // Marquee return-value pin: mutateSource MUST return
        // the post-mutate Project (with the new Source), NOT
        // the pre-mutate Project. Drift would break callers
        // that read back the new revision number.
        val store = FakeProjectStore(newProject(source = Source(revision = 0)))

        val returned = store.mutateSource(pid) { current ->
            current.copy(revision = 42)
        }

        assertEquals(
            42L,
            returned.source.revision,
            "returned Project MUST carry the new Source.revision (drift to pre-mutate would surface here)",
        )
        assertEquals(
            store.project,
            returned,
            "returned Project MUST equal the new value held in the store",
        )
    }

    // ── Identity / no-op semantics ─────────────────────────

    @Test fun blockReturningSameSourceIsNoopForSourceField() = runTest {
        // Pin: when the block returns the same Source value,
        // the post-mutate Project's source equals the input.
        // Drift to mutate-anyway would silently bump the
        // contentHash / revision when the user did not
        // request a change.
        val initial = Source(
            revision = 5,
            nodes = listOf(nodeWithBody("n", JsonObject(emptyMap()))),
        )
        val store = FakeProjectStore(newProject(source = initial))

        store.mutateSource(pid) { current -> current }

        assertEquals(
            initial,
            store.project.source,
            "block returning identity MUST leave Source unchanged",
        )
    }

    // ── Block exception propagation ────────────────────────

    @Test fun blockExceptionPropagatesAndDoesNotMutate() = runTest {
        // Pin: a block exception propagates to the caller.
        // The store-side mutation already happened before the
        // exception is thrown if mutate() doesn't catch — so
        // for THIS fake (which executes `project = block(...)`)
        // the in-flight assignment is what mutate() decides.
        // Pin documents the actual behavior: exception in the
        // block surfaces as an exception out of mutateSource,
        // not silent.
        val initial = newProject(source = Source(revision = 0))
        val store = FakeProjectStore(initial)

        assertFailsWith<IllegalStateException> {
            store.mutateSource(pid) { _ ->
                error("boom — block failed mid-mutation")
            }
        }
        // The fake's mutate eagerly evaluates `block(project)`,
        // so on exception the assignment doesn't happen — the
        // store stays at initial. Pin: at minimum, the
        // exception surfaces.
        assertEquals(
            initial.source,
            store.project.source,
            "fake-side: failed block leaves Source unchanged (exception thrown before assignment)",
        )
    }

    // ── Cross-field invariants ─────────────────────────────

    @Test fun mutateSourceLeavesProjectIdStable() = runTest {
        // Pin: ProjectId is the identity-key that all bindings
        // (clip.sourceBinding, lockfile.entries) reference.
        // Drift to let the block mutate id via copy would
        // catastrophically break references. mutateSource
        // MUST preserve it.
        val store = FakeProjectStore(newProject())

        store.mutateSource(pid) { current ->
            current.copy(revision = 1) // touches Source, NOT Project.id
        }

        assertEquals(
            pid,
            store.project.id,
            "Project.id MUST be preserved across source-only mutate",
        )
    }

    @Test fun multipleSequentialMutateSourceCallsCompose() = runTest {
        // Pin: sequential mutateSource calls compose — each
        // call sees the result of the previous.
        val store = FakeProjectStore(newProject(source = Source(revision = 0)))

        store.mutateSource(pid) { it.copy(revision = it.revision + 1) }
        store.mutateSource(pid) { it.copy(revision = it.revision + 1) }
        store.mutateSource(pid) { it.copy(revision = it.revision + 10) }

        assertEquals(
            12L,
            store.project.source.revision,
            "sequential mutateSource calls MUST compose (0 → 1 → 2 → 12)",
        )
    }

    @Test fun delegatesToProjectStoreMutate() = runTest {
        // Marquee delegation pin: mutateSource MUST go through
        // `mutate(id, block)`, not directly mutate the project
        // instance. Verified via a counter-style fake that
        // increments on each `mutate` call.
        var mutateCalls = 0
        val initial = newProject()
        val store = object : ProjectStore {
            var project: Project = initial
            override suspend fun get(id: ProjectId): Project? = project
            override suspend fun upsert(title: String, project: Project) = error("not used")
            override suspend fun list(): List<Project> = listOf(project)
            override suspend fun delete(id: ProjectId, deleteFiles: Boolean) = error("not used")
            override suspend fun setTitle(id: ProjectId, title: String) = error("not used")
            override suspend fun summary(id: ProjectId): ProjectSummary? = null
            override suspend fun listSummaries(): List<ProjectSummary> = emptyList()
            override suspend fun mutate(
                id: ProjectId,
                block: suspend (Project) -> Project,
            ): Project {
                mutateCalls++
                project = block(project)
                return project
            }
        }

        store.mutateSource(pid) { it.copy(revision = 1) }
        store.mutateSource(pid) { it.copy(revision = 2) }

        assertEquals(
            2,
            mutateCalls,
            "mutateSource MUST delegate through ProjectStore.mutate (drift to direct mutation surfaces here)",
        )
    }

    // ── Concurrency: store-side mutex serialisation ────────

    @Test fun parallelMutateSourceCallsSerializeViaProjectStoreMutate() = runTest {
        // Pin: parallel mutateSource calls go through the
        // single ProjectStore.mutate path. The fake here
        // doesn't use a real mutex (it's sequential by
        // suspending from runTest) — but the pin verifies
        // that concurrent launches don't lose updates.
        val store = FakeProjectStore(newProject(source = Source(revision = 0)))

        coroutineScope {
            val a = async { store.mutateSource(pid) { it.copy(revision = it.revision + 1) } }
            val b = async { store.mutateSource(pid) { it.copy(revision = it.revision + 1) } }
            val c = async { store.mutateSource(pid) { it.copy(revision = it.revision + 1) } }
            a.await()
            b.await()
            c.await()
        }

        assertEquals(
            3L,
            store.project.source.revision,
            "3 parallel mutateSource calls MUST each apply (final revision = 3, no lost updates)",
        )
    }

    @Test fun mutateSourceErrorsOnUnknownProjectId() = runTest {
        // Edge: mutateSource against an unknown projectId
        // surfaces the underlying mutate's error path. Pin
        // documents the contract — silently doing nothing
        // would be the wrong fallback.
        val store = FakeProjectStore(newProject())
        val unknown = ProjectId("not-real")

        assertFailsWith<IllegalArgumentException> {
            store.mutateSource(unknown) { it.copy(revision = 1) }
        }
        // Original project untouched.
        assertEquals(0L, store.project.source.revision)
        assertTrue(store.project.id == pid)
    }
}
