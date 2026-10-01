@file:OptIn(InternalDI::class)

package bosca.segmentation.pipeline

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.SegmentationPipelineNodeSerializersProvider
import bosca.pipelines.service.PipelineService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.segmentation.configuration.JobQueueNames
import bosca.segmentation.jobs.RunPipelineForSegmentJob
import bosca.segmentation.model.Segment
import bosca.segmentation.service.SegmentService
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class SegmentRunContext(val campaignId: String, val attempt: Int)

/** Verifies the segment fan-out node's validation, queue hand-off, dry-run, and generated catalogue entry. */
class RunPipelineForSegmentNodeTest {

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUID::class, UUIDSerializer()) }
    }
    private val segmentId = UUID.random()
    private val pipelineId = UUID.random()
    private val principalId = UUID.random()
    private val principal = mockk<AuthenticatedPrincipal> { every { id } returns principalId }
    private val authentication = mockk<AuthenticationContext> { every { principal() } returns principal }
    private val segmentService = mockk<SegmentService>(relaxed = true)
    private val pipelineService = mockk<PipelineService>(relaxed = true)
    private val queue = mockk<JobQueue>(relaxed = true)
    private val segment = mockk<Segment>()
    private val pipeline = Pipeline(id = pipelineId, name = "Email each profile", acceptedInputType = "JSON")

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<SegmentService> { segmentService }
        provides<PipelineService> { pipelineService }
        provides<JobQueue>(name = JobQueueNames.segmentationJobQueue) { queue }
        coEvery { segmentService.getById(segmentId) } returns segment
        coEvery { pipelineService.get(pipelineId) } returns pipeline
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun node(
        segmentId: UUID? = this.segmentId,
        pipelineId: UUID? = this.pipelineId,
    ) = RunPipelineForSegmentNode(
        id = "segment-fan-out",
        segmentId = segmentId,
        pipelineId = pipelineId,
    )

    private fun context(authentication: AuthenticationContext = this.authentication) =
        PipelineContext(authentication, json)

    @Test
    fun `enqueues one fan-out job carrying references principal and encoded context`() = runTest {
        val additionalContext = SegmentRunContext(campaignId = "welcome", attempt = 2)
        val inputs = NodeInputs(
            mapOf("in" to PipelineValue.of(additionalContext, SegmentRunContext.serializer())),
        )

        val result = node().run(context(), inputs)

        assertNull(result.result, "the segment fan-out is a fire-and-forget sink")
        coVerify(exactly = 1) { segmentService.getById(segmentId) }
        coVerify(exactly = 1) { pipelineService.get(pipelineId) }

        val job = slot<Job>()
        coVerify(exactly = 1) { queue.enqueue(capture(job)) }
        val definition = json.decodeFromJsonElement(
            RunPipelineForSegmentJob.serializer(),
            job.captured.getDefinition(),
        )
        assertEquals(segmentId, definition.segmentId)
        assertEquals(pipelineId, definition.pipelineId)
        assertEquals(principalId, definition.principalId)
        assertEquals(
            json.encodeToJsonElement(SegmentRunContext.serializer(), additionalContext),
            definition.context,
        )
    }

    @Test
    fun `enqueues a null context when the input is unconnected`() = runTest {
        node().run(context(), NodeInputs(emptyMap()))

        val job = slot<Job>()
        coVerify(exactly = 1) { queue.enqueue(capture(job)) }
        val definition = json.decodeFromJsonElement(
            RunPipelineForSegmentJob.serializer(),
            job.captured.getDefinition(),
        )
        assertNull(definition.context)
    }

    @Test
    fun `requires an authenticated principal before resolving references`() = runTest {
        val anonymous = mockk<AuthenticationContext> { every { principal() } returns null }

        val error = assertFailsWith<IllegalStateException> {
            node().run(context(anonymous), NodeInputs(emptyMap()))
        }

        assertTrue(error.message.orEmpty().contains("authenticated principal"))
        coVerify(exactly = 0) { segmentService.getById(any()) }
        coVerify(exactly = 0) { pipelineService.get(any()) }
        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `requires both segment and pipeline settings`() = runTest {
        val missingSegment = assertFailsWith<IllegalStateException> {
            node(segmentId = null).run(context(), NodeInputs(emptyMap()))
        }
        val missingPipeline = assertFailsWith<IllegalStateException> {
            node(pipelineId = null).run(context(), NodeInputs(emptyMap()))
        }

        assertTrue(missingSegment.message.orEmpty().contains("no segment selected"))
        assertTrue(missingPipeline.message.orEmpty().contains("no pipeline selected"))
        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `fails before enqueue when the selected segment or pipeline is missing`() = runTest {
        coEvery { segmentService.getById(segmentId) } returns null
        val segmentError = assertFailsWith<IllegalStateException> {
            node().run(context(), NodeInputs(emptyMap()))
        }
        assertTrue(segmentError.message.orEmpty().contains("segment not found"))

        coEvery { segmentService.getById(segmentId) } returns segment
        coEvery { pipelineService.get(pipelineId) } returns null
        val pipelineError = assertFailsWith<IllegalStateException> {
            node().run(context(), NodeInputs(emptyMap()))
        }
        assertTrue(pipelineError.message.orEmpty().contains("pipeline not found"))
        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `dry run records forwarded context without resolving or enqueueing`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(authentication, json, dryRun = true, trace = trace)
        val additionalContext = SegmentRunContext(campaignId = "welcome", attempt = 2)
        val inputs = NodeInputs(
            mapOf("in" to PipelineValue.of(additionalContext, SegmentRunContext.serializer())),
        )

        val result = node().run(dry, inputs)

        assertNull(result.result)
        val action = trace.actions["segment-fan-out"] as JsonObject
        assertEquals(JsonPrimitive("runPipelineForSegment"), action["action"])
        assertEquals(JsonPrimitive(segmentId.toString()), action["segmentId"])
        assertEquals(JsonPrimitive(pipelineId.toString()), action["pipelineId"])
        assertEquals(
            json.encodeToJsonElement(SegmentRunContext.serializer(), additionalContext),
            action["context"],
        )
        coVerify(exactly = 0) { segmentService.getById(any()) }
        coVerify(exactly = 0) { pipelineService.get(any()) }
        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `keeps work-in-progress settings nullable and round-trips configured values`() {
        val empty = RunPipelineForSegmentNode(id = "new")
        assertEquals("", empty.name)
        assertEquals("", empty.description)
        assertNull(empty.segmentId)
        assertNull(empty.pipelineId)
        assertEquals(NodePosition(), empty.position)

        val roundTrip = json.decodeFromString(
            RunPipelineForSegmentNode.serializer(),
            json.encodeToString(RunPipelineForSegmentNode.serializer(), node()),
        )
        assertEquals(segmentId, roundTrip.segmentId)
        assertEquals(pipelineId, roundTrip.pipelineId)
    }

    @Test
    fun `generated descriptor exposes context input and segment and pipeline reference settings`() {
        val descriptor = SegmentationPipelineNodeSerializersProvider().descriptors
            .single { it.key == "segmentation.runPipelineForSegment" }

        assertEquals("Run Pipeline for Segment", descriptor.label)
        assertEquals("Segmentation", descriptor.group)
        assertEquals("Flow", descriptor.subgroup)
        assertEquals("Context", descriptor.inputs.single().typeLabel)
        assertEquals(listOf("segmentId", "pipelineId"), descriptor.settings.map { it.name })
        assertEquals(
            listOf(ReferenceSource.SEGMENT, ReferenceSource.PIPELINE),
            descriptor.settings.map { it.reference },
        )
    }
}
