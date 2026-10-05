package bosca.communications.service

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatus
import bosca.communications.model.DeliveryStatusType
import bosca.communications.model.BmlMessageTemplateRender
import bosca.communications.repository.DeliveryEventRepository
import bosca.communications.repository.DeliveryStatusRepository
import bosca.communications.repository.SuppressionListRepository
import bosca.db.connectionOrNull
import bosca.db.transaction
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class DeliveryTrackingServiceImpl(
    private val eventRepository: DeliveryEventRepository,
    private val statusRepository: DeliveryStatusRepository,
    private val suppressionRepository: SuppressionListRepository,
) : DeliveryTrackingService {

    override suspend fun recordEvent(event: DeliveryEvent) {
        recordEventInternal(event, null)
    }

    override suspend fun recordEvent(event: DeliveryEvent, bmlTemplate: BmlMessageTemplateRender) {
        recordEventInternal(event, bmlTemplate)
    }

    private suspend fun recordEventInternal(event: DeliveryEvent, bmlTemplate: BmlMessageTemplateRender?) {
        val connection = connectionOrNull()
        if (connection == null || connection.inTransaction) {
            recordEventInTransaction(event, bmlTemplate)
        } else {
            transaction { recordEventInTransaction(event, bmlTemplate) }
        }
    }

    private suspend fun recordEventInTransaction(event: DeliveryEvent, bmlTemplate: BmlMessageTemplateRender?) {
        val inserted = eventRepository.insert(
            providerEventId = event.providerEventId,
            messageId = event.messageId,
            recipientId = event.recipientId,
            channel = event.channel,
            status = event.status,
            providerEvent = event.providerEvent,
            errorCode = event.errorCode,
            errorMessage = event.errorMessage,
            metadata = event.metadata?.toString(),
            createdAt = event.createdAt,
        ) ?: return

        val current = statusRepository.getForUpdate(
            inserted.messageId,
            inserted.recipientId,
            inserted.channel,
        )
        if (current != null) {
            statusRepository.update(current.applyEvent(inserted, bmlTemplate))
            return
        }

        val initial = DeliveryStatus(
            messageId = inserted.messageId,
            recipientId = inserted.recipientId,
            channel = inserted.channel,
            createdAt = inserted.createdAt,
            updatedAt = inserted.createdAt,
        ).applyEvent(inserted, bmlTemplate)
        if (statusRepository.insert(initial) == 1) return

        val concurrentlyInserted = checkNotNull(
            statusRepository.getForUpdate(
                inserted.messageId,
                inserted.recipientId,
                inserted.channel,
            ),
        ) {
            "Delivery status disappeared after an insert conflict"
        }
        statusRepository.update(concurrentlyInserted.applyEvent(inserted, bmlTemplate))
    }

    override suspend fun getStatus(messageId: UUID, recipientId: UUID): DeliveryStatus? =
        statusRepository.get(messageId, recipientId)

    override suspend fun getStatusesForMessage(messageId: UUID): List<DeliveryStatus> =
        statusRepository.getByMessageId(messageId)

    override suspend fun getStatuses(offset: Long, limit: Int): List<DeliveryStatus> =
        statusRepository.getAll(offset, limit)

    override suspend fun countStatuses(): Long =
        statusRepository.count()

    override suspend fun getHistoryForRecipient(recipientId: UUID, offset: Long, limit: Int): List<DeliveryStatus> =
        statusRepository.getByRecipientId(recipientId, offset, limit)

    override suspend fun getEventsForRecipient(recipientId: UUID, offset: Long, limit: Int): List<DeliveryEvent> =
        eventRepository.getByRecipientId(recipientId, offset, limit)

    override suspend fun isSuppressed(email: String): Boolean =
        suppressionRepository.isSuppressed(email)

    override suspend fun suppress(email: String, reason: String, providerCode: String?) =
        suppressionRepository.add(email, reason, providerCode)

    private fun DeliveryStatus.applyEvent(
        event: DeliveryEvent,
        bmlTemplate: BmlMessageTemplateRender?,
    ): DeliveryStatus {
        val eventPriority = event.status.priority
        val currentPriority = status.priority
        val statusAdvanced = eventPriority >= currentPriority
        val replaceDetails = eventPriority > currentPriority ||
            event.status == status && !event.createdAt.isBefore(updatedAt)
        val attempt = event.providerEvent == null &&
            (event.status == DeliveryStatusType.SENT || event.status == DeliveryStatusType.FAILED)

        return copy(
            status = if (statusAdvanced) event.status else status,
            attempts = attempts + if (attempt) 1 else 0,
            lastAttemptAt = if (attempt) latest(lastAttemptAt, event.createdAt) else lastAttemptAt,
            deliveredAt = milestone(deliveredAt, event, DeliveryStatusType.DELIVERED),
            bouncedAt = milestone(bouncedAt, event, DeliveryStatusType.BOUNCED),
            openedAt = milestone(openedAt, event, DeliveryStatusType.OPENED),
            clickedAt = milestone(clickedAt, event, DeliveryStatusType.CLICKED),
            errorCode = if (replaceDetails) event.errorCode else errorCode,
            errorMessage = if (replaceDetails) event.errorMessage else errorMessage,
            bmlTemplate = bmlTemplate ?: this.bmlTemplate,
            createdAt = earliest(createdAt, event.createdAt),
            updatedAt = if (statusAdvanced) latest(updatedAt, event.createdAt) else updatedAt,
        )
    }

    private fun milestone(
        current: OffsetDateTime?,
        event: DeliveryEvent,
        status: DeliveryStatusType,
    ): OffsetDateTime? =
        if (event.status == status) earliest(current, event.createdAt) else current

    private fun earliest(current: OffsetDateTime?, candidate: OffsetDateTime): OffsetDateTime =
        if (current == null || candidate.isBefore(current)) candidate else current

    private fun latest(current: OffsetDateTime?, candidate: OffsetDateTime): OffsetDateTime =
        if (current == null || candidate.isAfter(current)) candidate else current

    private val DeliveryStatusType.priority: Int
        get() = when (this) {
            DeliveryStatusType.PENDING -> 0
            DeliveryStatusType.FAILED -> 1
            DeliveryStatusType.SENT -> 2
            DeliveryStatusType.DEFERRED -> 3
            DeliveryStatusType.DELIVERED -> 4
            DeliveryStatusType.OPENED -> 5
            DeliveryStatusType.CLICKED -> 6
            DeliveryStatusType.BOUNCED -> 7
            DeliveryStatusType.DROPPED -> 8
            DeliveryStatusType.SPAM_REPORT -> 9
            DeliveryStatusType.UNSUBSCRIBED -> 10
        }
}
