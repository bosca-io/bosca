package bosca.postgres.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.postgres.admin.model.PgActiveQuery
import bosca.postgres.admin.model.PgBlockingChain
import bosca.postgres.admin.model.PgBouncerInfo
import bosca.postgres.admin.model.PgCheckpointStats
import bosca.postgres.admin.model.PgConnectionPoolStats
import bosca.postgres.admin.model.PgDatabaseStats
import bosca.postgres.admin.model.PgExtension
import bosca.postgres.admin.model.PgIndexStats
import bosca.postgres.admin.model.PgIndexSuggestion
import bosca.postgres.admin.model.PgLockInfo
import bosca.postgres.admin.model.PgLongTransaction
import bosca.postgres.admin.model.PgObjectSize
import bosca.postgres.admin.model.PgReplicationSlot
import bosca.postgres.admin.model.PgReplicationStatus
import bosca.postgres.admin.model.PgSequenceUsage
import bosca.postgres.admin.model.PgSetting
import bosca.postgres.admin.model.PgSlowQuery
import bosca.postgres.admin.model.PgTableIOStats
import bosca.postgres.admin.model.PgTableStats
import bosca.postgres.admin.model.PgVacuumProgress
import bosca.postgres.admin.model.PgWalStats
import bosca.postgres.admin.service.PostgresAdminService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Exposes PostgreSQL database monitoring data through the GraphQL API, allowing
 * administrators to inspect database health, query performance, index usage,
 * lock contention, connection pool status, and server configuration.
 */
@TypeController
class PostgresAdminController(
    private val postgresAdminService: PostgresAdminService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<PostgresAdmin> {

    @Field
    suspend fun databaseStats(authorization: AuthenticationContext): PgDatabaseStats {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getDatabaseStats()
    }

    @Field
    suspend fun activeQueries(
        authorization: AuthenticationContext,
        minDurationSeconds: Float?,
    ): List<PgActiveQuery> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getActiveQueries(minDurationSeconds)
    }

    @Field
    suspend fun slowQueries(
        authorization: AuthenticationContext,
        limit: Int?,
        orderBy: String?,
    ): List<PgSlowQuery> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getSlowQueries(limit ?: 20, orderBy ?: "total")
    }

    @Field
    suspend fun tableStats(
        authorization: AuthenticationContext,
        schemaName: String?,
    ): List<PgTableStats> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getTableStats(schemaName)
    }

    @Field
    suspend fun indexStats(
        authorization: AuthenticationContext,
        schemaName: String?,
    ): List<PgIndexStats> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getIndexStats(schemaName)
    }

    @Field
    suspend fun unusedIndexes(
        authorization: AuthenticationContext,
        minTableSize: Long?,
    ): List<PgIndexStats> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getUnusedIndexes(minTableSize ?: 0)
    }

    @Field
    suspend fun indexSuggestions(
        authorization: AuthenticationContext,
        minSeqScans: Long?,
    ): List<PgIndexSuggestion> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getIndexSuggestions(minSeqScans ?: 50)
    }

    @Field
    suspend fun locks(authorization: AuthenticationContext): List<PgLockInfo> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getLocks()
    }

    @Field
    suspend fun replicationSlots(authorization: AuthenticationContext): List<PgReplicationSlot> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getReplicationSlots()
    }

    @Field
    suspend fun connectionPoolStats(authorization: AuthenticationContext): List<PgConnectionPoolStats> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getConnectionPoolStats()
    }

    @Field
    suspend fun settings(
        authorization: AuthenticationContext,
        filter: String?,
    ): List<PgSetting> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getSettings(filter)
    }

    @Field
    suspend fun vacuumProgress(authorization: AuthenticationContext): List<PgVacuumProgress> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getVacuumProgress()
    }

    @Field
    suspend fun tableIOStats(
        authorization: AuthenticationContext,
        schemaName: String?,
    ): List<PgTableIOStats> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getTableIOStats(schemaName)
    }

    @Field
    suspend fun bloatedTables(
        authorization: AuthenticationContext,
        minBloatPercent: Double?,
    ): List<PgTableStats> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getBloatedTables(minBloatPercent ?: 10.0)
    }

    @Field
    suspend fun serverVersion(authorization: AuthenticationContext): String {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getServerVersion()
    }

    @Field
    suspend fun uptime(authorization: AuthenticationContext): String {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getUptime()
    }

    @Field
    suspend fun walStats(authorization: AuthenticationContext): PgWalStats {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getWalStats()
    }

    @Field
    suspend fun checkpointStats(authorization: AuthenticationContext): PgCheckpointStats {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getCheckpointStats()
    }

    @Field
    suspend fun blockingChains(authorization: AuthenticationContext): List<PgBlockingChain> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getBlockingChains()
    }

    @Field
    suspend fun longRunningTransactions(
        authorization: AuthenticationContext,
        minDurationSeconds: Float?,
    ): List<PgLongTransaction> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getLongRunningTransactions(minDurationSeconds ?: 60f)
    }

    @Field
    suspend fun sequenceUsage(
        authorization: AuthenticationContext,
        minPercentUsed: Float?,
    ): List<PgSequenceUsage> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getSequenceUsage(minPercentUsed ?: 0f)
    }

    @Field
    suspend fun databaseSizeBreakdown(
        authorization: AuthenticationContext,
        limit: Int?,
    ): List<PgObjectSize> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getDatabaseSizeBreakdown(limit ?: 50)
    }

    @Field
    suspend fun installedExtensions(authorization: AuthenticationContext): List<PgExtension> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getInstalledExtensions()
    }

    @Field
    suspend fun pgBouncerInfo(authorization: AuthenticationContext): PgBouncerInfo {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getPgBouncerInfo()
    }

    @Field
    suspend fun replicationStatus(authorization: AuthenticationContext): List<PgReplicationStatus> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.getReplicationStatus()
    }

    @Field
    suspend fun isInRecovery(authorization: AuthenticationContext): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return postgresAdminService.isInRecovery()
    }
}
