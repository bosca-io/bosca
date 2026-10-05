package bosca.communications.service

import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatus
import bosca.communications.model.DeliveryStatusType
import bosca.communications.model.BmlMessageTemplateRender
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Tracks email and push delivery lifecycle events and provides status
 * queries. Events flow in from provider callbacks and internal state
 * transitions; the service maintains an aggregate [DeliveryStatus] per
 * message, recipient, and channel.
 */
interface DeliveryTrackingService : Service {

    /**
     * Record a delivery event and update the aggregate status.
     */
    suspend fun recordEvent(event: DeliveryEvent)

    /**
     * Record a delivery event together with the template render that produced the delivery.
     * Later provider events retain these details on the delivery aggregate.
     */
    suspend fun recordEvent(event: DeliveryEvent, bmlTemplate: BmlMessageTemplateRender) {
        recordEvent(event)
    }

    /**
     * Get the current delivery status for a specific message and recipient.
     */
    suspend fun getStatus(messageId: UUID, recipientId: UUID): DeliveryStatus?

    /**
     * List delivery statuses for a message across all recipients.
     */
    suspend fun getStatusesForMessage(messageId: UUID): List<DeliveryStatus>

    /**
     * List all aggregate delivery statuses, newest first.
     */
    suspend fun getStatuses(offset: Long = 0, limit: Int = 50): List<DeliveryStatus>

    /**
     * Count all aggregate delivery-status rows.
     */
    suspend fun countStatuses(): Long

    /**
     * List delivery history for a recipient, newest first.
     */
    suspend fun getHistoryForRecipient(recipientId: UUID, offset: Long = 0, limit: Int = 50): List<DeliveryStatus>

    /**
     * List raw delivery events for a recipient, newest first —
     * includes gate decisions (suppressions and deferrals).
     */
    suspend fun getEventsForRecipient(recipientId: UUID, offset: Long = 0, limit: Int = 50): List<DeliveryEvent>

    /**
     * Check whether an email address is on the suppression list.
     */
    suspend fun isSuppressed(email: String): Boolean

    /**
     * Add an email address to the suppression list (hard bounce).
     */
    suspend fun suppress(email: String, reason: String, providerCode: String? = null)
}
