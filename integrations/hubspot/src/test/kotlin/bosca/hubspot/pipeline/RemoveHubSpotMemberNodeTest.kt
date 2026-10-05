@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.hubspot.pipeline

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.hubspot.client.HubSpot
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * The Remove HubSpot Member node tears down the contact→company association when a member leaves an
 * organization: it resolves both HubSpot ids and calls [HubSpot.removeAssociation]. When either side
 * has no HubSpot id (nothing synced to disassociate), it is a no-op — matching the legacy job.
 */
class RemoveHubSpotMemberNodeTest {

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

    @Test
    fun `removes the association`() = runBlocking {
        val orgId = Uuid.random()
        val memberId = Uuid.random()
        val orgProfileId = Uuid.random()
        val memberProfileId = Uuid.random()

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

        RemoveHubSpotMemberNode(id = "n").run(context(), inputs(orgId, memberId))

        coVerify(exactly = 1) {
            hubspot.removeAssociation(
                fromObjectType = "contact",
                fromObjectId = "contact-1",
                toObjectType = "company",
                toObjectId = "company-1",
            )
        }
    }

    @Test
    fun `is a no-op when a hubspot id is missing`() = runBlocking {
        val orgId = Uuid.random()
        val memberId = Uuid.random()
        val orgProfileId = Uuid.random()
        val memberProfileId = Uuid.random()

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
        val hubspot = mockk<HubSpot>(relaxed = true)
        provides<HubSpot> { hubspot }

        RemoveHubSpotMemberNode(id = "n").run(context(), inputs(orgId, memberId))

        coVerify(exactly = 0) { hubspot.removeAssociation(any(), any(), any(), any()) }
    }

    @Test
    fun `a dry run records the action`() = runBlocking {
        val orgId = Uuid.random()
        val memberId = Uuid.random()
        val trace = DryRunTrace()

        RemoveHubSpotMemberNode(id = "a").run(context(dryRun = true, trace = trace), inputs(orgId, memberId))

        val action = trace.actions["a"]?.jsonObject ?: error("expected a recorded action")
        assertEquals("removeHubSpotMember", action["action"]?.jsonPrimitive?.content)
        assertEquals(orgId.toString(), action["organization"]?.jsonPrimitive?.content)
        assertEquals(memberId.toString(), action["member"]?.jsonPrimitive?.content)
    }

    @Test
    fun `round-trips with its settings`() {
        val node = RemoveHubSpotMemberNode(id = "c", name = "Remove")
        val decoded = json.decodeFromString(
            RemoveHubSpotMemberNode.serializer(),
            json.encodeToString(RemoveHubSpotMemberNode.serializer(), node),
        )
        assertEquals("Remove", decoded.name)
        assertEquals("c", decoded.id)
    }
}
