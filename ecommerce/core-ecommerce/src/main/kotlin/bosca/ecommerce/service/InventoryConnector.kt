package bosca.ecommerce.service

import bosca.ecommerce.model.FulfillmentCenter

/**
 * Reports on-hand stock for a fulfillment center from an external system of record (a WMS / ERP / 3PL
 * feed). The SPI behind a center's [FulfillmentCenter.connectorKey], resolved by DI name (no
 * `Class.forName`) — the scheduled inventory-sync sweep ([FulfillmentService.syncInventory]) looks one
 * up per syncable center and pulls quantities for its inventory rows.
 *
 * Mirrors the legacy `fulfillment.FulfillmentConnector.getQuantity(product)` contract. Connectors are
 * intentionally read-only and external: the reserved key `"manual"` means *no* sync (the quantity is
 * operator-managed), so a center on `"manual"` is never handed to a connector. Bosca ships none by
 * default — real connectors are deployment-specific — so a center pointed at an unregistered key is
 * skipped (logged), not failed.
 */
interface InventoryConnector {

    /** DI key identifying this connector (matches a center's [FulfillmentCenter.connectorKey]). */
    val key: String

    /**
     * The authoritative on-hand quantity for [sku] at [center], or `null` when the connector has no
     * reading for that sku (the inventory row is then left unchanged rather than zeroed).
     */
    suspend fun getQuantity(center: FulfillmentCenter, sku: String): Int?
}
