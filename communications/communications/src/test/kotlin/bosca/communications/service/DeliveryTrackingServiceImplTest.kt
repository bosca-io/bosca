package bosca.communications.service

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatus
import bosca.communications.model.DeliveryStatusType
import bosca.communications.model.BmlMessageTemplateRender
import bosca.communications.repository.DeliveryEventRepository
import bosca.communications.repository.DeliveryStatusRepository
import bosca.communications.repository.SuppressionListRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeliveryTrackingServiceImplTest {

    private val events = mockk<DeliveryEventRepository>()
    private val statuses = mockk<DeliveryStatusRepository>()
    private val suppressions = mockk<SuppressionListRepository>()
    private val service = DeliveryTrackingServiceImpl(events, statuses, suppressions)

    @BeforeTest
    fun setup() {
        io.mockk.clearMocks(events, statuses, suppressions)
    }

    @Test
    fun `provider retry does not update the aggregate twice`() = runBlocking {
        coEvery {
            events.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns null

        service.recordEvent(deliveryEvent(providerEventId = "provider-event"))

        coVerify(exactly = 0) { statuses.getForUpdate(any(), any(), any()) }
        coVerify(exactly = 0) { statuses.insert(any()) }
        coVerify(exactly = 0) { statuses.update(any()) }
    }

    @Test
    fun `new internal send inserts an aggregate and records its attempt`() = runBlocking {
        val event = deliveryEvent(status = DeliveryStatusType.SENT)
        coEvery {
            events.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns event
        coEvery {
            statuses.getForUpdate(event.messageId, event.recipientId, DeliveryChannel.EMAIL)
        } returns null
        coEvery { statuses.insert(any()) } returns 1

        service.recordEvent(event)

        coVerify(exactly = 1) {
            statuses.insert(
                DeliveryStatus(
                    messageId = event.messageId,
                    recipientId = event.recipientId,
                    status = DeliveryStatusType.SENT,
                    attempts = 1,
                    lastAttemptAt = event.createdAt,
                    createdAt = event.createdAt,
                    updatedAt = event.createdAt,
                ),
            )
        }
        coVerify(exactly = 0) { statuses.update(any()) }
    }

    @Test
    fun `template render is stored on the aggregate and retained by later events`() = runBlocking {
        val template = BmlMessageTemplateRender(
            project = "bosca-messages",
            templateKey = "welcome",
            version = "20260804-v1",
            parameters = buildJsonObject { put("name", "Ada") },
        )
        val pending = deliveryEvent(status = DeliveryStatusType.PENDING)
        coEvery {
            events.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns pending
        coEvery {
            statuses.getForUpdate(pending.messageId, pending.recipientId, pending.channel)
        } returns null
        coEvery { statuses.insert(any()) } returns 1

        service.recordEvent(pending, template)

        coVerify {
            statuses.insert(
                DeliveryStatus(
                    messageId = pending.messageId,
                    recipientId = pending.recipientId,
                    bmlTemplate = template,
                    createdAt = pending.createdAt,
                    updatedAt = pending.createdAt,
                ),
            )
        }

        io.mockk.clearMocks(events, statuses, suppressions)
        val sent = pending.copy(status = DeliveryStatusType.SENT)
        val current = DeliveryStatus(
            messageId = pending.messageId,
            recipientId = pending.recipientId,
            bmlTemplate = template,
            createdAt = pending.createdAt,
            updatedAt = pending.createdAt,
        )
        coEvery {
            events.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns sent
        coEvery {
            statuses.getForUpdate(sent.messageId, sent.recipientId, sent.channel)
        } returns current
        coEvery { statuses.update(any()) } returns Unit

        service.recordEvent(sent)

        coVerify {
            statuses.update(
                current.copy(
                    status = DeliveryStatusType.SENT,
                    attempts = 1,
                    lastAttemptAt = sent.createdAt,
                ),
            )
        }
    }

    @Test
    fun `provider event advances an existing aggregate without recording another attempt`() = runBlocking {
        val sentAt = OffsetDateTime.parse("2026-07-29T12:00:00.900Z")
        val deliveredAt = OffsetDateTime.parse("2026-07-29T12:00:00Z")
        val event = deliveryEvent(
            providerEventId = "provider-event",
            status = DeliveryStatusType.DELIVERED,
            providerEvent = "delivered",
            createdAt = deliveredAt,
        )
        val current = DeliveryStatus(
            messageId = event.messageId,
            recipientId = event.recipientId,
            status = DeliveryStatusType.SENT,
            attempts = 1,
            lastAttemptAt = sentAt,
            createdAt = sentAt,
            updatedAt = sentAt,
        )
        coEvery {
            events.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns event
        coEvery {
            statuses.getForUpdate(event.messageId, event.recipientId, DeliveryChannel.EMAIL)
        } returns current
        coEvery { statuses.update(any()) } returns Unit

        service.recordEvent(event)

        coVerify(exactly = 1) {
            statuses.update(
                current.copy(
                    status = DeliveryStatusType.DELIVERED,
                    deliveredAt = deliveredAt,
                    createdAt = deliveredAt,
                ),
            )
        }
        coVerify(exactly = 0) { statuses.insert(any()) }
    }

    @Test
    fun `a later lower-priority event cannot regress delivery state`() = runBlocking {
        val deliveredAt = OffsetDateTime.parse("2026-07-29T12:00:00Z")
        val acceptedAt = OffsetDateTime.parse("2026-07-29T12:00:02Z")
        val event = deliveryEvent(
            status = DeliveryStatusType.SENT,
            createdAt = acceptedAt,
        )
        val current = DeliveryStatus(
            messageId = event.messageId,
            recipientId = event.recipientId,
            status = DeliveryStatusType.DELIVERED,
            deliveredAt = deliveredAt,
            createdAt = deliveredAt,
            updatedAt = deliveredAt,
        )
        coEvery {
            events.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns event
        coEvery {
            statuses.getForUpdate(event.messageId, event.recipientId, DeliveryChannel.EMAIL)
        } returns current
        coEvery { statuses.update(any()) } returns Unit

        service.recordEvent(event)

        coVerify(exactly = 1) {
            statuses.update(
                current.copy(
                    attempts = 1,
                    lastAttemptAt = acceptedAt,
                ),
            )
        }
    }

    @Test
    fun `an insert race locks the winning aggregate and applies the event`() = runBlocking {
        val event = deliveryEvent(
            providerEventId = "provider-event",
            status = DeliveryStatusType.OPENED,
            providerEvent = "open",
        )
        val winner = DeliveryStatus(
            messageId = event.messageId,
            recipientId = event.recipientId,
            status = DeliveryStatusType.DELIVERED,
            deliveredAt = event.createdAt.minusMinutes(1),
            createdAt = event.createdAt.minusMinutes(1),
            updatedAt = event.createdAt.minusMinutes(1),
        )
        coEvery {
            events.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns event
        coEvery {
            statuses.getForUpdate(event.messageId, event.recipientId, DeliveryChannel.EMAIL)
        } returnsMany listOf(null, winner)
        coEvery { statuses.insert(any()) } returns 0
        coEvery { statuses.update(any()) } returns Unit

        service.recordEvent(event)

        coVerify(exactly = 1) {
            statuses.update(
                winner.copy(
                    status = DeliveryStatusType.OPENED,
                    openedAt = event.createdAt,
                    updatedAt = event.createdAt,
                ),
            )
        }
    }

    @Test
    fun `an insert conflict without a winning aggregate fails loudly`() = runBlocking {
        val event = deliveryEvent()
        coEvery {
            events.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns event
        coEvery {
            statuses.getForUpdate(event.messageId, event.recipientId, event.channel)
        } returns null
        coEvery { statuses.insert(any()) } returns 0

        val error = assertFailsWith<IllegalStateException> {
            service.recordEvent(event)
        }

        assertEquals("Delivery status disappeared after an insert conflict", error.message)
        coVerify(exactly = 0) { statuses.update(any()) }
    }

    @Test
    fun `every delivery state can initialize an aggregate`() = runBlocking {
        for (status in DeliveryStatusType.entries) {
            val event = deliveryEvent(
                providerEventId = "provider-$status",
                status = status,
                providerEvent = status.name.lowercase(),
                withMetadata = status == DeliveryStatusType.PENDING,
            )
            coEvery {
                events.insert(
                    event.providerEventId,
                    event.messageId,
                    event.recipientId,
                    event.channel,
                    event.status,
                    event.providerEvent,
                    event.errorCode,
                    event.errorMessage,
                    event.metadata?.toString(),
                    event.createdAt,
                )
            } returns event
            coEvery {
                statuses.getForUpdate(event.messageId, event.recipientId, event.channel)
            } returns null
            coEvery { statuses.insert(match { it.messageId == event.messageId }) } returns 1

            service.recordEvent(event)
        }

        coVerify(exactly = DeliveryStatusType.entries.size) { statuses.insert(any()) }
    }

    @Test
    fun `recent list and count delegate to the repository`() = runBlocking {
        val rows = listOf(
            DeliveryStatus(messageId = UUID.random(), recipientId = UUID.random()),
        )
        coEvery { statuses.getAll(25, 25) } returns rows
        coEvery { statuses.count() } returns 51

        assertEquals(rows, service.getStatuses(25, 25))
        assertEquals(51, service.countStatuses())
    }

    @Test
    fun `status history events and suppression operations delegate to their repositories`() = runBlocking {
        val messageId = UUID.random()
        val recipientId = UUID.random()
        val status = DeliveryStatus(messageId = messageId, recipientId = recipientId)
        val event = deliveryEvent()
        coEvery { statuses.get(messageId, recipientId, DeliveryChannel.EMAIL) } returns status
        coEvery { statuses.getByMessageId(messageId) } returns listOf(status)
        coEvery { statuses.getByRecipientId(recipientId, 10, 20) } returns listOf(status)
        coEvery { events.getByRecipientId(recipientId, 5, 15) } returns listOf(event)
        coEvery { suppressions.isSuppressed("blocked@example.com") } returns true
        coEvery {
            suppressions.add("blocked@example.com", "hard bounce", "550")
        } returns Unit

        assertEquals(status, service.getStatus(messageId, recipientId))
        assertEquals(listOf(status), service.getStatusesForMessage(messageId))
        assertEquals(listOf(status), service.getHistoryForRecipient(recipientId, 10, 20))
        assertEquals(listOf(event), service.getEventsForRecipient(recipientId, 5, 15))
        assertEquals(true, service.isSuppressed("blocked@example.com"))
        service.suppress("blocked@example.com", "hard bounce", "550")

        coVerify(exactly = 1) {
            suppressions.add("blocked@example.com", "hard bounce", "550")
        }
    }

    @Test
    fun `older duplicate milestone keeps its earliest timestamp`() = runBlocking {
        val firstOpen = OffsetDateTime.parse("2026-07-29T12:00:02Z")
        val earlierOpen = OffsetDateTime.parse("2026-07-29T12:00:01Z")
        val event = deliveryEvent(
            providerEventId = "earlier-open",
            status = DeliveryStatusType.OPENED,
            providerEvent = "open",
            createdAt = earlierOpen,
        )
        val current = DeliveryStatus(
            messageId = event.messageId,
            recipientId = event.recipientId,
            status = DeliveryStatusType.OPENED,
            openedAt = firstOpen,
            createdAt = firstOpen,
            updatedAt = firstOpen,
            errorCode = "newer",
        )
        coEvery {
            events.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns event
        coEvery {
            statuses.getForUpdate(event.messageId, event.recipientId, event.channel)
        } returns current
        coEvery { statuses.update(any()) } returns Unit

        service.recordEvent(event)

        coVerify {
            statuses.update(
                current.copy(
                    openedAt = earlierOpen,
                    createdAt = earlierOpen,
                ),
            )
        }
    }

    private fun deliveryEvent(
        providerEventId: String? = null,
        status: DeliveryStatusType = DeliveryStatusType.SENT,
        providerEvent: String? = null,
        createdAt: OffsetDateTime = OffsetDateTime.parse("2026-07-29T12:00:00Z"),
        withMetadata: Boolean = false,
    ) = DeliveryEvent(
        providerEventId = providerEventId,
        messageId = UUID.random(),
        recipientId = UUID.random(),
        status = status,
        providerEvent = providerEvent,
        metadata = if (withMetadata) buildJsonObject { put("source", "provider") } else null,
        createdAt = createdAt,
    )
}
