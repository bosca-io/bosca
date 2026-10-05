@file:OptIn(
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.communications.jobs

import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatusType
import bosca.communications.service.DeliveryTrackingService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class RecordDeliveryEventsJobTest {

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `executor persists the accepted delivery batch`() = runBlocking {
        val tracking = mockk<DeliveryTrackingService>()
        val queue = mockk<JobQueue>(relaxed = true)
        val events = List(2) {
            DeliveryEvent(
                providerEventId = "bosca-${UUID.random()}",
                messageId = UUID.random(),
                recipientId = UUID.random(),
                status = DeliveryStatusType.SENT,
            )
        }
        events.forEach { coEvery { tracking.recordEvent(it) } returns Unit }
        val definition = RecordDeliveryEventsJob(events)
        val job = InternalJobConstructor(
            definition = Json.encodeToJsonElement(RecordDeliveryEventsJob.serializer(), definition),
            executor = RecordDeliveryEventsJobExecutor::class,
        )

        withContext(queue.asCoroutineContext(job)) {
            RecordDeliveryEventsJobExecutor(tracking).execute()
        }

        events.forEach { coVerify(exactly = 1) { tracking.recordEvent(it) } }
    }
}
