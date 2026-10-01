package bosca.sharedqueue.jobs.enqueue

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultJobEnqueueEventChannelTest {

    @Test
    fun emitted_events_are_received_by_collector() = runTest(UnconfinedTestDispatcher()) {
        val channel = DefaultJobEnqueueEventChannel()
        val received = mutableListOf<JobEnqueueEvent>()

        val collector = launch {
            channel.events().collect { received.add(it) }
        }

        val event = JobEnqueueEvent(
            jobId = UUID.random(),
            executor = "com.example.MyExecutor",
            executorName = "my-executor",
            queue = "test-queue",
            enqueuedAt = OffsetDateTime.now(),
            delayed = false
        )

        channel.emit(event)

        assertEquals(1, received.size)
        assertEquals(event, received[0])

        collector.cancel()
    }

    @Test
    fun multiple_events_are_received_in_order() = runTest(UnconfinedTestDispatcher()) {
        val channel = DefaultJobEnqueueEventChannel()
        val received = mutableListOf<JobEnqueueEvent>()

        val collector = launch {
            channel.events().collect { received.add(it) }
        }

        val events = (1..5).map {
            JobEnqueueEvent(
                jobId = UUID.random(),
                executor = "executor-$it",
                queue = "queue-$it"
            )
        }

        events.forEach { channel.emit(it) }

        assertEquals(5, received.size)
        events.forEachIndexed { i, expected ->
            assertEquals(expected.executor, received[i].executor)
            assertEquals(expected.queue, received[i].queue)
        }

        collector.cancel()
    }

    @Test
    fun multiple_collectors_receive_same_events() = runTest(UnconfinedTestDispatcher()) {
        val channel = DefaultJobEnqueueEventChannel()
        val received1 = mutableListOf<JobEnqueueEvent>()
        val received2 = mutableListOf<JobEnqueueEvent>()

        val collector1 = launch { channel.events().collect { received1.add(it) } }
        val collector2 = launch { channel.events().collect { received2.add(it) } }

        val event = JobEnqueueEvent(
            jobId = UUID.random(),
            executor = "executor",
            queue = "queue"
        )

        channel.emit(event)

        assertEquals(1, received1.size)
        assertEquals(1, received2.size)
        assertEquals(event, received1[0])
        assertEquals(event, received2[0])

        collector1.cancel()
        collector2.cancel()
    }
}
