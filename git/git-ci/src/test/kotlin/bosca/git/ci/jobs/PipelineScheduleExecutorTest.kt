@file:OptIn(bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)

package bosca.git.ci.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.PipelineScheduleJob
import bosca.git.service.PipelineScheduleRunner
import bosca.scheduler.model.ScheduledJobExecutionContext
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
import kotlin.test.assertFailsWith

class PipelineScheduleExecutorTest {

    private val json = Json
    private val runner = mockk<PipelineScheduleRunner>(relaxed = true)
    private val queue = mockk<JobQueue>(relaxed = true)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<PipelineScheduleRunner>(singleton = true) { runner }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `executor forwards scheduler-owned id and principal snapshot`() = runTest {
        val pipelineId = UUID.random()
        val scheduledJobId = UUID.random()
        val principalId = UUID.random()
        val job = job(pipelineId, scheduledJobId, principalId)

        withContext(queue.asCoroutineContext(job)) {
            PipelineScheduleExecutor().execute()
        }

        coVerify { runner.run(scheduledJobId, pipelineId, principalId) }
    }

    @Test
    fun `executor refuses a scheduler occurrence without a principal`() = runTest {
        val job = job(UUID.random(), UUID.random(), null)

        assertFailsWith<IllegalStateException> {
            withContext(queue.asCoroutineContext(job)) {
                PipelineScheduleExecutor().execute()
            }
        }
        coVerify(exactly = 0) { runner.run(any(), any(), any()) }
    }

    private fun job(pipelineId: UUID, scheduledJobId: UUID, principalId: UUID?) =
        InternalJobConstructor(
            definition = json.encodeToJsonElement(
                PipelineScheduleJob.serializer(),
                PipelineScheduleJob(pipelineId),
            ),
            executor = PipelineScheduleExecutor::class,
        ).apply {
            setContext(
                json.encodeToJsonElement(
                    ScheduledJobExecutionContext.serializer(),
                    ScheduledJobExecutionContext(scheduledJobId, principalId),
                )
            )
        }
}
