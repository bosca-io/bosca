@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.hubspot.pipeline

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.hubspot.client.HubSpot
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
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
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * [HubSpotUpdateNode] patches an existing HubSpot object: it reads a `properties` object and the
 * `hubspotId` string, calls `HubSpot.update`, and emits that id. We verify the inline update a
 * non-durable run runs straight away (calling `HubSpot.update` with the default `contacts` type, the
 * id, and the properties), that a missing `hubspotId` input fails, that a dry run traces the would-be
 * update, and that the node round-trips its serialized form.
 */
class HubSpotUpdateNodeTest {

    private val json = Json

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    private fun context(dryRun: Boolean = false, trace: DryRunTrace? = null, runId: bosca.serialization.UUID? = null) =
        PipelineContext(AuthenticationContext(null, null), json, dryRun = dryRun, trace = trace, runId = runId)

    private fun inputs() = NodeInputs(
        mapOf(
            "properties" to PipelineValue.ofJson(buildJsonObject { put("email", "a@b.c") }),
            "hubspotId" to PipelineValue.of("hs-1"),
        )
    )

    @Test
    fun `updates the object and returns its id`() = runBlocking {
        val properties = slot<JsonObject>()
        val hubspot = mockk<HubSpot> {
            coEvery { update("contacts", "hs-1", capture(properties)) } returns Unit
        }
        provides<HubSpot> { hubspot }

        val out = HubSpotUpdateNode(id = "n").run(context(), inputs()) as NodeResult.Output

        assertEquals("hs-1", out.value?.encode(json)?.jsonPrimitive?.content)
        coVerify(exactly = 1) { hubspot.update("contacts", "hs-1", any()) }
        assertEquals("a@b.c", properties.captured["email"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fails without a hubspotId input`() {
        runBlocking {
            val onlyProperties =
                NodeInputs(mapOf("properties" to PipelineValue.ofJson(buildJsonObject { put("email", "a@b.c") })))
            assertFailsWith<IllegalStateException> {
                HubSpotUpdateNode(id = "n").run(context(), onlyProperties)
            }
        }
    }

    @Test
    fun `a dry run traces`() = runBlocking {
        val hubspot = mockk<HubSpot>(relaxed = true)
        provides<HubSpot> { hubspot }

        val trace = DryRunTrace()
        HubSpotUpdateNode(id = "u").run(context(dryRun = true, trace = trace), inputs())

        assertEquals("hubspotUpdate", trace.actions["u"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
        coVerify(exactly = 0) { hubspot.update(any(), any(), any()) }
    }

    @Test
    fun `round-trips`() {
        val node = HubSpotUpdateNode(id = "u", name = "Update", objectType = "companies")
        val decoded = json.decodeFromString(HubSpotUpdateNode.serializer(), json.encodeToString(HubSpotUpdateNode.serializer(), node))
        assertEquals("companies", decoded.objectType)
    }
}
