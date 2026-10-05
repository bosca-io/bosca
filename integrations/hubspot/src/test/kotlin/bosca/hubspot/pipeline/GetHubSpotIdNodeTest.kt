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
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * [GetHubSpotIdNode] is a pure read that resolves the HubSpot id a prior sync stamped on a profile's
 * `bosca.profiles.hubspot.id` attribute and emits it on `out`. It sits on the update branch (where
 * the router has already established the profile is synced), so a missing id is a real error rather
 * than a branch. We verify the happy path, the missing-id error, the missing-profile error, and that
 * the node round-trips its serialized form.
 */
class GetHubSpotIdNodeTest {

    private val json = Json

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    private fun context() = PipelineContext(AuthenticationContext(null, null), json, runId = null)

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

    @Test
    fun `emits the existing hubspot id on out`() = runBlocking {
        val profileId = Uuid.random()
        val profileService = mockk<ProfileService> {
            coEvery { getAttributes(profileId) } returns listOf(attribute("bosca.profiles.hubspot.id", "id", "hs-1", profileId))
        }
        provides<ProfileService> { profileService }

        val out = GetHubSpotIdNode(id = "n").run(context(), profileInputs(profileId)) as NodeResult.Output

        assertEquals("hs-1", out.value?.encode(json)?.jsonPrimitive?.content)
    }

    @Test
    fun `fails when the profile has no stamped hubspot id`() {
        runBlocking {
            val profileId = Uuid.random()
            val profileService = mockk<ProfileService> {
                coEvery { getAttributes(profileId) } returns emptyList()
            }
            provides<ProfileService> { profileService }

            val error = assertFailsWith<IllegalStateException> {
                GetHubSpotIdNode(id = "n").run(context(), profileInputs(profileId))
            }
            assertTrue(error.message?.contains("found no HubSpot id") == true)
        }
    }

    @Test
    fun `requires a profile uuid`() {
        runBlocking {
            assertFailsWith<IllegalStateException> {
                GetHubSpotIdNode(id = "n").run(context(), NodeInputs(emptyMap()))
            }
        }
    }

    @Test
    fun `round-trips`() {
        val node = GetHubSpotIdNode(id = "g", name = "Get Id")
        assertEquals(
            "Get Id",
            json.decodeFromString(GetHubSpotIdNode.serializer(), json.encodeToString(GetHubSpotIdNode.serializer(), node)).name,
        )
    }
}
