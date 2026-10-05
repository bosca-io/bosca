package bosca.analytics.persistence.room

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
internal interface AnalyticsStorageDao {
    @Query(
        "SELECT * FROM analytics_events WHERE namespace = :namespace " +
            "ORDER BY created, clientId LIMIT :limit",
    )
    suspend fun readEvents(namespace: String, limit: Int): List<AnalyticsEventEntity>

    @Query("SELECT * FROM analytics_contexts WHERE id IN (:contextIds)")
    suspend fun readContexts(contextIds: Set<String>): List<AnalyticsContextEntity>

    @Transaction
    suspend fun readContextEvents(namespace: String, limit: Int): List<AnalyticsContextEvents> {
        val events = readEvents(namespace, limit)
        if (events.isEmpty()) return emptyList()
        val contexts = readContexts(events.mapTo(mutableSetOf()) { it.contextId }).associateBy { it.id }
        return events.groupBy { it.contextId }.map { (contextId, contextEvents) ->
            AnalyticsContextEvents(
                context = contexts.getValue(contextId),
                events = contextEvents,
            )
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun writeContext(context: AnalyticsContextEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeEvent(event: AnalyticsEventEntity)

    @Transaction
    suspend fun add(context: AnalyticsContextEntity, event: AnalyticsEventEntity) {
        writeContext(context)
        writeEvent(event)
    }

    @Query("DELETE FROM analytics_events WHERE namespace = :namespace AND clientId IN (:clientIds)")
    suspend fun deleteEvents(namespace: String, clientIds: Set<String>)

    @Query(
        "SELECT DISTINCT contextId FROM analytics_events " +
            "WHERE namespace = :namespace AND clientId IN (:clientIds)",
    )
    suspend fun readEventContextIds(namespace: String, clientIds: Set<String>): List<String>

    @Query(
        "DELETE FROM analytics_contexts WHERE id IN (:contextIds) " +
            "AND NOT EXISTS (SELECT 1 FROM analytics_events WHERE contextId = analytics_contexts.id)",
    )
    suspend fun deleteOrphanContexts(contextIds: Set<String>)

    @Transaction
    suspend fun remove(namespace: String, clientIds: Set<String>) {
        if (clientIds.isEmpty()) return
        val contextIds = readEventContextIds(namespace, clientIds).toSet()
        deleteEvents(namespace, clientIds)
        if (contextIds.isNotEmpty()) deleteOrphanContexts(contextIds)
    }

    @Query("SELECT COUNT(*) FROM analytics_events WHERE namespace = :namespace")
    suspend fun countEvents(namespace: String): Int

    @Query(
        "SELECT payload FROM analytics_feature_flag_caches " +
            "WHERE installationId = :installationId AND identity = :identity",
    )
    suspend fun readFeatureFlags(installationId: String, identity: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeFeatureFlags(cache: AnalyticsFeatureFlagCacheEntity)
}
