@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.profile.pipeline

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.organization.model.Organization
import bosca.profile.organization.pipeline.OrganizationEventToOrganizationNode
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.pipeline.ProfileEventToProfileAttributesNode
import bosca.profile.profile.pipeline.ProfileEventToProfileNode
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ProfileResolverNodesTest {

    private val profileService = mockk<ProfileService>()
    private val organizationService = mockk<OrganizationService>()

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
        }
    }
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ProfileService> { profileService }
        provides<OrganizationService> { organizationService }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("" to value))

    @Test
    fun `profile fromEvent resolves a typed profile id`() = runTest {
        val id = Uuid.random()
        val profile = mockk<Profile>()
        coEvery { profileService.getById(id) } returns profile

        val node = ProfileEventToProfileNode(id = "n1")
        val out = (node.run(context, inputs(PipelineValue.of(id, UUIDSerializer()))) as NodeResult.Output).value
        assertSame(profile, out?.value)
    }

    @Test
    fun `profile fromEvent resolves a plain JSON id downstream of a transform`() = runTest {
        val id = Uuid.random()
        val profile = mockk<Profile>()
        coEvery { profileService.getById(id) } returns profile

        val node = ProfileEventToProfileNode(id = "n1")
        val out = (node.run(context, inputs(PipelineValue.ofJson(JsonPrimitive(id.toString())))) as NodeResult.Output).value
        assertSame(profile, out?.value)
    }

    @Test
    fun `profile attributes resolves the attribute list for a typed profile`() = runTest {
        val id = Uuid.random()
        // A real Profile: the node decodes the slot via Profile.serializer(), which a mock can't survive.
        val profile = Profile(id = id, type = ProfileType.GENERIC, name = "p", visibility = ProfileVisibility.PUBLIC)
        val attributes = listOf(mockk<ProfileAttribute>())
        coEvery { profileService.getAttributes(id) } returns attributes

        val node = ProfileEventToProfileAttributesNode(id = "n1")
        val out = (node.run(context, inputs(PipelineValue.of(profile, Profile.serializer()))) as NodeResult.Output).value
        assertSame(attributes, out?.value)
    }

    @Test
    fun `organization fromEvent resolves the organization`() = runTest {
        val id = Uuid.random()
        val organization = mockk<Organization>()
        coEvery { organizationService.getOrganization(id) } returns organization

        val node = OrganizationEventToOrganizationNode(id = "n1")
        val out = (node.run(context, inputs(PipelineValue.of(id, UUIDSerializer()))) as NodeResult.Output).value
        assertSame(organization, out?.value)
    }

    @Test
    fun `resolvers fail clearly when the id input is missing`() = runTest {
        val node = ProfileEventToProfileNode(id = "n1", name = "Resolve profile")
        val e = assertFailsWith<IllegalStateException> {
            node.run(context, NodeInputs(emptyMap()))
        }
        assertSame(true, e.message?.contains("required input 'in'"))
    }

    @Test
    fun `resolvers fail when the input carries no id`() = runTest {
        val node = ProfileEventToProfileNode(id = "n1", name = "Resolve profile")
        assertFails {
            node.run(context, inputs(PipelineValue.ofJson(buildJsonObject { put("name", "x") })))
        }
    }
}
