package bosca.ecommerce.service

import bosca.ecommerce.model.Address
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.Parcel
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentLabel
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.ShippingRate
import bosca.ecommerce.model.TrackingUpdate

/**
 * Returns shipping rate options for a cart and, when a packed box ships, the carrier label for it. The
 * behavior behind a `ShippingProvider` config row, selected by [key] == the row's `providerKey` (DI
 * lookup, no `Class.forName`). [provider] carries the row's settings (jsonb `configuration`).
 *
 * Providers are thin (no impl-service access): the caller resolves the shipment context a real carrier
 * needs — the [origin] (fulfillment-center) and [destination] (cart) addresses and the [parcels]
 * (box dimensions + weight) — and passes it in. The fixed-rate test provider ignores them; carrier
 * integrations (Shippo, EasyPost) use them.
 */
interface ShippingRateProvider {
    /** DI key matched against a `ShippingProvider.providerKey`. */
    val key: String

    suspend fun rates(provider: ShippingProvider, cart: Cart, origin: Address?, destination: Address?, parcels: List<Parcel>): List<ShippingRate>

    /**
     * Assign a carrier to a packed box as it ships, returning the [ShipmentLabel] (carrier + tracking +
     * label URL). The default returns `null` — a provider with no real labeling — so the fulfillment
     * flow falls back to an operator-supplied / unlabeled shipment. Real carrier providers override it,
     * using the resolved [origin]/[destination] addresses and [parcels].
     */
    suspend fun purchaseLabel(provider: ShippingProvider, shipment: Shipment, origin: Address?, destination: Address?, parcels: List<Parcel>): ShipmentLabel? = null

    /**
     * Fetch the carrier's latest tracking reading for a shipped [shipment] (by its tracking number),
     * mapped onto the canonical lifecycle. Returns `null` when there's no update (or the provider does
     * no tracking — the default). A returned [TrackingUpdate] only advances the shipment forward.
     */
    suspend fun track(provider: ShippingProvider, shipment: Shipment): TrackingUpdate? = null
}
