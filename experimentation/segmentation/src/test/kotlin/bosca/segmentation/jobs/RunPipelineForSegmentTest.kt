package bosca.segmentation.jobs

import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.security.service.AuthenticationContext
import bosca.segmentation.model.SegmentPipelineInput
import bosca.segmentation.service.SegmentService
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [runPipelineForSegment] — the fan-out that starts one durable pipeline run
 * per segment member. Pins the operator-visible contract:
 *
 *   1. Missing pipeline: bail out before touching the audience, no run started.
 *   2. Empty segment: no runs.
 *   3. Happy path: one run per member with `{ segmentId, profileId, context }`.
 *   4. Additional context is copied to every child run.
 *   5. Resilience: one failed start does not abort the rest.
 *   6. Cancellation always propagates.
 */
class RunPipelineForSegmentTest {

    private val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } }

    private val segmentService = mockk<SegmentService>()
    private val pipelineService = mockk<PipelineService>()
    private val pipelineRunService = mockk<PipelineRunService>()
    private val authentication = mockk<AuthenticationContext>(relaxed = true)

    private val segmentId = UUID.parse("00000000-0000-0000-0000-0000000000a1")
    private val pipelineId = UUID.parse("00000000-0000-0000-0000-0000000000b1")
    private val principalId = UUID.parse("00000000-0000-0000-0000-0000000000c1")
    private val pipeline = Pipeline(id = pipelineId, name = "segment pipeline", acceptedInputType = "JSON")

    private val job = RunPipelineForSegmentJob(
        segmentId = segmentId,
        pipelineId = pipelineId,
        principalId = principalId,
    )

    private suspend fun run(pageSize: Int, job: RunPipelineForSegmentJob = this.job) = runPipelineForSegment(
        job = job,
        authentication = authentication,
        segmentService = segmentService,
        pipelineService = pipelineService,
        pipelineRunService = pipelineRunService,
        pageSize = pageSize,
    )

    @Test
    fun `throws and does not page the audience when the pipeline is missing`() = runTest {
        coEvery { pipelineService.get(pipelineId) } returns null

        assertFailsWith<IllegalStateException> { run(pageSize = 2) }

        coVerify(exactly = 0) { segmentService.getAudienceProfileIdsPaged(any(), any(), any()) }
        coVerify(exactly = 0) { pipelineRunService.start(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `starts no runs for an empty segment`() = runTest {
        coEvery { pipelineService.get(pipelineId) } returns pipeline
        coEvery { segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 0, 2) } returns emptyList()

        run(pageSize = 2)

        coVerify(exactly = 0) { pipelineRunService.start(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `starts one run per member across pages seeded with segmentId and profileId`() = runTest {
        val p1 = UUID.random()
        val p2 = UUID.random()
        val p3 = UUID.random()
        coEvery { pipelineService.get(pipelineId) } returns pipeline
        coEvery { segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 0, 2) } returns listOf(p1, p2)
        coEvery { segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 2, 2) } returns listOf(p3)
        coEvery { segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 4, 2) } returns emptyList()
        val inputs = mutableListOf<PipelineValue>()
        coEvery {
            pipelineRunService.start(pipeline, capture(inputs), any(), any(), authentication)
        } returns null

        run(pageSize = 2)

        coVerify(exactly = 3) {
            pipelineRunService.start(pipeline, any(), any(), any(), authentication)
        }
        // Paging walked the audience to exhaustion.
        coVerify(exactly = 1) { segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 4, 2) }
        // Each run is seeded with the member's profile id under the originating segment.
        val decoded = inputs.map {
            json.decodeFromJsonElement(SegmentPipelineInput.serializer(), it.encode(json))
        }
        assertEquals(listOf(p1, p2, p3), decoded.map { it.profileId })
        assertTrue(decoded.all { it.segmentId == segmentId })
        assertTrue(decoded.all { it.context == null })
    }

    @Test
    fun `copies additional context to every per-profile run`() = runTest {
        val profiles = List(3) { UUID.random() }
        val additionalContext = buildJsonObject {
            put("campaignId", "welcome")
            put("attempt", 2)
        }
        coEvery { pipelineService.get(pipelineId) } returns pipeline
        coEvery { segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 0, 2) } returns profiles.take(2)
        coEvery { segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 2, 2) } returns profiles.drop(2)
        coEvery { segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 4, 2) } returns emptyList()
        val inputs = mutableListOf<PipelineValue>()
        coEvery {
            pipelineRunService.start(pipeline, capture(inputs), any(), any(), authentication)
        } returns null

        run(pageSize = 2, job = job.copy(context = additionalContext))

        coVerify(exactly = 3) {
            pipelineRunService.start(pipeline, any(), any(), any(), authentication)
        }
        val decoded = inputs.map {
            json.decodeFromJsonElement(SegmentPipelineInput.serializer(), it.encode(json))
        }
        assertEquals(profiles, decoded.map { it.profileId })
        assertTrue(decoded.all { it.segmentId == segmentId })
        assertTrue(decoded.all { it.context == additionalContext })
    }

    @Test
    fun `a failed start for one member does not abort the others`() = runTest {
        coEvery { pipelineService.get(pipelineId) } returns pipeline
        coEvery { segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 0, 10) } returns
            listOf(UUID.random(), UUID.random(), UUID.random())
        coEvery { segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 10, 10) } returns emptyList()
        var calls = 0
        coEvery { pipelineRunService.start(any(), any(), any(), any(), any()) } answers {
            calls++
            if (calls == 2) throw RuntimeException("boom") else null
        }

        run(pageSize = 10)

        // All three members were attempted despite the middle failure.
        coVerify(exactly = 3) { pipelineRunService.start(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancellation from a start propagates and halts the fan-out`() = runTest {
        coEvery { pipelineService.get(pipelineId) } returns pipeline
        coEvery { segmentService.getAudienceProfileIdsPaged(listOf(segmentId), 0, 10) } returns
            listOf(UUID.random(), UUID.random())
        coEvery { pipelineRunService.start(any(), any(), any(), any(), any()) } throws
            CancellationException("cancelled")

        assertFailsWith<CancellationException> { run(pageSize = 10) }

        // Halted at the first member — the second was never attempted.
        coVerify(exactly = 1) { pipelineRunService.start(any(), any(), any(), any(), any()) }
    }
}
