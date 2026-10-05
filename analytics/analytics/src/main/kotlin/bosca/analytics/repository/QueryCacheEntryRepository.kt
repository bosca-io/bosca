package bosca.analytics.repository

import bosca.analytics.model.AnalyticsQueryCacheEntry
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Bookkeeping for cached analytics query results. Each row tracks one parameter
 * combination that has been executed and cached for a query, so the background
 * refresh job knows what to re-execute and which combinations have gone idle.
 */
@Repository
interface QueryCacheEntryRepository {

    @Query(
        """
        select e.* from analytics_query_cache_entries e
        join analytics_queries q on q.id = e.query_id
        where e.query_id = :queryId
          and e.query_generation = :queryGeneration
          and e.parameters_hash = :parametersHash
          and q.cache_generation = e.query_generation
          and q.refresh_interval_seconds is not null
        """
    )
    suspend fun getEntry(
        queryId: UUID,
        queryGeneration: Long,
        parametersHash: String,
    ): AnalyticsQueryCacheEntry?

    @Query(
        """
        select * from analytics_query_cache_entries
        where query_id = :queryId
          and query_generation = :queryGeneration
          and parameters_hash = :parametersHash
        """
    )
    suspend fun getStoredEntry(
        queryId: UUID,
        queryGeneration: Long,
        parametersHash: String,
    ): AnalyticsQueryCacheEntry?

    @Query("select cache_generation from analytics_queries where id = :queryId")
    suspend fun getCurrentGeneration(queryId: UUID): Long?

    @Query("select refresh_interval_seconds from analytics_queries where id = :queryId")
    suspend fun getCurrentRefreshInterval(queryId: UUID): Int?

    @Query(
        """
        select e.* from analytics_query_cache_entries e
        join analytics_queries q on q.id = e.query_id and q.cache_generation = e.query_generation
        where e.query_id = :queryId
          and e.object_version is not null
        order by e.created_at
        """
    )
    suspend fun getEntries(queryId: UUID): List<AnalyticsQueryCacheEntry>

    @Query(
        """
        select e.* from analytics_query_cache_entries e
        join analytics_queries q on q.id = e.query_id
        where e.query_id = :queryId
          and e.query_generation = q.cache_generation
          and e.object_version is not null
          and q.refresh_interval_seconds is not null
          and e.last_refreshed_at + q.refresh_interval_seconds * interval '1 second' <= now()
        order by e.created_at
        """
    )
    suspend fun getStaleEntries(queryId: UUID): List<AnalyticsQueryCacheEntry>

    @Query(
        """
        select e.query_id from analytics_query_cache_entries e
        join analytics_queries q on q.id = e.query_id
        where q.refresh_interval_seconds is not null
          and e.query_generation = q.cache_generation
          and e.object_version is not null
          and e.last_refreshed_at + q.refresh_interval_seconds * interval '1 second' <= now()
        group by e.query_id
        order by min(e.last_refreshed_at), e.query_id
        limit :batchSize
        """
    )
    suspend fun getDueQueryIds(batchSize: Int): List<UUID>

    @Query(
        """
        insert into analytics_query_cache_entries (
            query_id, query_generation, parameters_hash, parameters, object_version,
            superseded_object_version, superseded_legacy_object
        )
        select :queryId, :queryGeneration, :parametersHash, :parameters, :objectVersion, null, false
        from analytics_queries
        where id = :queryId
          and cache_generation = :queryGeneration
          and refresh_interval_seconds is not null
        for share
        on conflict (query_id, parameters_hash)
            do update set
                query_generation = excluded.query_generation,
                parameters = excluded.parameters,
                superseded_object_version = analytics_query_cache_entries.object_version,
                superseded_legacy_object = analytics_query_cache_entries.object_version is null,
                object_version = excluded.object_version,
                last_refreshed_at = now(),
                last_accessed_at = now()
        returning *
        """
    )
    suspend fun markRefreshed(
        queryId: UUID,
        queryGeneration: Long,
        parametersHash: String,
        parameters: JsonElement,
        objectVersion: UUID,
    ): AnalyticsQueryCacheEntry?

    @Query(
        """
        update analytics_query_cache_entries e
        set parameters = :parameters,
            superseded_object_version = e.object_version,
            superseded_legacy_object = e.object_version is null,
            object_version = :objectVersion,
            last_refreshed_at = now()
        from analytics_queries q
        where e.query_id = :queryId
          and e.query_generation = :queryGeneration
          and e.parameters_hash = :parametersHash
          and q.id = e.query_id
          and q.cache_generation = e.query_generation
          and q.refresh_interval_seconds is not null
        returning e.*
        """
    )
    suspend fun markRefreshedInBackground(
        queryId: UUID,
        queryGeneration: Long,
        parametersHash: String,
        parameters: JsonElement,
        objectVersion: UUID,
    ): AnalyticsQueryCacheEntry?

    @Query(
        """
        update analytics_query_cache_entries set last_accessed_at = now()
        where query_id = :queryId
          and query_generation = :queryGeneration
          and parameters_hash = :parametersHash
          and last_accessed_at < now() - interval '15 minutes'
        """
    )
    suspend fun touchAccessed(queryId: UUID, queryGeneration: Long, parametersHash: String)

    @Query("delete from analytics_query_cache_entries where query_id = :queryId returning *")
    suspend fun deleteByQueryId(queryId: UUID): List<AnalyticsQueryCacheEntry>

    @Query(
        """
        delete from analytics_query_cache_entries e
        where (e.query_id, e.parameters_hash) in (
            select query_id, parameters_hash
            from analytics_query_cache_entries
            where object_version is null
            order by created_at
            limit :batchSize
            for update skip locked
        )
        returning e.*
        """
    )
    suspend fun deleteLegacyEntries(batchSize: Int): List<AnalyticsQueryCacheEntry>

    @Query(
        """
        delete from analytics_query_cache_entries e
        where (e.query_id, e.parameters_hash) in (
            select query_id, parameters_hash
            from analytics_query_cache_entries
            where last_accessed_at < now() - (:idleDays * interval '1 day')
            order by last_accessed_at
            limit :batchSize
            for update skip locked
        )
        returning e.*
        """
    )
    suspend fun deleteIdleEntries(idleDays: Int, batchSize: Int): List<AnalyticsQueryCacheEntry>

    @Query(
        """
        delete from analytics_query_cache_entries e
        where (e.query_id, e.parameters_hash) in (
            select query_id, parameters_hash
            from analytics_query_cache_entries
            where query_id = :queryId
              and query_generation = :queryGeneration
            order by last_accessed_at desc, created_at desc
            offset :maxEntries
            for update skip locked
        )
        returning e.*
        """
    )
    suspend fun deleteOverflowEntries(
        queryId: UUID,
        queryGeneration: Long,
        maxEntries: Int,
    ): List<AnalyticsQueryCacheEntry>
}
