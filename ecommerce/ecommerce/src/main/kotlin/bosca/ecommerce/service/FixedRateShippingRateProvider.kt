package bosca.ecommerce.service

import bosca.ecommerce.model.Address
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.ecommerce.model.Parcel
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentLabel
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.ShippingRate
import bosca.ecommerce.model.TrackingUpdate
import bosca.serialization.OffsetDateTime

/**
 * A test/default shipping provider that quotes one flat rate. The amount comes from the config row's
 * `KeyValueProviderConfiguration["amount"]` (a Money string) or defaults to 5.00. Real carrier
 * integrations implement [ShippingRateProvider] with a different [key] and are a follow-up spec.
 */
class FixedRateShippingRateProvider : ShippingRateProvider {

    override val key: String = "fixed-rate"

    override suspend fun rates(provider: ShippingProvider, cart: Cart, origin: Address?, destination: Address?, parcels: List<Parcel>): List<ShippingRate> {
        val config = provider.configuration as? KeyValueProviderConfiguration
        val configured = if (config != null) config.values["amount"] else null
        val amount = if (configured != null) Money.of(configured) else Money.of("5.00")
        return listOf(
            ShippingRate(
                carrier = provider.name,
                serviceLevel = "Standard",
                durationTerms = null,
                token = "fixed:${provider.id}",
                amount = amount,
            ),
        )
    }

    /** Assigns the configured carrier name; a flat-rate provider produces no real tracking or label. */
    override suspend fun purchaseLabel(provider: ShippingProvider, shipment: Shipment, origin: Address?, destination: Address?, parcels: List<Parcel>): ShipmentLabel =
        ShipmentLabel(carrier = provider.name)

    /**
     * No real carrier behind this provider, so it *simulates* tracking by advancing the shipment one
     * step along the lifecycle each time it's polled (SHIPPED → IN_TRANSIT → OUT_FOR_DELIVERY →
     * DELIVERED), letting the tracking flow be exercised end-to-end without a carrier integration.
     */
    override suspend fun track(provider: ShippingProvider, shipment: Shipment): TrackingUpdate? {
        val next = when (shipment.status) {
            ShipmentStatus.SHIPPED -> ShipmentStatus.IN_TRANSIT
            ShipmentStatus.IN_TRANSIT -> ShipmentStatus.OUT_FOR_DELIVERY
            ShipmentStatus.OUT_FOR_DELIVERY -> ShipmentStatus.DELIVERED
            else -> return null // not in flight (or already delivered/terminal)
        }
        return TrackingUpdate(
            status = next,
            carrierStatus = "${provider.name}: ${next.name.lowercase().replace('_', ' ')}",
            delivered = if (next == ShipmentStatus.DELIVERED) OffsetDateTime.now() else null,
        )
    }
}
