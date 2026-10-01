@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.hubspot.pipeline

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.hubspot.client.HubSpot
import bosca.hubspot.client.HubspotAddResults
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.NodeSuspensionService
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * [HubSpotAddNode] writes a HubSpot object from a `properties` object input and emits the created
 * object's id, flipping `willSuspend = true` so a durable run parks the write on the generic
 * [NodeSuspensionService] rather than performing it inline. We verify the inline write a non-durable
 * run runs straight away (calling `HubSpot.add` with the default `contacts` type and the properties),
 * that a dry run traces the would-be add and writes nothing, that a durable run suspends, and that the
 * node round-trips its `objectType`.
 */
class HubSpotAddNodeTest {

    private val json = Json

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    private fun context(dryRun: Boolean = false, trace: DryRunTrace? = null, runId: bosca.serialization.UUID? = null) =
        PipelineContext(AuthenticationContext(null, null), json, dryRun = dryRun, trace = trace, runId = runId)

    private fun propertyInputs() =
        NodeInputs(mapOf("properties" to PipelineValue.ofJson(buildJsonObject { put("email", "a@b.c") })))

    @Test
    fun `adds the object and returns the new id`() = runBlocking {
        val properties = slot<JsonObject>()
        val hubspot = mockk<HubSpot> {
            coEvery { add("contacts", capture(properties), any(), any()) } returns HubspotAddResults("contact-9", true)
        }
        provides<HubSpot> { hubspot }

        val out = HubSpotAddNode(id = "n").run(context(), propertyInputs()) as NodeResult.Output

        assertEquals("contact-9", out.value?.encode(json)?.jsonPrimitive?.content)
        coVerify(exactly = 1) { hubspot.add("contacts", any(), any(), any()) }
        assertEquals("a@b.c", properties.captured["email"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a dry run traces and writes nothing`() = runBlocking {
        val hubspot = mockk<HubSpot>(relaxed = true)
        provides<HubSpot> { hubspot }

        val trace = DryRunTrace()
        HubSpotAddNode(id = "a").run(context(dryRun = true, trace = trace), propertyInputs())

        assertEquals("hubspotAdd", trace.actions["a"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
        coVerify(exactly = 0) { hubspot.add(any(), any(), any(), any()) }
    }

    @Test
    fun `a durable run suspends via the NodeSuspensionService`() = runBlocking {
        val suspension = mockk<NodeSuspensionService>(relaxed = true)
        provides<NodeSuspensionService> { suspension }

        val result = HubSpotAddNode(id = "n").run(context(runId = Uuid.random()), propertyInputs())

        assertEquals(true, result is NodeResult.Suspend)
        (result as NodeResult.Suspend).enqueue()
        coVerify(exactly = 1) { suspension.suspendNode(any(), any(), any()) }
    }

    @Test
    fun `round-trips`() {
        val node = HubSpotAddNode(id = "a", name = "Add", objectType = "companies")
        val decoded = json.decodeFromString(HubSpotAddNode.serializer(), json.encodeToString(HubSpotAddNode.serializer(), node))
        assertEquals("companies", decoded.objectType)
    }
}
