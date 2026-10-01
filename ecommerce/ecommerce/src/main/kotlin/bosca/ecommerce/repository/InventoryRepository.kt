package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Inventory
import bosca.serialization.UUID

/**
 * Persistence for `ecom.product_inventory`. No soft delete (inventory rows are managed, not
 * deleted). [getForUpdate] takes the row lock the reservation state machine relies on.
 */
@Repository
interface InventoryRepository {

    @Query("select * from ecom.product_inventory where id = :id")
    suspend fun get(id: UUID): Inventory?

    @Query("select * from ecom.product_inventory where id = :id for update")
    suspend fun getForUpdate(id: UUID): Inventory?

    @Query("select * from ecom.product_inventory where product_id = :productId order by created")
    suspend fun getByProduct(productId: UUID): List<Inventory>

    @Query("select * from ecom.product_inventory where fulfillment_center_id = :fulfillmentCenterId order by created")
    suspend fun getByCenter(fulfillmentCenterId: UUID): List<Inventory>

    @Query(
        """
        insert into ecom.product_inventory
            (product_id, fulfillment_center_id, sku, quantity, pending, in_cart, manual_quantity, expiration_seconds)
        values
            (:productId, :fulfillmentCenterId, :sku, :quantity, :pending, :inCart, :manualQuantity, :expirationSeconds)
        returning *
        """,
    )
    suspend fun add(inventory: Inventory): Inventory

    @Query(
        """
        update ecom.product_inventory
           set quantity = :quantity, pending = :pending, in_cart = :inCart,
               manual_quantity = :manualQuantity, modified = now()
         where id = :id
        returning *
        """,
    )
    suspend fun update(inventory: Inventory): Inventory?
}
