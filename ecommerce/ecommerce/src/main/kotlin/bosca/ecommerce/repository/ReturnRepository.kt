package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Return
import bosca.ecommerce.model.ReturnStatus
import bosca.serialization.UUID

/** Persistence for `ecom.returns` (`lines` jsonb via JsonbMapper; status/tender are native enums). */
@Repository
interface ReturnRepository {

    @Query("select * from ecom.returns where id = :id and deleted is null")
    suspend fun get(id: UUID): Return?

    @Query("select * from ecom.returns where id = :id and deleted is null for update")
    suspend fun getForUpdate(id: UUID): Return?

    @Query("select * from ecom.returns where cart_id = :cartId and deleted is null order by created desc")
    suspend fun getByCart(cartId: UUID): List<Return>

    @Query("select * from ecom.returns where store_id = :storeId and deleted is null order by created desc offset :offset limit :limit")
    suspend fun getByStore(storeId: UUID, offset: Int, limit: Int): List<Return>

    @Query(
        "select * from ecom.returns where store_id = :storeId and status = (:status)::ecom.return_status and deleted is null order by created desc offset :offset limit :limit",
    )
    suspend fun getByStoreAndStatus(storeId: UUID, status: ReturnStatus, offset: Int, limit: Int): List<Return>

    @Query(
        """
        insert into ecom.returns (cart_id, store_id, company_id, status, reason, tender, lines, refunded_amount, check_number)
        values (:cartId, :storeId, :companyId, (:status)::ecom.return_status, :reason, (:tender)::ecom.refund_tender, :lines, :refundedAmount, :checkNumber)
        returning *
        """,
    )
    suspend fun add(returnEntity: Return): Return

    @Query(
        """
        update ecom.returns
           set status = (:status)::ecom.return_status, reason = :reason, refunded_amount = :refundedAmount,
               check_number = :checkNumber, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(returnEntity: Return): Return?
}
