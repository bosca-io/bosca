@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.hubspot.pipeline

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.hubspot.client.HubSpot
import bosca.hubspot.transformer.HubSpotEntityTransformer
import bosca.hubspot.transformer.HubspotData
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.NodeSuspensionService
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The HubSpot list / subscription / membership-association pipeline nodes do their HubSpot work in
 * their `execute()` (reusing the same client steps the legacy sync jobs run), and flip
 * `willSuspend = true` so a durable run parks on that work inside a child of the run job rather than
 * fire-and-forget. We verify both: the inline work a non-durable run runs straight away, and that a
 * durable run hands the node to the generic [NodeSuspensionService] (which re-runs `execute()` in a job).
 */
class HubSpotSyncNodesTest {

    private val json = Json

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    private fun context(dryRun: Boolean = false, trace: DryRunTrace? = null, runId: bosca.serialization.UUID? = null) =
        PipelineContext(AuthenticationContext(null, null), json, dryRun = dryRun, trace = trace, runId = runId)

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

    // ── durable runs suspend via the generic mechanism ──────────────────────────────────────────────

    @Test
    fun `a durable run parks each node on the NodeSuspensionService`() = runBlocking {
        val suspension = mockk<NodeSuspensionService>(relaxed = true)
        provides<NodeSuspensionService> { suspension }
        val runId = Uuid.random()
        val nodes = listOf(
            AddToHubSpotListNode(id = "n", listId = "l"),
            SubscribeToHubSpotNode(id = "n", subscriptionId = "1"),
            AssociateHubSpotMembershipsNode(id = "n"),
        )
        for (node in nodes) {
            val result = node.run(context(runId = runId), profileInputs(Uuid.random()))
            assertTrue(result is NodeResult.Suspend, "${node::class.simpleName} should suspend in a durable run")
            result.enqueue()
        }
        coVerify(exactly = 3) { suspension.suspendNode(any(), any(), any()) }
    }

    // ── dry runs trace the would-be action ──────────────────────────────────────────────────────────

    @Test
    fun `a dry run records the action for each node and does no work`() = runBlocking {
        val trace = DryRunTrace()
        AddToHubSpotListNode(id = "a", listId = "l").run(context(dryRun = true, trace = trace), profileInputs(Uuid.random()))
        SubscribeToHubSpotNode(id = "b", subscriptionId = "1").run(context(dryRun = true, trace = trace), profileInputs(Uuid.random()))
        AssociateHubSpotMembershipsNode(id = "c").run(context(dryRun = true, trace = trace), profileInputs(Uuid.random()))
        assertEquals("addToHubSpotList", trace.actions["a"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
        assertEquals("subscribeToHubSpot", trace.actions["b"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
        assertEquals("associateHubSpotMemberships", trace.actions["c"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
    }

    // ── non-durable execute() does the work inline ──────────────────────────────────────────────────

    @Test
    fun `add-to-list resolves the contact and adds it to the list`() = runBlocking {
        val profileId = Uuid.random()
        val profileService = mockk<ProfileService> {
            coEvery { getAttributes(profileId) } returns listOf(attribute("bosca.profiles.hubspot.id", "id", "contact-1", profileId))
        }
        val hubspot = mockk<HubSpot>(relaxed = true)
        provides<ProfileService> { profileService }
        provides<HubSpot> { hubspot }

        val out = AddToHubSpotListNode(id = "n", listId = "list-1").run(context(), profileInputs(profileId)) as NodeResult.Output

        coVerify(exactly = 1) { hubspot.addToList("list-1", "contact-1") }
        // The node passes the profile id through so the run can continue.
        assertEquals(profileId.toString(), out.value?.encode(json)?.jsonPrimitive?.content)
    }

    @Test
    fun `subscribe resolves the email and sets the subscription`() = runBlocking {
        val profileId = Uuid.random()
        val profileService = mockk<ProfileService> {
            coEvery { getAttributes(profileId) } returns listOf(attribute("bosca.profiles.email", "email", "a@b.c", profileId))
        }
        val hubspot = mockk<HubSpot>(relaxed = true)
        provides<ProfileService> { profileService }
        provides<HubSpot> { hubspot }

        SubscribeToHubSpotNode(id = "n", subscriptionId = "42").run(context(), profileInputs(profileId))

        coVerify(exactly = 1) { hubspot.setSubscription("a@b.c", 42, any()) }
    }

    @Test
    fun `associate transforms the profile and creates its associations`() = runBlocking {
        val profileId = Uuid.random()
        val profileData = mockk<HubspotData>()
        val transformer = mockk<HubSpotEntityTransformer> { coEvery { transform(Unit, profileId) } returns profileData }
        val hubspot = mockk<HubSpot>(relaxed = true)
        provides<HubSpotEntityTransformer> { transformer }
        provides<HubSpot> { hubspot }

        AssociateHubSpotMembershipsNode(id = "n").run(context(), profileInputs(profileId))

        coVerify(exactly = 1) { hubspot.createAssociations(profileData) }
    }

    // ── validation ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `execute requires a profile and the node's configuration`() {
        runBlocking {
            assertFailsWith<IllegalStateException> { AddToHubSpotListNode(id = "n", listId = "").run(context(), profileInputs(Uuid.random())) }
            assertFailsWith<IllegalStateException> { SubscribeToHubSpotNode(id = "n", subscriptionId = "").run(context(), profileInputs(Uuid.random())) }
            assertFailsWith<IllegalStateException> { AddToHubSpotListNode(id = "n", listId = "l").run(context(), NodeInputs(emptyMap())) }
        }
    }

    // ── serialization ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `nodes round-trip with their settings`() {
        val list = AddToHubSpotListNode(id = "a", name = "List", listId = "l1")
        assertEquals(list.listId, json.decodeFromString(AddToHubSpotListNode.serializer(), json.encodeToString(AddToHubSpotListNode.serializer(), list)).listId)
        val sub = SubscribeToHubSpotNode(id = "b", subscriptionId = "7")
        assertEquals(sub.subscriptionId, json.decodeFromString(SubscribeToHubSpotNode.serializer(), json.encodeToString(SubscribeToHubSpotNode.serializer(), sub)).subscriptionId)
        val assoc = AssociateHubSpotMembershipsNode(id = "c", name = "Assoc")
        assertEquals("Assoc", json.decodeFromString(AssociateHubSpotMembershipsNode.serializer(), json.encodeToString(AssociateHubSpotMembershipsNode.serializer(), assoc)).name)
    }
}
