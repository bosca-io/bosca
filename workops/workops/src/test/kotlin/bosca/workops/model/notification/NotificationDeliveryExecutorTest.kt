@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.model.notification

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.workops.service.NotificationDeliveryService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

class NotificationDeliveryExecutorTest {

    private val queue = mockk<JobQueue>(relaxed = true)
    private val deliveryService = mockk<NotificationDeliveryService>()
    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
        }
    }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `executor unwraps the serializable delivery command`() = runTest {
        val delivery = NotificationDelivery(
            event = NotificationEvent.TASK_UPDATED,
            taskId = UUID.random(),
            projectId = UUID.random(),
        )
        coEvery { deliveryService.deliver(delivery) } just Runs
        val definition = NotificationDeliveryJob(delivery)
        val job = Job(definition, NotificationDeliveryExecutor::class)

        withContext(queue.asCoroutineContext(job)) {
            NotificationDeliveryExecutor(deliveryService).execute()
        }

        coVerify(exactly = 1) { deliveryService.deliver(delivery) }
    }

    @Test
    fun `job round trips its delivery command`() {
        val job = NotificationDeliveryJob(
            NotificationDelivery(
                event = NotificationEvent.TASK_COMMENTED,
                taskId = UUID.random(),
                projectId = UUID.random(),
                actorProfileId = UUID.random(),
                mentionedProfileIds = setOf(UUID.random()),
                commentId = 42,
            ),
        )

        val encoded = json.encodeToString(NotificationDeliveryJob.serializer(), job)

        assertEquals(job, json.decodeFromString(NotificationDeliveryJob.serializer(), encoded))
    }
}
