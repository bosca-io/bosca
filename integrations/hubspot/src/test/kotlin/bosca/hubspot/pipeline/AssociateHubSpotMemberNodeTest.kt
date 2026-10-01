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
import bosca.pipelines.node.PipelineValue
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.service.OrganizationService
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
 * The Associate HubSpot Member node creates the contact→company association joining a member to an
 * organization: it resolves the organization's profile (via [OrganizationService]) and the member's
 * profile (via [ProfileService.getByPrincipal]), reads each side's already-synced `bosca.profiles.hubspot.id`
 * attribute, and calls [HubSpot.createAssociation]. It errors when either HubSpot id has not synced yet.
 */
class AssociateHubSpotMemberNodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    private fun context(dryRun: Boolean = false, trace: DryRunTrace? = null) =
        PipelineContext(AuthenticationContext(null, null), json, dryRun = dryRun, trace = trace)

    private fun inputs(orgId: Uuid, memberId: Uuid) =
        NodeInputs(
            mapOf(
                "organization" to PipelineValue.ofJson(JsonPrimitive(orgId.toString())),
                "member" to PipelineValue.ofJson(JsonPrimitive(memberId.toString())),
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

    /** Registers a ConfigurationService whose `hubspot` config decodes to [config]. */
    private fun configuredWith(config: HubSpotConfiguration): ConfigurationService {
        val configId = Uuid.random()
        val row = mockk<Configuration> { every { id } returns configId }
        return mockk<ConfigurationService> {
            coEvery { getByKey("hubspot") } returns row
            coEvery { getValue(configId) } returns json.encodeToJsonElement(config)
        }
    }

    private fun configuration() =
        HubSpotConfiguration(token = "t", expressions = HubSpotExpressions(generic = "{}", organization = "{}"))

    @Test
    fun `associates the member contact with the organization company`() = runBlocking {
        val orgId = Uuid.random()
        val memberId = Uuid.random()
        val orgProfileId = Uuid.random()
        val memberProfileId = Uuid.random()

        provides<ConfigurationService> { configuredWith(configuration()) }
        provides<OrganizationService> {
            mockk<OrganizationService> {
                coEvery { getOrganization(orgId) } returns mockk { every { profileId } returns orgProfileId }
            }
        }
        provides<ProfileService> {
            mockk<ProfileService> {
                coEvery { getByPrincipal(memberId) } returns listOf(
                    Profile(id = memberProfileId, type = ProfileType.GENERIC, name = "Member", visibility = ProfileVisibility.SYSTEM)
                )
                coEvery { getAttributes(orgProfileId) } returns listOf(attribute("bosca.profiles.hubspot.id", "id", "company-1", orgProfileId))
                coEvery { getAttributes(memberProfileId) } returns listOf(attribute("bosca.profiles.hubspot.id", "id", "contact-1", memberProfileId))
            }
        }
        val hubspot = mockk<HubSpot>(relaxed = true)
        provides<HubSpot> { hubspot }

        AssociateHubSpotMemberNode(id = "n").run(context(), inputs(orgId, memberId))

        coVerify(exactly = 1) {
            hubspot.createAssociation(
                fromObjectType = "contact",
                fromObjectId = "contact-1",
                toObjectType = "company",
                toObjectId = "company-1",
                associationTypeId = any(),
                associationCategory = any(),
            )
        }
    }

    @Test
    fun `fails when the member has no hubspot id`() {
        runBlocking {
            val orgId = Uuid.random()
            val memberId = Uuid.random()
            val orgProfileId = Uuid.random()
            val memberProfileId = Uuid.random()

            provides<ConfigurationService> { configuredWith(configuration()) }
            provides<OrganizationService> {
                mockk<OrganizationService> {
                    coEvery { getOrganization(orgId) } returns mockk { every { profileId } returns orgProfileId }
                }
            }
            provides<ProfileService> {
                mockk<ProfileService> {
                    coEvery { getByPrincipal(memberId) } returns listOf(
                        Profile(id = memberProfileId, type = ProfileType.GENERIC, name = "Member", visibility = ProfileVisibility.SYSTEM)
                    )
                    coEvery { getAttributes(orgProfileId) } returns listOf(attribute("bosca.profiles.hubspot.id", "id", "company-1", orgProfileId))
                    coEvery { getAttributes(memberProfileId) } returns emptyList()
                }
            }
            provides<HubSpot> { mockk(relaxed = true) }

            assertFailsWith<IllegalStateException> {
                AssociateHubSpotMemberNode(id = "n").run(context(), inputs(orgId, memberId))
            }
        }
    }

    @Test
    fun `a dry run records the action`() = runBlocking {
        val orgId = Uuid.random()
        val memberId = Uuid.random()
        val trace = DryRunTrace()

        AssociateHubSpotMemberNode(id = "a").run(context(dryRun = true, trace = trace), inputs(orgId, memberId))

        val action = trace.actions["a"]?.jsonObject ?: error("expected a recorded action")
        assertEquals("associateHubSpotMember", action["action"]?.jsonPrimitive?.content)
        assertEquals(orgId.toString(), action["organization"]?.jsonPrimitive?.content)
        assertEquals(memberId.toString(), action["member"]?.jsonPrimitive?.content)
    }

    @Test
    fun `round-trips with its settings`() {
        val node = AssociateHubSpotMemberNode(id = "c", name = "Associate")
        val decoded = json.decodeFromString(
            AssociateHubSpotMemberNode.serializer(),
            json.encodeToString(AssociateHubSpotMemberNode.serializer(), node),
        )
        assertEquals("Associate", decoded.name)
        assertEquals("c", decoded.id)
    }
}
