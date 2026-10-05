@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.hubspot.pipeline

import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.hubspot.client.HubSpot
import bosca.hubspot.configuration.HubSpotConfiguration
import bosca.hubspot.configuration.HubSpotExpressions
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * `SubscribeToConfiguredHubSpotNode` reads the profile UUID on `profile`, resolves the contact's
 * email, and sets **every** communication subscription configured in the HubSpot integration
 * settings (`subscriptionIds`), then passes the profile id through. We verify the fan-out over the
 * configured ids, the dry-run trace, the input-validation guard, and round-trip.
 */
class SubscribeToConfiguredHubSpotNodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    /** Registers a ConfigurationService whose `hubspot` config decodes to [config]. */
    private fun configuredWith(config: HubSpotConfiguration): ConfigurationService {
        val configId = Uuid.random()
        val row = mockk<Configuration> { every { id } returns configId }
        return mockk<ConfigurationService> {
            coEvery { getByKey("hubspot") } returns row
            coEvery { getValue(configId) } returns json.encodeToJsonElement(config)
        }
    }

    private fun config(subscriptionIds: List<String>) = HubSpotConfiguration(
        token = "t",
        expressions = HubSpotExpressions(generic = "{}", organization = "{}"),
        subscriptionIds = subscriptionIds,
    )

    private fun context(dryRun: Boolean = false, trace: DryRunTrace? = null) =
        PipelineContext(AuthenticationContext(null, null), json, dryRun = dryRun, trace = trace)

    private fun profileInputs(profileId: Uuid) =
        NodeInputs(mapOf("profile" to PipelineValue.ofJson(JsonPrimitive(profileId.toString()))))

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

    private fun profileServiceWithEmail(profileId: Uuid, email: String) =
        mockk<ProfileService> {
            coEvery { getAttributes(profileId) } returns
                listOf(attribute("bosca.profiles.email", "email", email, profileId))
        }

    @Test
    fun `subscribes to every configured subscription`() = runBlocking {
        val profileId = Uuid.random()
        val hubspot = mockk<HubSpot>(relaxed = true)
        provides<ConfigurationService> { configuredWith(config(listOf("1", "2"))) }
        provides<ProfileService> { profileServiceWithEmail(profileId, "a@b.c") }
        provides<HubSpot> { hubspot }

        val out = SubscribeToConfiguredHubSpotNode(id = "n").run(context(), profileInputs(profileId)) as NodeResult.Output

        coVerify(exactly = 1) { hubspot.setSubscription("a@b.c", 1, any()) }
        coVerify(exactly = 1) { hubspot.setSubscription("a@b.c", 2, any()) }
        assertEquals(profileId.toString(), out.value?.encode(json)?.jsonPrimitive?.content, "the profile id passes through")
    }

    @Test
    fun `a dry run records the action`() = runBlocking {
        val trace = DryRunTrace()
        provides<ConfigurationService> { configuredWith(config(listOf("1", "2"))) }

        SubscribeToConfiguredHubSpotNode(id = "a").run(context(dryRun = true, trace = trace), profileInputs(Uuid.random()))

        assertEquals("subscribeToConfiguredHubSpot", trace.actions["a"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `requires a profile uuid`() {
        runBlocking {
            assertFailsWith<IllegalStateException> {
                SubscribeToConfiguredHubSpotNode(id = "n").run(context(), NodeInputs(emptyMap()))
            }
        }
    }

    @Test
    fun `round-trips with its settings`() {
        val node = SubscribeToConfiguredHubSpotNode(id = "a", name = "Subs")
        val decoded = json.decodeFromString(
            SubscribeToConfiguredHubSpotNode.serializer(),
            json.encodeToString(SubscribeToConfiguredHubSpotNode.serializer(), node),
        )
        assertEquals("Subs", decoded.name)
    }
}
