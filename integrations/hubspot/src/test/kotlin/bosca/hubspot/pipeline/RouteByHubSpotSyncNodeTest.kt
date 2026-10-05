@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.hubspot.pipeline

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * [RouteByHubSpotSyncNode] is the add-vs-update router: it passes the profile id through on `synced`
 * when a prior sync stamped a `bosca.profiles.hubspot.id`, or on `unsynced` when none exists. Both
 * ports carry the profile id (not the HubSpot id) so each branch can recompute properties for itself.
 * We verify the routed port and passthrough value for both outcomes, the missing-profile error, and
 * serialization round-trip.
 */
class RouteByHubSpotSyncNodeTest {

    private val json = Json

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    private fun context() = PipelineContext(AuthenticationContext(null, null), json, runId = null)

    private fun profileInputs(profileId: Uuid) =
        NodeInputs(mapOf("profile" to PipelineValue.ofJson(JsonPrimitive(profileId.toString()))))

    private fun stampedId(profile: Uuid) = ProfileAttribute(
        profile = profile,
        typeId = "bosca.profiles.hubspot.id",
        visibility = ProfileVisibility.SYSTEM,
        confidence = 100,
        priority = 100,
        source = "test",
        attributes = JsonObject(mapOf("id" to JsonPrimitive("hs-1"))),
    )

    @Test
    fun `routes the profile id on synced when already synced`() = runBlocking {
        val profileId = Uuid.random()
        val profileService = mockk<ProfileService> {
            coEvery { getAttributes(profileId) } returns listOf(stampedId(profileId))
        }
        provides<ProfileService> { profileService }

        val out = RouteByHubSpotSyncNode(id = "n").run(context(), profileInputs(profileId)) as NodeResult.Output

        assertEquals("synced", out.value?.port)
        assertEquals(profileId.toString(), out.value?.encode(json)?.jsonPrimitive?.content)
    }

    @Test
    fun `routes the profile id on unsynced when not yet synced`() = runBlocking {
        val profileId = Uuid.random()
        val profileService = mockk<ProfileService> {
            coEvery { getAttributes(profileId) } returns emptyList()
        }
        provides<ProfileService> { profileService }

        val out = RouteByHubSpotSyncNode(id = "n").run(context(), profileInputs(profileId)) as NodeResult.Output

        assertEquals("unsynced", out.value?.port)
        assertEquals(profileId.toString(), out.value?.encode(json)?.jsonPrimitive?.content)
    }

    @Test
    fun `requires a profile uuid`() {
        runBlocking {
            assertFailsWith<IllegalStateException> {
                RouteByHubSpotSyncNode(id = "n").run(context(), NodeInputs(emptyMap()))
            }
        }
    }

    @Test
    fun `round-trips`() {
        val node = RouteByHubSpotSyncNode(id = "r", name = "Route")
        assertEquals(
            "Route",
            json.decodeFromString(RouteByHubSpotSyncNode.serializer(), json.encodeToString(RouteByHubSpotSyncNode.serializer(), node)).name,
        )
    }
}
