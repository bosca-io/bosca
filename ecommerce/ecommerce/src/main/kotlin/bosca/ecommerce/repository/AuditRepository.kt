package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Audit
import bosca.serialization.UUID

/** Append-only writes to `ecom.audit`, plus the admin read path. jsonb columns use the `::jsonb` cast. */
@Repository
interface AuditRepository {

    @Query(
        """
        insert into ecom.audit (principal_id, profile_id, store_id, entity_type, entity_id, action, before, after, details)
        values (:principalId, :profileId, :storeId, :entityType, :entityId, :action, :before::jsonb, :after::jsonb, :details::jsonb)
        returning *
        """,
    )
    suspend fun add(audit: Audit): Audit

    /**
     * Filterable read, newest first. Each filter is optional — a null param matches everything (the
     * `:p::type is null or col = :p` idiom; the cast lets Postgres infer the bind type).
     */
    @Query(
        """
        select * from ecom.audit
         where (:entityType::text is null or entity_type = :entityType)
           and (:entityId::uuid is null or entity_id = :entityId)
           and (:storeId::uuid is null or store_id = :storeId)
           and (:action::text is null or action = :action)
         order by created desc
         offset :offset limit :limit
        """,
    )
    suspend fun list(
        entityType: String?,
        entityId: UUID?,
        storeId: UUID?,
        action: String?,
        offset: Int,
        limit: Int,
    ): List<Audit>
}
