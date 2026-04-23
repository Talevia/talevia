package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Round-trips the lockfile-driven stale-clip detector through `find_stale_clips`.
 * The detector itself has unit coverage on the domain extension; this suite proves
 * the tool surfaces the right shape to the agent and respects the legacy /
 * imported-media skip rules.
 */
class FindStaleClipsToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun newStore(): FileProjectStore = ProjectStoreTestKit.create()

    private fun videoClip(id: String, asset: AssetId, binding: Set<SourceNodeId> = emptySet()): Clip.Video =
        Clip.Video(
            id = ClipId(id),
            timeRange = TimeRange(start = 0.seconds, duration = 1.seconds),
            sourceRange = TimeRange(start = 0.seconds, duration = 1.seconds),
            assetId = asset,
            sourceBinding = binding,
        )

    private suspend fun seedProjectWithClip(
        store: FileProjectStore,
        projectId: ProjectId,
        clip: Clip.Video,
    ) {
        val track = Track.Video(id = TrackId("v0"), clips = listOf(clip))
        store.upsert(
            "demo",
            Project(id = projectId, timeline = Timeline(tracks = listOf(track))),
        )
    }

    private fun fakeProvenance(seed: Long = 1L): GenerationProvenance =
        GenerationProvenance(
            providerId = "fake",
            modelId = "fake-model",
            modelVersion = null,
            seed = seed,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 1_700_000_000_000L,
        )

    private suspend fun appendLockfile(
        store: FileProjectStore,
        projectId: ProjectId,
        entry: LockfileEntry,
    ) {
        store.mutate(projectId) { it.copy(lockfile = it.lockfile.append(entry)) }
    }

    @Test fun freshProjectReportsZeroStale() = runTest {
        val store = newStore()
        val pid = ProjectId("p-fresh")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        // Set up source + lockfile so the snapshot matches the current source hash.
        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
        }
        val nowHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))
        appendLockfile(
            store,
            pid,
            LockfileEntry(
                inputHash = "h1",
                toolId = "generate_image",
                assetId = asset,
                provenance = fakeProvenance(),
                sourceBinding = setOf(SourceNodeId("mei")),
                sourceContentHashes = mapOf(SourceNodeId("mei") to nowHash),
            ),
        )

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(0, out.staleClipCount)
        assertEquals(1, out.totalClipCount)
        assertTrue(out.reports.isEmpty())
    }

    @Test fun characterEditFlagsBoundClip() = runTest {
        val store = newStore()
        val pid = ProjectId("p-stale")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
        }
        val originalHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))
        appendLockfile(
            store,
            pid,
            LockfileEntry(
                inputHash = "h1",
                toolId = "generate_image",
                assetId = asset,
                provenance = fakeProvenance(),
                sourceBinding = setOf(SourceNodeId("mei")),
                sourceContentHashes = mapOf(SourceNodeId("mei") to originalHash),
            ),
        )

        // User edits the character — content hash changes.
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red hair"),
                    ),
                )
            }
        }

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(1, out.staleClipCount)
        assertEquals(1, out.reports.size)
        val report = out.reports.single()
        assertEquals("c-1", report.clipId)
        assertEquals(asset.value, report.assetId)
        assertEquals(listOf("mei"), report.changedSourceIds)
    }

    @Test fun reportsOnlyTheBoundNodesThatChanged() = runTest {
        val store = newStore()
        val pid = ProjectId("p-multi")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
                .let { s -> s.addStyleBible(SourceNodeId("noir"), StyleBibleBody(name = "noir", description = "noir, high contrast")) }
        }
        val src = store.get(pid)!!.source
        val meiHash = src.deepContentHashOf(SourceNodeId("mei"))
        val noirHash = src.deepContentHashOf(SourceNodeId("noir"))
        appendLockfile(
            store,
            pid,
            LockfileEntry(
                inputHash = "h1",
                toolId = "generate_image",
                assetId = asset,
                provenance = fakeProvenance(),
                sourceBinding = setOf(SourceNodeId("mei"), SourceNodeId("noir")),
                sourceContentHashes = mapOf(
                    SourceNodeId("mei") to meiHash,
                    SourceNodeId("noir") to noirHash,
                ),
            ),
        )

        // Only the style bible changes.
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("noir")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        StyleBibleBody.serializer(),
                        StyleBibleBody(name = "noir", description = "vibrant pop"),
                    ),
                )
            }
        }

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(1, out.staleClipCount)
        val report = out.reports.single()
        // Detector reports only the *direct* drifted ids; mei is unchanged so absent.
        assertEquals(listOf("noir"), report.changedSourceIds)
    }

    @Test fun transitiveConsistencyEditFlagsGrandchildBoundClip() = runTest {
        // VISION §5.5 / source-consistency-propagation runtime coverage.
        // Two existing tests each cover half the lane:
        //   • `TransitiveSourceHashTest.staleClipsFromLockfileFlagsClipWhenGrandparentEdited`
        //     proves grandparent-edit → grandchild-deep-hash-drift for generic
        //     (`test.generic`) nodes, but bypasses the tool and the consistency
        //     kinds (`character_ref` / `style_bible`).
        //   • `characterEditFlagsBoundClip` (above) proves a consistency-node
        //     edit surfaces through the tool, but for a *directly-bound*
        //     character_ref only; the parent-chain path isn't exercised.
        // This test pins the intersection — a consistency-kind DAG where the
        // clip binds the child (`mei` character_ref) and the *parent*
        // (`noir` style_bible) is edited; the tool must still report the clip
        // stale, with the child's id listed in `changedSourceIds` (deep-hash
        // drift surfaces on the descendant even though its shallow contentHash
        // is unchanged). This is the combined-regression shape — a future
        // refactor that re-introduces shallow-hash-only comparison, or that
        // drops `parents = [noir]` wiring from `addCharacterRef`'s signature,
        // breaks this test.
        val store = newStore()
        val pid = ProjectId("p-transitive")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset, binding = setOf(SourceNodeId("mei"))))

        // noir is the style_bible; mei is the character_ref with noir as its
        // parent (modelling "this character's look builds on this style").
        store.mutateSource(pid) {
            it.addStyleBible(
                SourceNodeId("noir"),
                StyleBibleBody(name = "noir", description = "high-contrast noir"),
            ).addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
                parents = listOf(SourceRef(SourceNodeId("noir"))),
            )
        }
        // Snapshot the child's deep hash — which includes the parent's body —
        // at generation time.
        val snapshotHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))
        appendLockfile(
            store,
            pid,
            LockfileEntry(
                inputHash = "h-transitive",
                toolId = "generate_image",
                assetId = asset,
                provenance = fakeProvenance(),
                sourceBinding = setOf(SourceNodeId("mei")),
                sourceContentHashes = mapOf(SourceNodeId("mei") to snapshotHash),
            ),
        )
        // Sanity: before any edit, nothing is stale.
        val toolBefore = FindStaleClipsTool(store)
        val baseline = toolBefore.execute(FindStaleClipsTool.Input(pid.value), ctx()).data
        assertEquals(0, baseline.staleClipCount, "freshly-snapshotted project must not report stale")

        // User edits only the parent `noir`. mei's own body + direct parents
        // list are unchanged, so its SHALLOW contentHash stays identical —
        // only its deep (folded-with-ancestors) hash shifts. The tool is the
        // consumer that surfaces this through the lockfile snapshot diff.
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("noir")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        StyleBibleBody.serializer(),
                        StyleBibleBody(name = "noir", description = "vibrant pop"),
                    ),
                )
            }
        }

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data
        assertEquals(1, out.staleClipCount, "parent-edit must flag the child-bound clip stale through deep-hash drift")
        val report = out.reports.single()
        assertEquals("c-1", report.clipId)
        // Detector names only the directly-bound-and-drifted id. The parent
        // (`noir`) caused the drift but isn't in the clip's binding, so it's
        // not listed — the child (`mei`) *is* in the binding and *did* see a
        // deep-hash change, so it shows up. Contract: report names the
        // bound-and-drifted nodes, not the root-cause ancestors.
        assertEquals(listOf("mei"), report.changedSourceIds)
    }

    @Test fun legacyEntryWithoutSnapshotIsSkipped() = runTest {
        val store = newStore()
        val pid = ProjectId("p-legacy")
        val asset = AssetId("a-1")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }

        // Legacy: empty sourceContentHashes — pre-snapshot lockfile entry.
        appendLockfile(
            store,
            pid,
            LockfileEntry(
                inputHash = "h1",
                toolId = "generate_image",
                assetId = asset,
                provenance = fakeProvenance(),
                sourceBinding = setOf(SourceNodeId("mei")),
                sourceContentHashes = emptyMap(),
            ),
        )

        // Even after a real edit, legacy entries are "unknown" — never stale, never fresh.
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red"),
                    ),
                )
            }
        }

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(0, out.staleClipCount)
        assertTrue(out.reports.isEmpty())
    }

    @Test fun importedClipWithoutLockfileEntryIsSkipped() = runTest {
        val store = newStore()
        val pid = ProjectId("p-imported")
        val asset = AssetId("a-imported")
        seedProjectWithClip(store, pid, videoClip("c-1", asset))

        // No lockfile entry at all — the clip plays an imported asset.
        // (Source has no nodes either; the detector should still gracefully skip.)

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(0, out.staleClipCount)
        assertEquals(1, out.totalClipCount)
        assertTrue(out.reports.isEmpty())
    }

    @Test fun emptyLockfileShortCircuits() = runTest {
        val store = newStore()
        val pid = ProjectId("p-empty")
        store.upsert("demo", Project(id = pid, timeline = Timeline()))

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(0, out.staleClipCount)
        assertEquals(0, out.totalClipCount)
        assertTrue(out.reports.isEmpty())
    }

    /**
     * Seeds a project with `clipCount` AIGC clips all bound to a single character
     * ref, then drifts the character so every clip goes stale. Returns the project
     * id for further assertions.
     */
    private suspend fun seedManyStale(
        store: FileProjectStore,
        pid: ProjectId,
        clipCount: Int,
    ) {
        // Intentionally insert clips in non-sorted order (reverse + zero-padded) so the
        // store / detector cannot accidentally hand us a pre-sorted stream — ordering
        // has to come from the tool itself.
        val clips = (clipCount - 1 downTo 0).map { i ->
            val idx = i.toString().padStart(3, '0')
            videoClip(
                id = "c-$idx",
                asset = AssetId("a-$idx"),
                binding = setOf(SourceNodeId("mei")),
            )
        }
        val track = Track.Video(id = TrackId("v0"), clips = clips)
        store.upsert(
            "demo",
            Project(id = pid, timeline = Timeline(tracks = listOf(track))),
        )
        store.mutateSource(pid) {
            it.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        val originalHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("mei"))
        clips.forEach { clip ->
            appendLockfile(
                store,
                pid,
                LockfileEntry(
                    inputHash = "h-${clip.assetId.value}",
                    toolId = "generate_image",
                    assetId = clip.assetId,
                    provenance = fakeProvenance(),
                    sourceBinding = setOf(SourceNodeId("mei")),
                    sourceContentHashes = mapOf(SourceNodeId("mei") to originalHash),
                ),
            )
        }
        // Drift the character → every bound clip becomes stale.
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red hair"),
                    ),
                )
            }
        }
    }

    @Test fun limitCapsReportsButKeepsTrueStaleCount() = runTest {
        val store = newStore()
        val pid = ProjectId("p-capped")
        seedManyStale(store, pid, clipCount = 12)

        val tool = FindStaleClipsTool(store)
        val out = tool.execute(FindStaleClipsTool.Input(pid.value, limit = 5), ctx()).data

        // True total preserved.
        assertEquals(12, out.staleClipCount)
        assertEquals(12, out.totalClipCount)
        // Reports trimmed to the cap.
        assertEquals(5, out.reports.size)
    }

    @Test fun reportOrderIsDeterministicAcrossCalls() = runTest {
        val store = newStore()
        val pid = ProjectId("p-ordered")
        seedManyStale(store, pid, clipCount = 8)

        val tool = FindStaleClipsTool(store)
        val first = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data.reports
        val second = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data.reports

        assertEquals(first, second)
        // And the order is specifically ascending-by-clipId.
        assertEquals(first.map { it.clipId }, first.map { it.clipId }.sorted())
    }

    @Test fun omittedLimitFallsBackToDefault50() = runTest {
        val store = newStore()
        val pid = ProjectId("p-default")
        // 60 stale clips > default cap of 50.
        seedManyStale(store, pid, clipCount = 60)

        val tool = FindStaleClipsTool(store)
        // Null / omitted limit (uses the `limit: Int? = null` default).
        val out = tool.execute(FindStaleClipsTool.Input(pid.value), ctx()).data

        assertEquals(60, out.staleClipCount)
        assertEquals(FindStaleClipsTool.DEFAULT_LIMIT, out.reports.size)
    }
}
