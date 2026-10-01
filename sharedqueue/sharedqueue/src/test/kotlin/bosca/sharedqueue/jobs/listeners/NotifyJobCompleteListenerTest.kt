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

/**
 * [NotifyJobCompleteListener] publishes a [JobCompleteNotification] to the
 * [JOB_COMPLETE_CHANNEL] only on the successful terminal state ([JobStatus.COMPLETE]).
 * Every other status — including the terminal failure — must be ignored, because
 * this channel signals success to awaiting subscribers.
 */
class NotifyJobCompleteListenerTest {

    private val pubsub = mockk<PubSubService>(relaxed = true)
    private val listener = NotifyJobCompleteListener(pubsub)

    @AfterTest
    fun tearDown() = unmockkAll()

    @OptIn(Internal::class)
    private suspend fun fire(status: JobStatus) {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), CompleteJob::class)
        listener.onStatusChanged(job, status, null)
    }

    private fun publishedOnce() = coVerify(exactly = 1) {
        pubsub.publish(eq(JOB_COMPLETE_CHANNEL), any<SerializationStrategy<JobCompleteNotification>>(), any<JobCompleteNotification>())
    }

    private fun neverPublished() = coVerify(exactly = 0) {
        pubsub.publish(any(), any<SerializationStrategy<JobCompleteNotification>>(), any<JobCompleteNotification>())
    }

    @Test
    fun publishes_on_complete() = runTest {
        fire(JobStatus.COMPLETE)
        publishedOnce()
    }

    @Test
    fun ignores_terminal_failure() = runTest {
        fire(JobStatus.FAILED_AND_COMPLETE)
        neverPublished()
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

    @Test
    fun ignores_pending() = runTest {
        fire(JobStatus.PENDING)
        neverPublished()
    }
}

private class CompleteJob : JobExecutor {
    override suspend fun execute() {}
}
