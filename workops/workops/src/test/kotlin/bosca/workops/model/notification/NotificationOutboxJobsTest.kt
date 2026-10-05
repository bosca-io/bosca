@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.model.notification

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.workops.service.NotificationChannelDeliveryService
import bosca.workops.service.NotificationOutboxService
import bosca.workops.service.TaskService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class NotificationOutboxJobsTest {

    private val queue = mockk<JobQueue>(relaxed = true)
    private val outboxService = mockk<NotificationOutboxService>()
    private val deliveryService = mockk<NotificationChannelDeliveryService>()
    private val taskService = mockk<TaskService>()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `successful handoff acknowledges the durable row`() = runTest {
        val entry = entry()
        coEvery { outboxService.getPending(entry.id) } returns entry
        coEvery { deliveryService.deliver(entry) } just Runs
        coEvery { outboxService.markSent(entry.id) } just Runs

        execute(entry.id)

        coVerify(exactly = 1) { deliveryService.deliver(entry) }
        coVerify(exactly = 1) { outboxService.markSent(entry.id) }
        coVerify(exactly = 0) { outboxService.markFailure(any(), any()) }
    }

    @Test
    fun `failed handoff records the attempt and remains retryable`() = runTest {
        val entry = entry()
        val failure = IllegalStateException("pipeline handoff failed")
        coEvery { outboxService.getPending(entry.id) } returns entry
        coEvery { deliveryService.deliver(entry) } throws failure
        coEvery { outboxService.markFailure(entry.id, failure.message ?: "") } just Runs

        val thrown = assertFailsWith<IllegalStateException> { execute(entry.id) }

        kotlin.test.assertEquals("pipeline handoff failed", thrown.message)
        coVerify(exactly = 1) { outboxService.markFailure(entry.id, "pipeline handoff failed") }
        coVerify(exactly = 0) { outboxService.markSent(any()) }
    }

    @Test
    fun `failed handoff derives a fallback message even when failure recording fails`() = runTest {
        val entry = entry()
        val failure = IllegalStateException()
        val recordFailure = IllegalArgumentException("outbox unavailable")
        coEvery { outboxService.getPending(entry.id) } returns entry
        coEvery { deliveryService.deliver(entry) } throws failure
        coEvery { outboxService.markFailure(entry.id, any()) } coAnswers { throw recordFailure }

        assertFailsWith<IllegalStateException> { execute(entry.id) }

        coVerify(exactly = 1) { outboxService.markFailure(entry.id, "IllegalStateException") }
    }

    @Test
    fun `an already acknowledged row is a no-op`() = runTest {
        val id = UUID.random()
        coEvery { outboxService.getPending(id) } returns null

        execute(id)

        coVerify(exactly = 0) { deliveryService.deliver(any()) }
        coVerify(exactly = 0) { outboxService.markSent(any()) }
    }

    @Test
    fun `maintenance attempts every action and propagates the first failure`() = runTest {
        coEvery { outboxService.recoverPending() } throws IllegalStateException("recovery failed")
        coEvery { outboxService.deliverDueDigests() } throws IllegalArgumentException("digest failed")
        coEvery { taskService.dispatchDueNotifications() } returns 2

        val failure = assertFailsWith<IllegalStateException> { executeMaintenance() }

        kotlin.test.assertEquals("recovery failed", failure.message)
        coVerify(exactly = 1) { outboxService.recoverPending() }
        coVerify(exactly = 1) { outboxService.deliverDueDigests() }
        coVerify(exactly = 1) { taskService.dispatchDueNotifications() }
    }

    @Test
    fun `maintenance propagates a digest failure after recovery succeeds`() = runTest {
        coEvery { outboxService.recoverPending() } returns 0
        coEvery { outboxService.deliverDueDigests() } throws IllegalArgumentException("digest failed")
        coEvery { taskService.dispatchDueNotifications() } returns 0

        val failure = assertFailsWith<IllegalArgumentException> { executeMaintenance() }

        kotlin.test.assertEquals("digest failed", failure.message)
        coVerify(exactly = 1) { taskService.dispatchDueNotifications() }
    }

    @Test
    fun `maintenance completes when every action succeeds`() = runTest {
        coEvery { outboxService.recoverPending() } returns 0
        coEvery { outboxService.deliverDueDigests() } returns 0
        coEvery { taskService.dispatchDueNotifications() } returns 0

        executeMaintenance()

        coVerify(exactly = 1) { outboxService.recoverPending() }
        coVerify(exactly = 1) { outboxService.deliverDueDigests() }
        coVerify(exactly = 1) { taskService.dispatchDueNotifications() }
    }

    @Test
    fun `maintenance preserves cancellation`() = runTest {
        coEvery { outboxService.recoverPending() } throws kotlin.coroutines.cancellation.CancellationException()

        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { executeMaintenance() }

        coVerify(exactly = 0) { outboxService.deliverDueDigests() }
        coVerify(exactly = 0) { taskService.dispatchDueNotifications() }
    }

    private suspend fun execute(id: UUID) {
        val definition = NotificationOutboxDeliveryJob(id)
        val job = Job(definition, NotificationOutboxDeliveryExecutor::class)
        withContext(queue.asCoroutineContext(job)) {
            NotificationOutboxDeliveryExecutor(outboxService, deliveryService).execute()
        }
    }

    private suspend fun executeMaintenance() {
        val job = Job(NotificationMaintenanceJob, NotificationMaintenanceExecutor::class)
        withContext(queue.asCoroutineContext(job)) {
            NotificationMaintenanceExecutor(outboxService, taskService).execute()
        }
    }

    private fun entry() = NotificationOutboxEntry(
        id = UUID.random(),
        sourceId = UUID.random(),
        event = "TASK_UPDATED",
        channel = NotificationChannel.EMAIL,
        target = UUID.random().toString(),
        payload = JsonObject(emptyMap()),
    )
}
