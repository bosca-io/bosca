package bosca.ecommerce.service

import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.InventoryInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Inventory with the reservation state machine. Every transition takes a row lock
 * (`select … for update`) so concurrent reservations can't oversell. The reservation/commit/release
 * transitions are internal (driven by the cart engine); [addInventory] and [adjust] are
 * the admin surface.
 */
interface InventoryService : Service {

    /** An inventory row by id. */
    suspend fun get(id: UUID): Inventory?

    /** All inventory rows for a product across fulfillment centers. */
    suspend fun getByProduct(productId: UUID): List<Inventory>

    /** All inventory rows at a fulfillment center (the inventory-sync sweep reads these). */
    suspend fun getByCenter(fulfillmentCenterId: UUID): List<Inventory>

    /** Create an inventory row for a product at a fulfillment center. */
    suspend fun addInventory(input: InventoryInput, principalId: UUID?): Inventory

    /**
     * Admin override: set the on-hand quantity AND pin the row to [Inventory.manualQuantity] = true,
     * opting it out of connector sync. Row-locked; cannot drop below pending. Audited.
     */
    suspend fun adjust(id: UUID, quantity: Int, principalId: UUID?): Inventory

    /**
     * Connector-sync setter (distinct from [adjust]): set the on-hand quantity from an external feed
     * WITHOUT pinning the row manual, so it keeps syncing. Row-locked; a no-op (returns null) when the
     * row is manually pinned, the [quantity] is unchanged, or it would drop below pending. Returns the
     * updated row only when it actually changed (so the sweep can count real updates). Audited as
     * `quantity_synced`.
     */
    suspend fun syncQuantity(id: UUID, quantity: Int, principalId: UUID?): Inventory?

    /** Reserve [quantity] units (available -> inCart). Fails if insufficient available. */
    suspend fun reserve(inventoryId: UUID, quantity: Int): Inventory

    /** Release an in-cart hold (inCart -> available); clamps to what is held. */
    suspend fun releaseInCart(inventoryId: UUID, quantity: Int): Inventory

    /** Commit an in-cart hold to pending on payment (inCart -> pending). */
    suspend fun commitToPending(inventoryId: UUID, quantity: Int): Inventory

    /** Release a pending hold back to available (pending -> available); clamps to what is pending. */
    suspend fun releasePending(inventoryId: UUID, quantity: Int): Inventory

    /** Ship pending units (pending and quantity both decrement). Audited. */
    suspend fun ship(inventoryId: UUID, quantity: Int, principalId: UUID? = null): Inventory
}
