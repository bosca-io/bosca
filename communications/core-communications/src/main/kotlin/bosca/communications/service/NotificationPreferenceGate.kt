package bosca.communications.service

import bosca.communications.model.DeliveryChannel
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service

/**
 * The outcome of a delivery gate check for one recipient.
 */
sealed class GateDecision {

    /** Delivery may proceed now. */
    data object Allow : GateDecision()

    /**
     * Delivery must not happen. [reason] is a stable identifier
     * recorded on the delivery event (see [GateReasons]).
     */
    data class Suppressed(val reason: String) : GateDecision()

    /**
     * Delivery should be retried at [until] (quiet hours). Only
     * issued for the push channel.
     */
    data class Deferred(val until: OffsetDateTime) : GateDecision()
}

/**
 * Stable reason identifiers recorded on delivery events for gate
 * decisions, so admins can see why a message did not go out.
 */
object GateReasons {
    const val PREFERENCE = "suppressed_preference"
    const val SUPPRESSED_ADDRESS = "suppressed_address"
    const val QUIET_HOURS = "deferred_quiet_hours"
}

/**
 * The single enforcement choke point consulted before any
 * notification delivery. Composes the per-user preference matrix,
 * the email suppression list, and push quiet hours. All senders go
 * through [MessageService], which consults this gate per recipient —
 * do not call a sender directly.
 *
 * Enforcement is fail-open by design: unknown notification types,
 * missing timezones, and malformed stored values allow delivery
 * (logged) rather than silently suppressing it.
 */
interface NotificationPreferenceGate : Service {

    /**
     * Decide whether a message of notification type [type] (null
     * means transactional) may be delivered to [profileId] on
     * [channel] right now.
     *
     * [emailAddress] enables the suppression-list check on the
     * email channel. [highPriority] bypasses quiet hours on the
     * push channel; security-type notifications bypass quiet hours
     * unconditionally.
     */
    suspend fun evaluate(
        profileId: UUID,
        channel: DeliveryChannel,
        type: String?,
        emailAddress: String? = null,
        highPriority: Boolean = false,
    ): GateDecision
}
