package bosca.analytics.repository

import bosca.analytics.model.AnalyticsScriptBinding
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Persists [AnalyticsScriptBinding] rows in the `analytics_script_binding` table (public schema,
 * per the analytics convention — see [AnalyticsMigration][bosca.analytics.configuration.AnalyticsMigration]).
 */
@Repository
interface AnalyticsScriptBindingRepository {

    /** All bindings, ordered so lower [ordinal][AnalyticsScriptBinding.ordinal] rows run first. */
    @Query("select * from analytics_script_binding order by ordinal asc, created asc")
    suspend fun getAll(): List<AnalyticsScriptBinding>

    /** Enabled bindings only, in execution order — the processor hot path. */
    @Query("select * from analytics_script_binding where enabled order by ordinal asc, created asc")
    suspend fun getEnabled(): List<AnalyticsScriptBinding>

    /** A single binding by id, or null. */
    @Query("select * from analytics_script_binding where id = :id")
    suspend fun getById(id: UUID): AnalyticsScriptBinding?

    /** Inserts a new binding and returns the persisted row (timestamps stamped by the database). */
    @Query(
        """
        insert into analytics_script_binding (id, script_id, transform, enabled, ordinal, created, modified)
        values (:id, :scriptId, :transform, :enabled, :ordinal, now(), now())
        returning *
        """
    )
    suspend fun insert(
        id: UUID,
        scriptId: UUID,
        transform: Boolean,
        enabled: Boolean,
        ordinal: Int,
    ): AnalyticsScriptBinding?

    /** Updates the mutable fields of a binding and returns the updated row, or null if id is unknown. */
    @Query(
        """
        update analytics_script_binding
           set script_id = :scriptId,
               transform = :transform,
               enabled = :enabled,
               ordinal = :ordinal,
               modified = now()
         where id = :id
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        scriptId: UUID,
        transform: Boolean,
        enabled: Boolean,
        ordinal: Int,
    ): AnalyticsScriptBinding?

    /** Deletes a binding by id. */
    @Query("delete from analytics_script_binding where id = :id")
    suspend fun delete(id: UUID)
}
