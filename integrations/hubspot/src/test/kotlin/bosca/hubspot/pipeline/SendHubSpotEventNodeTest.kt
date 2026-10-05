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
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * The Send HubSpot Event node sends a HubSpot custom behavioral event attributed to a profile's synced
 * contact: it resolves the profile's `bosca.profiles.hubspot.id` and calls [HubSpot.sendEvent] with the
 * configured event name and inbound properties, passing the profile id through as its output. It errors
 * when the contact has no HubSpot id yet, and when no event name is configured.
 */
class SendHubSpotEventNodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    private fun context(dryRun: Boolean = false, trace: DryRunTrace? = null) =
        PipelineContext(AuthenticationContext(null, null), json, dryRun = dryRun, trace = trace)

    private val properties = buildJsonObject { put("plan", "pro") }

    private fun inputs(profileId: Uuid) =
        NodeInputs(
            mapOf(
                "profile" to PipelineValue.ofJson(JsonPrimitive(profileId.toString())),
                "properties" to PipelineValue.ofJson(properties),
            )
        )

    private fun attribute(typeId: String, name: String, value: String, profile: Uuid) =
        ProfileAttribute(
            profile = profile,
            typeId = typeId,
            visibility = ProfileVisibility.SYSTEM,
            confidence = 100,
            priority = 100,
            source = "test",
            attributes = JsonObject(mapOf(name to JsonPrimitive(value))),
        )

    @Test
    fun `sends the event attributed to the synced contact`() = runBlocking {
        val profileId = Uuid.random()
        provides<ProfileService> {
            mockk<ProfileService> {
                coEvery { getAttributes(profileId) } returns listOf(attribute("bosca.profiles.hubspot.id", "id", "contact-1", profileId))
            }
        }
        val hubspot = mockk<HubSpot>(relaxed = true)
        provides<HubSpot> { hubspot }

        val out = SendHubSpotEventNode(id = "n", eventName = "pe123_signup").run(context(), inputs(profileId)) as NodeResult.Output

        coVerify(exactly = 1) {
            hubspot.sendEvent(
                eventName = "pe123_signup",
                objectId = "contact-1",
                properties = properties,
                occurredAt = any(),
            )
        }
        assertEquals(profileId.toString(), out.value?.encode(json)?.jsonPrimitive?.content)
    }

    @Test
    fun `fails when the contact has no hubspot id yet`() {
        runBlocking {
            val profileId = Uuid.random()
            provides<ProfileService> {
                mockk<ProfileService> {
                    coEvery { getAttributes(profileId) } returns emptyList()
                }
            }
            provides<HubSpot> { mockk(relaxed = true) }

            assertFailsWith<IllegalStateException> {
                SendHubSpotEventNode(id = "n", eventName = "pe123_signup").run(context(), inputs(profileId))
            }
        }
    }

    @Test
    fun `fails without an event name`() {
        runBlocking {
            val profileId = Uuid.random()
            // The node resolves the profile id before checking the event name, so still provide it.
            provides<ProfileService> {
                mockk<ProfileService> {
                    coEvery { getAttributes(profileId) } returns listOf(attribute("bosca.profiles.hubspot.id", "id", "contact-1", profileId))
                }
            }
            provides<HubSpot> { mockk(relaxed = true) }

            assertFailsWith<IllegalStateException> {
                SendHubSpotEventNode(id = "n", eventName = "").run(context(), inputs(profileId))
            }
        }
    }

    @Test
    fun `a dry run records the action`() = runBlocking {
        val profileId = Uuid.random()
        val trace = DryRunTrace()

        SendHubSpotEventNode(id = "a", eventName = "pe123_signup").run(context(dryRun = true, trace = trace), inputs(profileId))

        val action = trace.actions["a"]?.jsonObject ?: error("expected a recorded action")
        assertEquals("sendHubSpotEvent", action["action"]?.jsonPrimitive?.content)
        assertEquals(profileId.toString(), action["profileId"]?.jsonPrimitive?.content)
        assertEquals("pe123_signup", action["eventName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `round-trips with its settings`() {
        val node = SendHubSpotEventNode(id = "c", name = "Send", eventName = "pe123_signup")
        val decoded = json.decodeFromString(
            SendHubSpotEventNode.serializer(),
            json.encodeToString(SendHubSpotEventNode.serializer(), node),
        )
        assertEquals("pe123_signup", decoded.eventName)
        assertEquals("Send", decoded.name)
        assertEquals("c", decoded.id)
    }
}
