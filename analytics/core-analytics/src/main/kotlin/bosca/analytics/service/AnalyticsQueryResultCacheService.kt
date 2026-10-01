package bosca.analytics.service

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryCacheEntry
import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Cache for analytics query results, with the result payloads held in object
 * storage (result sets are unbounded, so they don't belong in the in-memory
 * distributed cache).
 *
 * Results are cached per query and per canonical parameter combination for queries
 * that opt in via [AnalyticsQuery.refreshIntervalSeconds]. Cached combinations are
 * tracked as [AnalyticsQueryCacheEntry] bookkeeping rows, which act as the index
 * for the stored payloads and let the background refresh job re-execute exactly
 * the combinations callers actually use, and prune combinations that have gone idle.
 */
interface AnalyticsQueryResultCacheService : Service {

    /**
     * Returns the cached response for the given query and parameter combination,
     * or `null` when no cached value exists. A hit may be stale and remains
     * available as the last known good result until refresh succeeds. A hit also
     * marks the combination as recently accessed so the background refresh keeps
     * it warm.
     */
    suspend fun get(queryId: UUID, parameters: List<AnalyticsQueryExecutionParameterInput>): AnalyticsQueryResponse?

    /**
     * Returns a cached response only when it was produced by [query]'s current
     * executable definition generation. The response's freshness is evaluated
     * against [AnalyticsQuery.refreshIntervalSeconds].
     */
    suspend fun get(
        query: AnalyticsQuery,
        parameters: List<AnalyticsQueryExecutionParameterInput>,
    ): AnalyticsQueryResponse?

    /**
     * Stores freshly computed [records][AnalyticsQueryResponse.records] for the given
     * query and parameter combination and marks the combination as just refreshed.
     */
    suspend fun store(queryId: UUID, parameters: List<AnalyticsQueryExecutionParameterInput>, response: AnalyticsQueryResponse)

    /**
     * Stores [response] only if [query]'s executable definition generation is
     * still current. [callerAccess] is `false` for background refreshes so they
     * do not keep an otherwise idle parameter combination alive.
     */
    suspend fun store(
        query: AnalyticsQuery,
        parameters: List<AnalyticsQueryExecutionParameterInput>,
        response: AnalyticsQueryResponse,
        callerAccess: Boolean,
    )

    /**
     * Removes all cached results and bookkeeping entries for the given query.
     * Called when a query is edited, synced from git, or deleted, since the
     * cached records may no longer match the query definition.
     */
    suspend fun invalidate(queryId: UUID)

    /**
     * Returns the cached parameter combinations for the given query that the refresh
     * job should re-execute. When [onlyStale] is `true`, only combinations whose last
     * refresh is older than the query's refresh interval are returned; otherwise all
     * tracked combinations are returned.
     */
    suspend fun getRefreshableEntries(queryId: UUID, onlyStale: Boolean): List<AnalyticsQueryCacheEntry>

    /**
     * Returns the ids of queries that have at least one recently accessed cached
     * combination whose last refresh is older than the query's refresh interval.
     */
    suspend fun getQueryIdsDueForRefresh(): List<UUID>

    /**
     * Deletes bookkeeping entries and their stored result objects for combinations
     * that no caller has requested recently, so the background refresh stops
     * re-executing abandoned parameter sets.
     */
    suspend fun pruneIdleEntries()
}
