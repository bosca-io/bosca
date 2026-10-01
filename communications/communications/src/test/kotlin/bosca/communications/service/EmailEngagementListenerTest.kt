package bosca.communications.service

import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatus
import bosca.communications.model.DeliveryStatusType
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the engagement event → [DeliveryEvent] mapping: CLICKED with the url in metadata,
 * OPENED, the skip rules (no recipient, unparseable ids), and that a storage failure is
 * swallowed — engagement recording must never wedge the listener loop.
 */
class EmailEngagementListenerTest {

    private val tracking = RecordingDeliveryTracking()
    private val listener = EmailEngagementListener(NoopPubSub, tracking)
    private val messageId = UUID.random()
    private val recipientId = UUID.random()

    @AfterTest
    fun shutdown() {
        runBlocking { listener.shutdown() }
    }

    @Test
    fun `a click records CLICKED with the destination url in metadata`() {
        runBlocking {
            listener.record(
                messageId.toString(),
                recipientId.toString(),
                DeliveryStatusType.CLICKED,
                "https://example.com/go",
                "bml-click",
            )
        }
        val event = tracking.recorded.single()
        assertEquals("bml-click", event.providerEventId)
        assertEquals(messageId, event.messageId)
        assertEquals(recipientId, event.recipientId)
        assertEquals(DeliveryStatusType.CLICKED, event.status)
        assertEquals("click", event.providerEvent)
        val metadata = event.metadata?.jsonObject
        assertEquals("https://example.com/go", metadata?.get("url")?.jsonPrimitive?.content)
        assertEquals("bml-message-server", metadata?.get("source")?.jsonPrimitive?.content)
    }

    @Test
    fun `an open records OPENED without a url`() {
        runBlocking {
            listener.record(
                messageId.toString(),
                recipientId.toString(),
                DeliveryStatusType.OPENED,
                url = null,
                eventId = "bml-open",
            )
        }
        val event = tracking.recorded.single()
        assertEquals("bml-open", event.providerEventId)
        assertEquals(DeliveryStatusType.OPENED, event.status)
        assertEquals("open", event.providerEvent)
        assertEquals(null, event.metadata?.jsonObject?.get("url"))
    }

    @Test
    fun `events without a recipient are skipped — delivery events are per-recipient records`() {
        runBlocking {
            listener.record(
                messageId.toString(),
                null,
                DeliveryStatusType.CLICKED,
                "https://example.com",
                "bml-click",
            )
        }
        assertTrue(tracking.recorded.isEmpty())
    }

    @Test
    fun `unparseable ids are dropped, not thrown`() {
        runBlocking {
            listener.record("not-a-uuid", recipientId.toString(), DeliveryStatusType.CLICKED, null, "bml-click")
            listener.record(messageId.toString(), "not-a-uuid", DeliveryStatusType.OPENED, null, "bml-open")
        }
        assertTrue(tracking.recorded.isEmpty())
    }

    @Test
    fun `a storage failure is logged and swallowed so the listener keeps consuming`() {
        tracking.failNext = true
        runBlocking {
            listener.record(messageId.toString(), recipientId.toString(), DeliveryStatusType.OPENED, null, "bml-open-1")
            listener.record(messageId.toString(), recipientId.toString(), DeliveryStatusType.OPENED, null, "bml-open-2")
        }
        assertEquals(1, tracking.recorded.size)
    }

    private object NoopPubSub : PubSubService {
        override suspend fun <T> publish(channel: String, serializer: SerializationStrategy<T>, message: T) = Unit
        override fun <T> subscribe(channel: String, deserializer: DeserializationStrategy<T>): Flow<Message<T>> = emptyFlow()
    }

    private class RecordingDeliveryTracking : DeliveryTrackingService {
        val recorded = mutableListOf<DeliveryEvent>()
        var failNext = false

        override suspend fun recordEvent(event: DeliveryEvent) {
            if (failNext) {
                failNext = false
                error("storage unavailable")
            }
            recorded.add(event)
        }

        override suspend fun getStatus(messageId: UUID, recipientId: UUID): DeliveryStatus? = null
        override suspend fun getStatusesForMessage(messageId: UUID): List<DeliveryStatus> = emptyList()
        override suspend fun getStatuses(offset: Long, limit: Int): List<DeliveryStatus> = emptyList()
        override suspend fun countStatuses(): Long = 0
        override suspend fun getHistoryForRecipient(recipientId: UUID, offset: Long, limit: Int): List<DeliveryStatus> = emptyList()
        override suspend fun getEventsForRecipient(recipientId: UUID, offset: Long, limit: Int): List<DeliveryEvent> = emptyList()
        override suspend fun isSuppressed(email: String): Boolean = false
        override suspend fun suppress(email: String, reason: String, providerCode: String?) = Unit
    }
}
