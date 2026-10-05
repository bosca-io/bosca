package bosca.analytics.service

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.query.QueryParameterDeclaration
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionInput
import bosca.security.model.PermissionService
import bosca.serialization.UUID

/**
 * Service for managing analytics query definitions and their typed parameters.
 * Provides CRUD operations on queries, parameter retrieval, and access permission control.
 *
 * Extends [PermissionService] to provide permission evaluation and batch-loading
 * capabilities for [AnalyticsQuery] entities identified by [UUID].
 */
interface AnalyticsQueryService : PermissionService<AnalyticsQuery, UUID> {

    /**
     * Retrieves a paginated list of analytics query definitions.
     *
     * @param offset the zero-based index of the first query to return
     * @param limit the maximum number of queries to return
     * @return a list of [AnalyticsQuery] instances within the requested page
     */
    suspend fun getQueries(offset: Long, limit: Int): List<AnalyticsQuery>

    /**
     * Retrieves a single analytics query by its unique identifier.
     *
     * @param id the unique identifier of the query
     * @return the matching [AnalyticsQuery]
     * @throws Exception if no query exists with the given [id]
     */
    suspend fun getQueryById(id: UUID): AnalyticsQuery

    /**
     * Retrieves a single analytics query by its human-readable key.
     *
     * @param key the unique key string identifying the query
     * @return the matching [AnalyticsQuery], or `null` if no query has the given key
     */
    suspend fun getQueryByKey(key: String): AnalyticsQuery?

    /**
     * Retrieves the typed parameter definitions for a single query.
     * Parameters describe the placeholders that must be supplied when executing the query.
     *
     * @param queryId the unique identifier of the query whose parameters to retrieve
     * @return a list of [AnalyticsQueryParameter] definitions for the specified query
     */
    suspend fun getParameters(queryId: UUID): List<AnalyticsQueryParameter>

    /**
     * Retrieves the typed parameter definitions for multiple queries in a single call.
     * This is a batched variant of [getParameters] intended to reduce round-trips
     * when loading parameters for several queries at once.
     *
     * @param queryIds the unique identifiers of the queries whose parameters to retrieve
     * @return a combined list of [AnalyticsQueryParameter] definitions across all specified queries
     */
    suspend fun getParametersForQueries(queryIds: List<UUID>): List<AnalyticsQueryParameter>

    /**
     * Creates a new analytics query definition from the provided input,
     * including any associated parameter definitions.
     *
     * @param query the input data describing the query to create
     * @return the newly created [AnalyticsQuery] with its assigned identifier
     */
    suspend fun addQuery(query: AnalyticsQueryInput): AnalyticsQuery

    /**
     * Updates an existing analytics query definition with the provided input data.
     * The query to update is identified by the [AnalyticsQueryInput.id] field.
     *
     * @param query the input data containing the updated query fields
     * @return the updated [AnalyticsQuery]
     */
    suspend fun editQuery(query: AnalyticsQueryInput): AnalyticsQuery

    /**
     * Applies a git-originated update to an existing query, atomically updating its
     * SQL and (optionally) its parameter set.
     *
     * @param queryId the id of the query being updated
     * @param newSql the SQL to store on the query (already stripped of any
     *               `@bosca-query` metadata block by the caller)
     * @param parameterDeclarations parameter declarations parsed from the
     *               `@bosca-query` block. When `null` (no block present in the
     *               source file), existing parameter rows are left untouched —
     *               this is the "soft-mode" behavior that prevents UI-defined
     *               parameters from being wiped by SQL-only commits. An empty
     *               list explicitly clears all parameters.
     * @return the resulting [AnalyticsQuery], or `null` if no query exists with [queryId]
     */
    suspend fun applyGitSync(
        queryId: UUID,
        newSql: String,
        parameterDeclarations: List<QueryParameterDeclaration>?,
    ): AnalyticsQuery?

    /**
     * Deletes an analytics query definition by its unique identifier.
     *
     * @param id the unique identifier of the query to delete
     */
    suspend fun deleteQueryById(id: UUID)

    /**
     * Grants a permission on a query to a subject (e.g., user or group)
     * as described by the given [PermissionInput].
     *
     * @param permission the permission specification identifying the entity, subject, and access level
     * @return the created [EntityPermission] record
     */
    suspend fun addPermission(permission: PermissionInput): EntityPermission

    /**
     * Revokes a permission on a query from a subject as described by the given [PermissionInput].
     *
     * @param permission the permission specification identifying the entity, subject, and access level to revoke
     * @return the removed [EntityPermission] record
     */
    suspend fun deletePermission(permission: PermissionInput): EntityPermission
}
