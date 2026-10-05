package bosca.ecommerce.model

import kotlinx.serialization.Serializable

/**
 * The physical dimensions and weight of a box being rated or shipped, in the seller's configured
 * units ([LengthUnit]/[WeightUnit] per company). Carrier shipping providers
 * ([bosca.ecommerce.service.ShippingRateProvider]) need this to quote rates and buy labels; the
 * caller resolves it (from the packed [Container] for a shipment, or estimated from product
 * dimensions for a cart quote) so providers stay thin.
 */
@Serializable
data class Parcel(
    val length: Double,
    val width: Double,
    val height: Double,
    val weight: Double,
    val lengthUnit: LengthUnit = LengthUnit.INCHES,
    val weightUnit: WeightUnit = WeightUnit.POUNDS,
)
