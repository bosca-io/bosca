@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.hubspot.pipeline

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Add HubSpot Profile Attribute node: stamps the synced HubSpot id onto a profile as a SYSTEM
 * `bosca.profiles.hubspot.id` attribute (so a later sync updates rather than duplicates) and outputs
 * the new attribute id. A dry run records the would-be write and stamps nothing.
 */
class AddHubSpotProfileAttributeNodeTest {

    private val json = Json
    private val node = AddHubSpotProfileAttributeNode(id = "addId")

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    private fun inputs(profileId: Uuid, id: JsonElement) = NodeInputs(
        mapOf(
            "profile" to PipelineValue.ofJson(JsonPrimitive(profileId.toString())),
            "id" to PipelineValue.ofJson(id),
        )
    )

    /** An existing SYSTEM `bosca.profiles.hubspot.id` attribute carrying [hubspotId], as `getAttributes` would return. */
    private fun hubspotAttribute(profileId: Uuid, hubspotId: String) = ProfileAttribute(
        profile = profileId,
        typeId = "bosca.profiles.hubspot.id",
        visibility = ProfileVisibility.SYSTEM,
        confidence = 100,
        priority = 100,
        source = "pipeline",
        attributes = JsonObject(mapOf("id" to JsonPrimitive(hubspotId))),
    )

    @Test
    fun `stamps the hubspot id as a system attribute and passes the profile through`() = runBlocking {
        val profileId = Uuid.random()
        val captured = slot<List<ProfileAttributeInput>>()
        val profileService = mockk<ProfileService> {
            coEvery { getAttributes(profileId) } returns emptyList()
            coEvery { addAttributes(profileId, capture(captured), any()) } returns emptyList()
        }
        provides<ProfileService> { profileService }

        val context = PipelineContext(AuthenticationContext(null, null), json)
        val result = node.run(context, inputs(profileId, JsonPrimitive("contact-1"))) as NodeResult.Output

        // The legacy stamp is reproduced: a SYSTEM `bosca.profiles.hubspot.id` carrying the id.
        val input = captured.captured.single()
        assertEquals("bosca.profiles.hubspot.id", input.typeId)
        assertEquals("contact-1", input.attributes?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertEquals(ProfileVisibility.SYSTEM, input.visibility)
        assertEquals("pipeline", input.source)

        // The node passes the profile id through so later steps sequence after the stamp.
        assertEquals(profileId.toString(), result.value?.encode(json)?.jsonPrimitive?.content)
    }

    @Test
    fun `is idempotent — re-stamping the same id writes nothing and passes the profile through`() = runBlocking {
        val profileId = Uuid.random()
        val profileService = mockk<ProfileService> {
            coEvery { getAttributes(profileId) } returns listOf(hubspotAttribute(profileId, "contact-1"))
        }
        provides<ProfileService> { profileService }

        val context = PipelineContext(AuthenticationContext(null, null), json)
        val result = node.run(context, inputs(profileId, JsonPrimitive("contact-1"))) as NodeResult.Output

        // The id already matches, so no second stamp is written...
        coVerify(exactly = 0) { profileService.addAttributes(any(), any(), any()) }
        // ...but the profile id still passes through so later steps sequence the same way.
        assertEquals(profileId.toString(), result.value?.encode(json)?.jsonPrimitive?.content)
    }

    @Test
    fun `fails when the profile already carries a different hubspot id`() = runBlocking {
        val profileId = Uuid.random()
        val profileService = mockk<ProfileService> {
            coEvery { getAttributes(profileId) } returns listOf(hubspotAttribute(profileId, "contact-1"))
        }
        provides<ProfileService> { profileService }

        val context = PipelineContext(AuthenticationContext(null, null), json)
        val error = assertFailsWith<IllegalStateException> {
            node.run(context, inputs(profileId, JsonPrimitive("contact-2")))
        }
        assertTrue(error.message?.contains("already has a HubSpot id of contact-1") == true)
        coVerify(exactly = 0) { profileService.addAttributes(any(), any(), any()) }
    }

    @Test
    fun `a dry run records the would-be write and stamps nothing`() = runBlocking {
        val profileService = mockk<ProfileService>(relaxed = true)
        provides<ProfileService> { profileService }

        val trace = DryRunTrace()
        val context = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        node.run(context, inputs(Uuid.random(), JsonPrimitive("contact-1")))

        coVerify(exactly = 0) { profileService.addAttributes(any(), any(), any()) }
        assertEquals("addHubSpotId", trace.actions["addId"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
        assertEquals("contact-1", trace.actions["addId"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
    }

    @Test
    fun `fails when the profile input is missing`() {
        runBlocking {
            val context = PipelineContext(AuthenticationContext(null, null), json)
            val onlyId = NodeInputs(mapOf("id" to PipelineValue.ofJson(JsonPrimitive("contact-1"))))
            val error = assertFailsWith<IllegalStateException> { node.run(context, onlyId) }
            assertTrue(error.message?.contains("required input 'profile'") == true)
        }
    }

    @Test
    fun `fails when the id input carries no content`() {
        runBlocking {
            val context = PipelineContext(AuthenticationContext(null, null), json)
            val error = assertFailsWith<IllegalStateException> { node.run(context, inputs(Uuid.random(), JsonNull)) }
            assertTrue(error.message?.contains("required input 'id'") == true)
        }
    }
}
