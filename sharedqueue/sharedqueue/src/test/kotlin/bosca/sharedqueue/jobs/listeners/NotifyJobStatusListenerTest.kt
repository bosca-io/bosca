package bosca.sharedqueue.jobs.listeners

import bosca.core.annotations.Internal
import bosca.pubsub.PubSubService
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobStatus
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test

class NotifyJobStatusListenerTest {

    private val pubsub = mockk<PubSubService>(relaxed = true)
    private val listener = NotifyJobStatusListener(pubsub)

    @AfterTest
    fun tearDown() = unmockkAll()

    @OptIn(Internal::class)
    private suspend fun fire(status: JobStatus) {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NotifyStatusJob::class)
        listener.onStatusChanged(job, status, null)
    }

    private fun publishedOnce() = coVerify {
        pubsub.publish(eq(JOB_STATUS_CHANNEL), any<SerializationStrategy<JobStatusNotification>>(), any<JobStatusNotification>())
    }

    private fun neverPublished() = coVerify(exactly = 0) {
        pubsub.publish(any(), any<SerializationStrategy<JobStatusNotification>>(), any<JobStatusNotification>())
    }

    @Test
    fun publishes_on_complete() = runTest {
        fire(JobStatus.COMPLETE)
        publishedOnce()
    }

    @Test
    fun publishes_on_terminal_failure() = runTest {
        fire(JobStatus.FAILED_AND_COMPLETE)
        publishedOnce()
    }

    @Test
    fun ignores_retryable_failure() = runTest {
        fire(JobStatus.FAILED)
        neverPublished()
    }

    @Test
    fun ignores_running() = runTest {
        fire(JobStatus.RUNNING)
        neverPublished()
    }
}

private class NotifyStatusJob : JobExecutor {
    override suspend fun execute() {}
}
