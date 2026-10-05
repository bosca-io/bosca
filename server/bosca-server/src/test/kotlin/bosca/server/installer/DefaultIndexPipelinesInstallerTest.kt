package bosca.server.installer

import bosca.content.collection.events.CollectionDeleted
import bosca.content.collection.pipeline.CollectionEventToCollectionNode
import bosca.content.metadata.events.MetadataDeleted
import bosca.content.metadata.events.MetadataUpdated
import bosca.content.metadata.pipeline.MetadataEventToMetadataNode
import bosca.content.transformations.pipeline.BuildMetadataSearchDocumentNode
import bosca.content.transformations.pipeline.BuildProfileSearchDocumentNode
import bosca.pipelines.builtin.GetIdNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.InputNode
import bosca.pipelines.service.PipelineService
import bosca.profile.profile.events.ProfileUpdatedEvent
import bosca.profile.profile.pipeline.ProfileEventToProfileNode
import bosca.search.pipeline.IndexDocumentNode
import bosca.search.pipeline.RemoveFromIndexNode
import bosca.search.pipeline.SearchDocumentPipeline
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultIndexPipelinesInstallerTest {

    private fun installerWith(existing: List<String>): Pair<DefaultIndexPipelinesInstaller, MutableList<Pipeline>> {
        val captured = mutableListOf<Pipeline>()
        val ps = mockk<PipelineService>()
        coEvery { ps.getAll() } returns existing.map { name -> mockk<Pipeline> { every { this@mockk.name } returns name } }
        // The installer encodes each typed graph here — capture it; the encoded JSON is irrelevant to the test.
        coEvery { ps.graphAsJsonElement(capture(captured)) } returns JsonObject(emptyMap())
        coEvery {
            ps.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns mockk(relaxed = true)
        return DefaultIndexPipelinesInstaller(ps) to captured
    }

    private suspend fun DefaultIndexPipelinesInstaller.run() =
        install(mockk(relaxed = true), mockk(relaxed = true))

    @Test
    fun `seeds one triggered pipeline per index event, all accepting the event FQDN`() = runTest {
        val (installer, captured) = installerWith(emptyList())
        installer.run()

        assertEquals(18, captured.size, "6+1 metadata, 6+2 collection, 2+1 profile = 18 pipelines")
        assertTrue(captured.all { it.triggered }, "every seeded pipeline must be triggered")
        // acceptedInputType is the event class FQDN, and the Input node declares the same type.
        assertTrue(captured.all { it.acceptedInputType.isNotBlank() })
        assertTrue(
            captured.all { p -> (p.nodes.first { it is InputNode } as InputNode).acceptedType == p.acceptedInputType },
            "the Input node's acceptedType must equal the pipeline's acceptedInputType",
        )
        // Distinct FQDNs (no duplicate triggers for the same event).
        assertEquals(18, captured.map { it.acceptedInputType }.toSet().size)
    }

    @Test
    fun `an index pipeline fans Get X out to a Build then Index Document branch per content index`() = runTest {
        val (installer, captured) = installerWith(emptyList())
        installer.run()

        val p = captured.single { it.acceptedInputType == MetadataUpdated::class.qualifiedName }
        assertTrue(!p.name.startsWith("Remove"))
        // node chain: Input → Get Id → Get Metadata, then Build/Index per index (Default + Admin).
        assertTrue(p.nodes[0] is InputNode)
        assertTrue(p.nodes[1] is GetIdNode)
        assertTrue(p.nodes[2] is MetadataEventToMetadataNode)

        val builds = p.nodes.filterIsInstance<BuildMetadataSearchDocumentNode>()
        val indexes = p.nodes.filterIsInstance<IndexDocumentNode>()
        assertEquals(2, builds.size, "one Build node per content index")
        assertEquals(2, indexes.size, "one Index Document node per content index")
        assertEquals(
            setOf(SearchDocumentPipeline.DEFAULT_INDEX, SearchDocumentPipeline.ADMIN_INDEX),
            builds.map { it.index }.toSet(),
            "Build nodes target the Default and Admin indexes",
        )
        assertEquals(
            setOf(SearchDocumentPipeline.DEFAULT_INDEX, SearchDocumentPipeline.ADMIN_INDEX),
            indexes.map { it.index }.toSet(),
            "Index Document nodes target the Default and Admin indexes",
        )
        // Each Build node feeds an Index Document node targeting the same index.
        for (build in builds) {
            val edge = p.edges.single { it.source == build.id }
            val index = p.nodes.single { it.id == edge.target } as IndexDocumentNode
            assertEquals(build.index, index.index, "a Build branch feeds the Index for the same index")
        }
        // Get Metadata fans out to both Build nodes.
        assertEquals(builds.map { it.id }.toSet(), p.edges.filter { it.source == "get" }.map { it.target }.toSet())

        // Visibility is expressed via the Build toggles: the default index requires published, the admin index does not.
        val defaultBuild = builds.single { it.index == SearchDocumentPipeline.DEFAULT_INDEX }
        val adminBuild = builds.single { it.index == SearchDocumentPipeline.ADMIN_INDEX }
        assertTrue(defaultBuild.requirePublished && defaultBuild.requirePublic, "the default index only indexes public, published content")
        assertTrue(!adminBuild.requirePublished && !adminBuild.requirePublic, "the admin index also indexes unpublished content")
        // Metadata yields a single document per index, so its Index Document nodes don't replace.
        assertTrue(indexes.none { it.replace }, "single-document metadata does not need replace")
    }

    @Test
    fun `a collection index pipeline replaces each index's documents so dropped variants are cleared`() = runTest {
        val (installer, captured) = installerWith(emptyList())
        installer.run()

        val p = captured.single { it.acceptedInputType == bosca.content.collection.events.CollectionUpdated::class.qualifiedName }
        val indexes = p.nodes.filterIsInstance<IndexDocumentNode>()
        assertEquals(2, indexes.size)
        assertTrue(indexes.all { it.replace }, "a collection's per-variant documents are replaced on every index")
    }

    @Test
    fun `a profile index pipeline targets only the profile and admin indexes`() = runTest {
        val (installer, captured) = installerWith(emptyList())
        installer.run()

        val pipeline = captured.single { it.acceptedInputType == ProfileUpdatedEvent::class.qualifiedName }
        val builds = pipeline.nodes.filterIsInstance<BuildProfileSearchDocumentNode>()
        val indexes = pipeline.nodes.filterIsInstance<IndexDocumentNode>()
        val expected = setOf(SearchDocumentPipeline.PROFILE_INDEX, SearchDocumentPipeline.ADMIN_INDEX)

        assertEquals(expected, builds.map { it.index }.toSet())
        assertEquals(expected, indexes.map { it.index }.toSet())
        assertTrue(builds.none { it.index == SearchDocumentPipeline.DEFAULT_INDEX })
    }

    @Test
    fun `a delete event ends in a Remove from Index node`() = runTest {
        val (installer, captured) = installerWith(emptyList())
        installer.run()

        val md = captured.single { it.acceptedInputType == MetadataDeleted::class.qualifiedName }
        assertTrue(md.nodes[3] is RemoveFromIndexNode)
        assertTrue(md.name.startsWith("Remove Metadata from Index"))
        assertEquals(listOf("input", "getId", "get"), md.edges.map { it.source })
        assertEquals(listOf("getId", "get", "sink"), md.edges.map { it.target })

        val cd = captured.single { it.acceptedInputType == CollectionDeleted::class.qualifiedName }
        assertTrue(cd.nodes[2] is CollectionEventToCollectionNode)
        assertTrue(cd.nodes[3] is RemoveFromIndexNode)

        // Profile *update* is an index event — it builds + indexes, not removes.
        val pu = captured.single { it.acceptedInputType == ProfileUpdatedEvent::class.qualifiedName }
        assertTrue(pu.nodes[2] is ProfileEventToProfileNode)
        assertTrue(pu.nodes.any { it is BuildProfileSearchDocumentNode })
        assertTrue(pu.nodes.any { it is IndexDocumentNode })
        assertTrue(pu.nodes.none { it is RemoveFromIndexNode })
    }

    @Test
    fun `every seeded pipeline is structurally well-formed and fully connected`() = runTest {
        val (installer, captured) = installerWith(emptyList())
        installer.run()

        for (p in captured) {
            val ids = p.nodes.map { it.id }
            assertEquals(ids.size, ids.toSet().size, "duplicate node id in '${p.name}'")
            assertEquals(p.edges.map { it.id }.size, p.edges.map { it.id }.toSet().size, "duplicate edge id in '${p.name}'")
            val idSet = ids.toSet()
            // Every edge connects two existing nodes — guards the "edge needs a source and a target" error.
            for (e in p.edges) {
                assertTrue(e.source in idSet, "edge '${e.id}' source '${e.source}' is not a node in '${p.name}'")
                assertTrue(e.target in idSet, "edge '${e.id}' target '${e.target}' is not a node in '${p.name}'")
            }
            // No orphans: every node is reachable from the Input node via the edges.
            val input = p.nodes.single { it is InputNode }.id
            val reachable = mutableSetOf(input)
            var changed = true
            while (changed) {
                changed = false
                for (e in p.edges) if (e.source in reachable && e.target !in reachable) {
                    reachable += e.target
                    changed = true
                }
            }
            assertEquals(idSet, reachable, "every node must be reachable from the input in '${p.name}'")
        }
    }

    @Test
    fun `is idempotent - an already-present pipeline name is not recreated`() = runTest {
        // Pre-seed the Metadata Created index pipeline's name; the installer must skip exactly it.
        val (installer, captured) = installerWith(listOf("Index Metadata — Created"))
        installer.run()

        assertEquals(17, captured.size, "the one already-present pipeline is skipped")
        assertTrue(captured.none { it.name == "Index Metadata — Created" })
    }
}
