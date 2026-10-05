package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Promotion
import bosca.serialization.UUID

/** Persistence for `ecom.promotions` (unique store+code). The `rule` jsonb round-trips via JsonbMapper. */
@Repository
interface PromotionRepository {

    @Query("select * from ecom.promotions where id = :id and deleted is null")
    suspend fun get(id: UUID): Promotion?

    @Query("select * from ecom.promotions where store_id = :storeId and code = :code and deleted is null")
    suspend fun getByCode(storeId: UUID, code: String): Promotion?

    @Query("select * from ecom.promotions where store_id = :storeId and deleted is null order by created desc offset :offset limit :limit")
    suspend fun getByStore(storeId: UUID, offset: Int, limit: Int): List<Promotion>

    @Query(
        """
        insert into ecom.promotions (store_id, code, name, type, rule, starts, ends)
        values (:storeId, :code, :name, (:type)::ecom.promotion_type, :rule, :starts, :ends)
        returning *
        """,
    )
    suspend fun add(promotion: Promotion): Promotion

    @Query(
        """
        update ecom.promotions
           set code = :code, name = :name, type = (:type)::ecom.promotion_type, rule = :rule,
               starts = :starts, ends = :ends, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(promotion: Promotion): Promotion?

    @Query("update ecom.promotions set deleted = now(), modified = now() where id = :id and deleted is null")
    suspend fun softDelete(id: UUID)
}
