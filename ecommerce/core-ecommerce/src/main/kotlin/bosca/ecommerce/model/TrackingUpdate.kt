package bosca.ecommerce.model

import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A carrier tracking reading for a shipment, returned by `ShippingRateProvider.track`. [status] is the
 * carrier state mapped onto the canonical [ShipmentStatus] lifecycle; [carrierStatus] is the carrier's
 * own raw wording (kept for fidelity); [delivered] is set when the carrier reports delivery. A tracking
 * update only ever advances the shipment's status forward (by [ShipmentStatus.lifecycleRank]).
 */
@Serializable
data class TrackingUpdate(
    val status: ShipmentStatus,
    val carrierStatus: String? = null,
    @Contextual
    val delivered: OffsetDateTime? = null,
)
