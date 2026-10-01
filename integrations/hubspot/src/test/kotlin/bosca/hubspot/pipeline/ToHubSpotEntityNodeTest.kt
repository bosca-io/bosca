@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.hubspot.pipeline

import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.hubspot.configuration.HubSpotConfiguration
import bosca.hubspot.configuration.HubSpotExpressions
import bosca.hubspot.transformer.HubSpotEntityTransformer
import bosca.hubspot.transformer.HubspotData
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.profile.model.ProfileType
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class ToHubSpotEntityNodeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val node = CreateHubSpotPropertiesNode(id = "entity")

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

    private fun inputs(profileId: Uuid) =
        NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive(profileId.toString()))))

    @Test
    fun `maps a person profile via the generic JSONata expression`() = runBlocking {
        val profileId = Uuid.random()
        provides<ConfigurationService> {
            configuredWith(
                HubSpotConfiguration(
                    token = "t",
                    // JSONata object constructor: copy the inbound email through as the HubSpot property.
                    expressions = HubSpotExpressions(generic = "{ \"email\": email }", organization = "{}"),
                )
            )
        }
        provides<HubSpotEntityTransformer> {
            mockk<HubSpotEntityTransformer> {
                coEvery { transform(Unit, profileId) } returns HubspotData(
                    id = profileId,
                    type = ProfileType.GENERIC,
                    hubspotId = null,
                    data = mapOf("email" to "a@b.c"),
                    context = buildJsonObject {},
                )
            }
        }

        val context = PipelineContext(AuthenticationContext(null, null), json)
        val result = node.run(context, inputs(profileId)) as NodeResult.Output
        val entity = result.value?.encode(json)?.jsonObject ?: error("expected an entity")

        assertEquals("a@b.c", entity["email"]?.jsonPrimitive?.content)
    }

    @Test
    fun `maps a company profile via the organization JSONata expression`() = runBlocking {
        val profileId = Uuid.random()
        provides<ConfigurationService> {
            configuredWith(
                HubSpotConfiguration(
                    token = "t",
                    // The organization branch must win for a company profile — generic would not emit `name`.
                    expressions = HubSpotExpressions(generic = "{}", organization = "{ \"name\": name }"),
                )
            )
        }
        provides<HubSpotEntityTransformer> {
            mockk<HubSpotEntityTransformer> {
                coEvery { transform(Unit, profileId) } returns HubspotData(
                    id = profileId,
                    type = ProfileType.ORGANIZATION,
                    hubspotId = null,
                    data = mapOf("name" to "Acme"),
                    context = buildJsonObject {},
                )
            }
        }

        val context = PipelineContext(AuthenticationContext(null, null), json)
        val result = node.run(context, inputs(profileId)) as NodeResult.Output
        val entity = result.value?.encode(json)?.jsonObject ?: error("expected an entity")

        assertEquals("Acme", entity["name"]?.jsonPrimitive?.content)
        assertEquals(null, entity["email"], "the generic expression must not have been used")
    }

    @Test
    fun `fails when the input is not a profile UUID`() {
        runBlocking {
            val context = PipelineContext(AuthenticationContext(null, null), json)
            // The generated codec decodes the 'in' slot with the UUID serializer, so a non-UUID value
            // fails the node with the decoder's exception rather than a named IllegalStateException.
            val notAUuid = NodeInputs(mapOf("in" to PipelineValue.ofJson(buildJsonObject { })))
            assertFails { node.run(context, notAUuid) }
            val notAUuidString = NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("not-a-uuid"))))
            assertFailsWith<IllegalArgumentException> { node.run(context, notAUuidString) }
        }
    }

    @Test
    fun `fails when the profile input is missing`() {
        runBlocking {
            val context = PipelineContext(AuthenticationContext(null, null), json)
            val error = assertFailsWith<IllegalStateException> { node.run(context, NodeInputs(emptyMap())) }
            assertEquals(true, error.message?.contains("required input 'in'"))
        }
    }
}
