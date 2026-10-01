package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Cart
import bosca.serialization.UUID

/**
 * Persistence for `ecom.carts`. The whole cart is one row; [getForUpdate] row-locks it for atomic
 * item mutations. The `items` jsonb column round-trips `List<CartItem>` via `JsonbMapper` (declared
 * on the model property) — no `::jsonb` cast needed, the bound value is a jsonb PGobject.
 */
@Repository
interface CartRepository {

    @Query("select * from ecom.carts where id = :id and deleted is null")
    suspend fun get(id: UUID): Cart?

    @Query("select * from ecom.carts where id = any(:ids) and deleted is null")
    suspend fun getByIds(ids: List<UUID>): List<Cart>

    @Query("select * from ecom.carts where id = :id and deleted is null for update")
    suspend fun getForUpdate(id: UUID): Cart?

    @Query("select * from ecom.carts where store_id = :storeId and deleted is null order by modified desc offset :offset limit :limit")
    suspend fun getByStore(storeId: UUID, offset: Int, limit: Int): List<Cart>

    /** Paid carts (the PAID status bit set) for a store — the "orders" admin/fulfillment view. */
    @Query("select * from ecom.carts where store_id = :storeId and (status & :paidBit) = :paidBit and deleted is null order by modified desc offset :offset limit :limit")
    suspend fun getPaidByStore(storeId: UUID, paidBit: Int, offset: Int, limit: Int): List<Cart>

    /** Carts in a store whose status has [statusBit] set — the status-filtered admin order list (legacy `getCarts(companyId, status)`). */
    @Query("select * from ecom.carts where store_id = :storeId and (status & :statusBit) = :statusBit and deleted is null order by modified desc offset :offset limit :limit")
    suspend fun getByStoreAndStatus(storeId: UUID, statusBit: Int, offset: Int, limit: Int): List<Cart>

    @Query(
        """
        insert into ecom.carts
            (company_id, store_id, account_id, customer_id, status, items, extras, expires, billing_same_as_shipping,
             retail_total, retail_subtotal, sales_total, sales_subtotal, shipping, tax, discounts, paid, pending_paid, due, refund_due, quantity, currency)
        values
            (:companyId, :storeId, :accountId, :customerId, :status, :items, :extras, :expires, :billingSameAsShipping,
             :retailTotal, :retailSubtotal, :salesTotal, :salesSubtotal, :shipping, :tax, :discounts, :paid, :pendingPaid, :due, :refundDue, :quantity, :currency)
        returning *
        """,
    )
    suspend fun add(cart: Cart): Cart

    @Query(
        """
        update ecom.carts
           set status = :status, items = :items, extras = :extras, billing_same_as_shipping = :billingSameAsShipping,
               retail_total = :retailTotal, retail_subtotal = :retailSubtotal, sales_total = :salesTotal, sales_subtotal = :salesSubtotal,
               shipping = :shipping, tax = :tax, discounts = :discounts, paid = :paid, pending_paid = :pendingPaid,
               due = :due, refund_due = :refundDue, quantity = :quantity, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(cart: Cart): Cart?

    @Query(
        """
        select * from ecom.carts
         where deleted is null and (status & :openStatus) = :openStatus and expires <= now()
         order by expires
         limit :limit
        """,
    )
    suspend fun getExpiredOpen(openStatus: Int, limit: Int): List<Cart>
}
