package bosca.workops.jobs

import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.NotificationEvent
import bosca.workops.model.notification.NotificationOutboxDeliveryJob
import bosca.workops.service.MetadataUpdated
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DurableJobSerializationTest {

    private val sparseJson = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }
    private val explicitJson = Json(sparseJson) { encodeDefaults = true }

    @Test
    fun `index and context jobs preserve sparse defaults and authored values`() {
        val taskId = UUID.random()
        val projectId = UUID.random()
        assertBoth(TaskIndexJob(), TaskIndexJob.serializer())
        assertBoth(TaskIndexJob(taskId, projectId, deleteOnly = true), TaskIndexJob.serializer())

        val specId = UUID.random()
        assertBoth(SpecIndexJob(), SpecIndexJob.serializer())
        assertBoth(SpecIndexJob(specId, deleteOnly = true), SpecIndexJob.serializer())
        assertBoth(SpecContextSyncJob(), SpecContextSyncJob.serializer())
        assertBoth(SpecContextSyncJob(specId), SpecContextSyncJob.serializer())
    }

    @Test
    fun `metadata and notification envelopes round trip required and default fields`() {
        val metadataId = UUID.random()
        assertBoth(MetadataUpdated(metadataId), MetadataUpdated.serializer())
        assertBoth(MetadataUpdated(metadataId, 9), MetadataUpdated.serializer())

        val outboxId = UUID.random()
        assertBoth(NotificationOutboxDeliveryJob(outboxId), NotificationOutboxDeliveryJob.serializer())
        val delivery = NotificationDelivery(
            event = NotificationEvent.TASK_DUE,
            taskId = UUID.random(),
            projectId = UUID.random(),
        )
        val requested = NotificationDeliveryRequested(delivery)
        assertBoth(requested, NotificationDeliveryRequested.serializer())
        assertEquals(delivery.id, requested.identityKey())
    }

    @Test
    fun `durable job wire formats reject absent required identifiers`() {
        assertFailsWith<SerializationException> {
            sparseJson.decodeFromString(WaitForHealthyJob.serializer(), "{}")
        }
        assertFailsWith<SerializationException> {
            sparseJson.decodeFromString(NotificationOutboxDeliveryJob.serializer(), "{}")
        }
        assertFailsWith<SerializationException> {
            sparseJson.decodeFromString(NotificationDeliveryRequested.serializer(), "{}")
        }
    }

    private fun <T> assertBoth(value: T, serializer: KSerializer<T>) {
        assertEquals(value, sparseJson.decodeFromString(serializer, sparseJson.encodeToString(serializer, value)))
        assertEquals(value, explicitJson.decodeFromString(serializer, explicitJson.encodeToString(serializer, value)))
    }
}
