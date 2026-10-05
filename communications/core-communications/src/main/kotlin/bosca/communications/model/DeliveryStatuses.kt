package bosca.communications.model

import kotlinx.serialization.Serializable

/**
 * A recipient's aggregate delivery status with display identity for administration.
 *
 * Recipient identity is resolved through the profile service rather than joined
 * directly from communications persistence, preserving the profile aggregate boundary.
 */
@Serializable
data class RecipientDeliveryStatus(
    val delivery: DeliveryStatus,
    val recipientName: String?,
    val recipientEmail: String?,
)

/**
 * Recent delivery statuses and the total number of aggregate statuses.
 */
@Serializable
data class DeliveryStatuses(
    val statuses: List<RecipientDeliveryStatus>,
    val total: Long,
)
