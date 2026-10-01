package bosca.ecommerce.model

import kotlinx.serialization.Serializable

/**
 * The carrier assignment a shipping provider produces when a packed box ships: the
 * chosen [carrier], a [tracking] number, and a [labelUrl] to the printable label. A flat-rate/test
 * provider assigns only a carrier name; real carrier integrations fill in tracking + label.
 */
@Serializable
data class ShipmentLabel(
    val carrier: String? = null,
    val tracking: String? = null,
    val labelUrl: String? = null,
)
