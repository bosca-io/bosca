package bosca.analytics.service

import bosca.analytics.model.AnalyticsQueryColumn
import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service responsible for executing analytics queries against the underlying data store.
 *
 * Queries can be resolved either by their human-readable key or by their unique identifier.
 * Parameter values are supplied at execution time to fill placeholders defined
 * in the query's parameter definitions.
 */
interface AnalyticsQueryExecutionService : Service {

    /**
     * Executes the analytics query identified by its human-readable key,
     * substituting the supplied parameter values into the query.
     *
     * @param key the unique key string identifying the query to execute
     * @param parameters the runtime parameter values to bind into the query
     * @return an [AnalyticsQueryResponse] containing the result records
     */
    suspend fun execute(key: String, parameters: List<AnalyticsQueryExecutionParameterInput>): AnalyticsQueryResponse

    /**
     * Executes the analytics query identified by its unique identifier,
     * substituting the supplied parameter values into the query.
     *
     * For queries with a refresh interval configured, the result is served from the
     * query result cache when available; otherwise the query runs against the
     * analytics store and the result is cached for subsequent calls.
     *
     * @param id the unique identifier of the query to execute
     * @param parameters the runtime parameter values to bind into the query
     * @return an [AnalyticsQueryResponse] containing the result records
     */
    suspend fun execute(id: UUID, parameters: List<AnalyticsQueryExecutionParameterInput>): AnalyticsQueryResponse

    /**
     * Executes the analytics query against the analytics store, bypassing any cached
     * result, and replaces the cached records for this parameter combination when the
     * query has a refresh interval configured. Used by the background refresh job and
     * by on-demand refreshes.
     *
     * @param id the unique identifier of the query to refresh
     * @param parameters the runtime parameter values to bind into the query
     * @return an [AnalyticsQueryResponse] containing the freshly computed records
     */
    suspend fun refresh(id: UUID, parameters: List<AnalyticsQueryExecutionParameterInput>): AnalyticsQueryResponse

    /**
     * Returns the output columns of the query identified by [id] without fetching any rows.
     * The query is probed with a `LIMIT 0` wrapper so only its result-set metadata is read;
     * results are cached per query and invalidated when the query's SQL changes. Returns an
     * empty list if the columns cannot be determined (e.g. the probe fails) so callers can
     * degrade gracefully rather than treating it as a hard error.
     *
     * @param id the unique identifier of the query to describe
     * @return the ordered list of output [AnalyticsQueryColumn]s, or empty if undeterminable
     */
    suspend fun getColumns(id: UUID): List<AnalyticsQueryColumn>
}