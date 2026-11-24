package bosca.analytics.service

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.queue.AnalyticsEventQueue
import bosca.analytics.queue.AnalyticsEventQueueStatus
import bosca.analytics.repository.EventRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AnalyticsEventConsumerLifecycleTest {
    @Test fun `invalid worker and batch limits fail before consuming any work`(): Unit = runBlocking {
        val queue = mockk<AnalyticsEventQueue>()
        val repository = mockk<EventRepository>()
        for (config in listOf(
            EventProcessingConfiguration(workerCount = 0),
            EventProcessingConfiguration(workerCount = 1, channelCapacity = -1),
            EventProcessingConfiguration(workerCount = 1, natsBatchSize = 0),
        )) {
            val consumer = AnalyticsEventConsumer(queue, repository, EventPipelineTransforms(emptyList()), config)
            assertFailsWith<IllegalArgumentException> { consumer.run() }
        }
        coVerify(exactly = 0) { queue.receiveBatch(any(), any()) }
    }

    @Test fun `an empty processing group has no storage or queue side effects`(): Unit = runBlocking {
        val queue = mockk<AnalyticsEventQueue>()
        val repository = mockk<EventRepository>()
        AnalyticsEventConsumer(queue, repository, EventPipelineTransforms(emptyList()), EventProcessingConfiguration()).processDeliveries(emptyList())
        coVerify(exactly = 0) { repository.store(any()); repository.process(any()); repository.flush(); queue.acknowledgeBatch(any()); queue.retryBatch(any(), any()) }
    }

    @Test fun `worker cancellation stops polling when receive returns normally`(): Unit = runBlocking {
        val queue = mockk<AnalyticsEventQueue>()
        val received = CompletableDeferred<Unit>()
        coEvery { queue.receiveBatch(any(), any()) } coAnswers {
            currentCoroutineContext().cancel()
            received.complete(Unit)
            emptyList()
        }
        val consumer = AnalyticsEventConsumer(queue, mockk(), EventPipelineTransforms(emptyList()), EventProcessingConfiguration(workerCount = 1))
        val running = launch { consumer.run() }
        try {
            withTimeout(3000) { received.await() }
            yield()
            coVerify(exactly = 1) { queue.receiveBatch(any(), any()) }
        } finally { running.cancelAndJoin() }
    }

    @Test fun `status monitor cancellation stops normally after an in-progress status call returns`(): Unit = runTest {
        val queue = mockk<AnalyticsEventQueue>()
        val observed = CompletableDeferred<Unit>()
        coEvery { queue.receiveBatch(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { queue.status() } coAnswers {
            currentCoroutineContext().cancel()
            observed.complete(Unit)
            AnalyticsEventQueueStatus(pending = 0, storageBytes = 0)
        }
        val consumer = AnalyticsEventConsumer(queue, mockk(), EventPipelineTransforms(emptyList()), EventProcessingConfiguration(workerCount = 1))
        val running = launch { consumer.run() }
        try {
            runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertTrue(observed.isCompleted)
            coVerify(exactly = 1) { queue.status() }
        } finally { running.cancelAndJoin() }
    }
}
