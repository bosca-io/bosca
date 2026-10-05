package bosca.postgres.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.postgres.admin.service.PostgresAdminService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Handles GraphQL mutation operations for PostgreSQL administration tasks such as
 * canceling queries, terminating backends, and running maintenance operations.
 * All operations require admin group membership.
 */
@TypeController
class PostgresAdminMutationController(
    private val postgresAdminService: PostgresAdminService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<PostgresAdminMutation> {

    /**
     * Sends a cancel signal to the backend with the specified process ID,
     * interrupting its currently running query without closing the connection.
     */
    @Field
    suspend fun cancelQuery(
        authorization: AuthenticationContext,
        pid: Int,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.cancelQuery(pid)
    }

    /**
     * Terminates the backend process with the specified process ID,
     * forcefully closing the connection.
     */
    @Field
    suspend fun terminateBackend(
        authorization: AuthenticationContext,
        pid: Int,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.terminateBackend(pid)
    }

    /**
     * Runs ANALYZE on the specified table to update query planner statistics,
     * potentially improving query plan selection for that table.
     */
    @Field
    suspend fun analyzeTable(
        authorization: AuthenticationContext,
        schemaName: String,
        tableName: String,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.analyzeTable(schemaName, tableName)
    }

    /**
     * Resets the pg_stat_statements statistics counters, clearing all accumulated
     * query performance data. Requires the pg_stat_statements extension.
     */
    @Field
    suspend fun resetStatStatements(
        authorization: AuthenticationContext,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.resetStatStatements()
    }

    /**
     * Runs VACUUM on the specified table to reclaim storage from dead tuples.
     * Optionally performs a VACUUM FULL which rewrites the entire table but
     * requires an exclusive lock.
     */
    @Field
    suspend fun vacuumTable(
        authorization: AuthenticationContext,
        schemaName: String,
        tableName: String,
        full: Boolean?,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.vacuumTable(schemaName, tableName, full ?: false)
    }

    /**
     * Rebuilds the specified index to reclaim bloated space and restore optimal
     * lookup performance. Acquires an exclusive lock on the index's parent table.
     */
    @Field
    suspend fun reindex(
        authorization: AuthenticationContext,
        schemaName: String,
        indexName: String,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.reindex(schemaName, indexName)
    }
}
