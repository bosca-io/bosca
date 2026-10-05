@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.hubspot.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.InputNode
import bosca.pipelines.service.PipelineService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The installer seeds four HubSpot pipelines idempotently: the two event-triggered member flows
 * (`event → Get Id(id)=organization + Get Id(memberId)=member → <member node>`) and the two manually
 * invoked JSON-input sync flows (contact + company), each an add-vs-update route that threads the
 * profile id through both branches. We verify the trigger bindings (acceptedInputType + triggered)
 * and the graph wiring for all four, and that an existing pipeline is left alone. (Decoding the graph
 * against the node registry happens at install time — engine nodes aren't on this module's classpath —
 * so here we assert the emitted graph JSON.)
 */
class HubSpotPipelinesInstallerTest {

    private val installation = mockk<PackageInstallation>(relaxed = true)
    private val version = mockk<PackageInstallationVersion>(relaxed = true)

    private fun nodeTypes(graph: JsonElement): List<String> =
        graph.jsonObject["nodes"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }

    private fun positions(graph: JsonElement): List<Pair<Double, Double>> =
        graph.jsonObject["nodes"]!!.jsonArray.map {
            val p = it.jsonObject["position"]!!.jsonObject
            p["x"]!!.jsonPrimitive.double to p["y"]!!.jsonPrimitive.double
        }

    /** An edge as `"source[:sourcePort] -> target[:targetPort]"` for compact wiring assertions. */
    private fun edges(graph: JsonElement): List<String> =
        graph.jsonObject["edges"]!!.jsonArray.map {
            val o = it.jsonObject
            val from = o["source"]!!.jsonPrimitive.content + (o["sourcePort"]?.jsonPrimitive?.content?.let { p -> ":$p" } ?: "")
            val to = o["target"]!!.jsonPrimitive.content + (o["targetPort"]?.jsonPrimitive?.content?.let { p -> ":$p" } ?: "")
            "$from -> $to"
        }

    @Test
    fun `seeds all four pipelines with their triggers and wiring`() = runTest {
        val pipelines = mockk<PipelineService>()
        coEvery { pipelines.getByKey(any()) } returns null
        val keys = mutableListOf<String>()
        val acceptedTypes = mutableListOf<String>()
        val triggered = mutableListOf<Boolean>()
        val graphs = mutableListOf<JsonElement>()
        coEvery {
            pipelines.save(any(), any(), any(), capture(acceptedTypes), capture(triggered), any(), capture(graphs), any(), capture(keys), any(), any(), any(), any(), any())
        } returns mockk<Pipeline>(relaxed = true)

        HubSpotPipelinesInstaller(pipelines).install(installation, version)

        assertEquals(
            listOf(
                HubSpotPipelinesInstaller.MEMBER_ADDED_KEY,
                HubSpotPipelinesInstaller.MEMBER_REMOVED_KEY,
                HubSpotPipelinesInstaller.SYNC_CONTACT_KEY,
                HubSpotPipelinesInstaller.SYNC_COMPANY_KEY,
            ),
            keys,
        )
        assertEquals(
            listOf(
                HubSpotPipelinesInstaller.ORG_MEMBER_ADDED,
                HubSpotPipelinesInstaller.ORG_MEMBER_REMOVED,
                InputNode.JSON_TYPE,
                InputNode.JSON_TYPE,
            ),
            acceptedTypes,
        )
        // Member pipelines are event-triggered; the sync pipelines are manually invoked (JSON input).
        assertEquals(listOf(true, true, false, false), triggered)

        // Every node carries a distinct, non-origin position: the editor does no auto-layout, so
        // unpositioned nodes would all stack at (0,0) and read as disconnected.
        for (graph in graphs) {
            val ps = positions(graph)
            assertEquals(ps.size, ps.toSet().size, "every node needs a distinct canvas position")
            assertTrue(ps.none { it == 0.0 to 0.0 }, "no node should sit at the origin")
        }

        // Member Added / Removed: input(event) → getId(id), getId(memberId) → <member node>.
        assertEquals(listOf("input", "getId", "getId", "hubspotAssociateMember"), nodeTypes(graphs[0]))
        assertEquals(listOf("input", "getId", "getId", "hubspotRemoveMember"), nodeTypes(graphs[1]))
        val memberNodes = graphs[0].jsonObject["nodes"]!!.jsonArray
        assertEquals("id", memberNodes[1].jsonObject["field"]?.jsonPrimitive?.content)
        assertEquals("memberId", memberNodes[2].jsonObject["field"]?.jsonPrimitive?.content)

        // Sync Contact: route add-vs-update, threading the profile through both branches, with the
        // new-contact-only list/subscription enrolment and the associate step on both branches.
        // (Order-independent — the wiring is pinned by the edge assertions below.)
        assertEquals(
            listOf("input", "hubspotSyncRoute", "hubspotProperties", "hubspotProperties", "hubspotAdd", "hubspotGetId", "hubspotUpdate", "hubspotAddProfileAttribute", "hubspotAddToConfiguredLists", "hubspotSubscribeToConfigured", "hubspotAssociateMemberships").sorted(),
            nodeTypes(graphs[2]).sorted(),
        )
        val contactEdges = edges(graphs[2])
        assertTrue("route:unsynced -> stamp:profile" in contactEdges, "stamp must run only on the unsynced branch")
        assertTrue("add:out -> stamp:id" in contactEdges, "stamp records the newly-created id")
        assertTrue("getId:out -> update:hubspotId" in contactEdges, "update patches the resolved id")
        assertTrue("subscribe:profile -> associate:profile" in contactEdges, "associate ends the new-contact chain")
        assertTrue("route:synced -> associate:profile" in contactEdges, "associate also runs for an existing contact")

        // Sync Company: same route, companies object, no list/subscription/association steps.
        assertEquals(
            listOf("input", "hubspotSyncRoute", "hubspotProperties", "hubspotProperties", "hubspotAdd", "hubspotGetId", "hubspotUpdate", "hubspotAddProfileAttribute").sorted(),
            nodeTypes(graphs[3]).sorted(),
        )
        val companyNodes = graphs[3].jsonObject["nodes"]!!.jsonArray
        assertEquals("companies", companyNodes.first { it.jsonObject["type"]?.jsonPrimitive?.content == "hubspotAdd" }.jsonObject["objectType"]?.jsonPrimitive?.content)
        assertEquals("companies", companyNodes.first { it.jsonObject["type"]?.jsonPrimitive?.content == "hubspotUpdate" }.jsonObject["objectType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `is idempotent — skips a pipeline that already exists`() = runTest {
        val pipelines = mockk<PipelineService>()
        coEvery { pipelines.getByKey(any()) } returns null
        coEvery { pipelines.getByKey(HubSpotPipelinesInstaller.SYNC_CONTACT_KEY) } returns mockk<Pipeline>(relaxed = true)
        val keys = mutableListOf<String>()
        coEvery {
            pipelines.save(any(), any(), any(), any(), any(), any(), any(), any(), capture(keys), any(), any(), any(), any(), any())
        } returns mockk<Pipeline>(relaxed = true)

        HubSpotPipelinesInstaller(pipelines).install(installation, version)

        // The already-installed sync-contact pipeline is left alone; the other three are created.
        assertEquals(
            listOf(
                HubSpotPipelinesInstaller.MEMBER_ADDED_KEY,
                HubSpotPipelinesInstaller.MEMBER_REMOVED_KEY,
                HubSpotPipelinesInstaller.SYNC_COMPANY_KEY,
            ),
            keys,
        )
        coVerify(exactly = 3) { pipelines.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
