@file:OptIn(ExperimentalUuidApi::class, bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)

package bosca.pipelines.trigger

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.service.PipelineRunService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi

/**
 * The on-demand run's driving job: a manual / API run is created in a request thread
 * that is not itself a run job, so it is enqueued and driven here instead of inline — giving a
 * suspending node a real run job to attach its backing work to and resume from. The executor carries
 * the run drive listener (so backing children resume the run) and drives the already-created run.
 */
class PipelineManualRunJobExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val runId = UUID.random()
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val executor = PipelineManualRunJobExecutor(runService)

    // AbstractJobExecutor.getJobDefinition() decodes the definition via the DI-provided Json.
    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `drives the already-created on-demand run`() = runTest {
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(PipelineManualRunJob.serializer(), PipelineManualRunJob(runId)),
            executor = PipelineManualRunJobExecutor::class,
        )
        // execute() carries the drive listener (requires the job be lockable — a NIL-id job is) and then
        // drives the run; if attaching the listener failed it would throw before process is reached.
        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 1) { runService.process(runId) }
    }
}
