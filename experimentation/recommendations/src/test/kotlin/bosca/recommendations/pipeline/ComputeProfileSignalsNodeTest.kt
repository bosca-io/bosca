@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.recommendations.pipeline

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.recommendations.service.ProfileSignalComputeService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi

/**
 * the node reads a profile-attribute event's `attributeIds` from its JSON form and recomputes
 * those attributes' Personalization Signals. The event's typed class isn't on this module's classpath, so
 * inputs are supplied here as their JSON form (exactly what the runtime carries to the node).
 */
class ComputeProfileSignalsNodeTest {

    private val service = mockk<ProfileSignalComputeService>(relaxed = true)
    private val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } }
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ProfileSignalComputeService> { service }
        provides<Json>(singleton = true) { json }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("" to value))

    /** An event whose JSON carries `attributeIds` — the shape ProfileAttributesAdded/Updated serialize to. */
    private fun event(vararg attributeIds: String) = PipelineValue.ofJson(
        buildJsonObject {
            put("profileId", UUID.random().toString())
            putJsonArray("attributeIds") { attributeIds.forEach { add(it) } }
        },
    )

    @Test
    fun `recomputes signals for the event's attribute ids and passes the event through`() = runTest {
        val a = UUID.random()
        val b = UUID.random()
        coEvery { service.computeForAttributes(any()) } returns Unit

        val input = event(a.toString(), b.toString())
        val out = ComputeProfileSignalsNode(id = "n1").run(context, inputs(input))

        coVerify(exactly = 1) { service.computeForAttributes(listOf(a, b)) }
        assertEquals(input, (out as NodeResult.Output).result)
    }

    @Test
    fun `recomputes by profile and type for a verified event (no attribute ids)`() = runTest {
        val profileId = UUID.random()
        val verified = PipelineValue.ofJson(
            buildJsonObject {
                put("profileId", profileId.toString())
                put("typeId", "bosca.profiles.email")
                put("source", "email")
            },
        )
        ComputeProfileSignalsNode(id = "n1").run(context, inputs(verified))
        coVerify(exactly = 1) { service.computeForProfileType(profileId, "bosca.profiles.email") }
        coVerify(exactly = 0) { service.computeForAttributes(any()) }
    }

    @Test
    fun `skips malformed attribute ids`() = runTest {
        val good = UUID.random()
        ComputeProfileSignalsNode(id = "n1").run(context, inputs(event("not-a-uuid", good.toString())))
        coVerify(exactly = 1) { service.computeForAttributes(listOf(good)) }
    }

    @Test
    fun `does nothing when the event has no attribute ids`() = runTest {
        ComputeProfileSignalsNode(id = "n1").run(context, inputs(event()))
        coVerify(exactly = 0) { service.computeForAttributes(any()) }
    }

    @Test
    fun `does nothing when the event names neither ids nor a profile type`() = runTest {
        val noField = PipelineValue.ofJson(buildJsonObject { put("source", "email") })
        ComputeProfileSignalsNode(id = "n1").run(context, inputs(noField))
        coVerify(exactly = 0) { service.computeForAttributes(any()) }
        coVerify(exactly = 0) { service.computeForProfileType(any(), any()) }
    }

    @Test
    fun `does nothing when there is no input`() = runTest {
        val out = ComputeProfileSignalsNode(id = "n1").run(context, NodeInputs(emptyMap()))
        coVerify(exactly = 0) { service.computeForAttributes(any()) }
        assertEquals(NodeResult.Output::class, out::class)
    }

    @Test
    fun `dry run records the action and does not call the service`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        ComputeProfileSignalsNode(id = "n1").run(dry, inputs(event(UUID.random().toString())))
        assertEquals(true, trace.actions.containsKey("n1"))
        coVerify(exactly = 0) { service.computeForAttributes(any()) }
    }

    @Test
    fun `dry run without a trace passes the input through`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = null)
        val out = ComputeProfileSignalsNode(id = "n1").run(dry, inputs(event(UUID.random().toString())))
        assertEquals(NodeResult.Output::class, out::class)
    }

    @Test
    fun `dry run tolerates a missing input, tracing zero attributes`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        ComputeProfileSignalsNode(id = "n1").run(dry, NodeInputs(emptyMap()))
        val recorded = trace.actions["n1"] as JsonObject
        assertEquals(0, recorded["attributes"]?.jsonPrimitive?.int)
        coVerify(exactly = 0) { service.computeForAttributes(any()) }
    }

    @Test
    fun `construction defaults and serialization round-trip`() {
        assertEquals("", ComputeProfileSignalsNode(id = "id-1").name)
        val full = ComputeProfileSignalsNode(id = "id-2", name = "Signals", description = "desc", position = NodePosition())
        assertEquals("Signals", full.name)
        val encoded = json.encodeToString(ComputeProfileSignalsNode.serializer(), full)
        assertEquals("id-2", json.decodeFromString(ComputeProfileSignalsNode.serializer(), encoded).id)
        // Minimal object → the "field absent → default" branch of each optional property.
        assertEquals("id-3", json.decodeFromString(ComputeProfileSignalsNode.serializer(), """{"id":"id-3"}""").id)
        // Encoding a defaults-only node exercises the serializer's "value equals default → skip" branches.
        val reEncoded = json.encodeToString(ComputeProfileSignalsNode.serializer(), ComputeProfileSignalsNode(id = "id-4"))
        assertEquals("id-4", json.decodeFromString(ComputeProfileSignalsNode.serializer(), reEncoded).id)
        // encodeDefaults writes the optional fields too → the serializer's "encode default" branch.
        val withDefaults = Json {
            serializersModule = SerializersModule { contextual(UUIDSerializer()) }; encodeDefaults = true
        }
        val re = withDefaults.encodeToString(ComputeProfileSignalsNode.serializer(), ComputeProfileSignalsNode("id-5"))
        assertEquals("id-5", withDefaults.decodeFromString(ComputeProfileSignalsNode.serializer(), re).id)
        // The strict decoder (no ignoreUnknownKeys) rejects an unknown key → the unknown-field branch.
        assertFailsWith<Exception> {
            json.decodeFromString(ComputeProfileSignalsNode.serializer(), """{"id":"x","unexpected":1}""")
        }
    }
}
