package bosca.postgres.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.postgres.admin.model.PgActiveQuery
import bosca.postgres.admin.model.PgBlockingChain
import bosca.postgres.admin.model.PgBouncerDatabase
import bosca.postgres.admin.model.PgBouncerDatabaseStats
import bosca.postgres.admin.model.PgBouncerInfo
import bosca.postgres.admin.model.PgBouncerPoolStats
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

/**
 * Type controllers that register GraphQL field data fetchers for all PostgreSQL admin
 * model types. Each controller maps its data class properties to the corresponding
 * GraphQL object type fields so the framework's custom wiring can resolve them.
 */

@TypeController
class PgActiveQueryTypeController : GraphQLController<PgActiveQuery> {
    @Field fun pid(q: PgActiveQuery) = q.pid
    @Field fun databaseName(q: PgActiveQuery) = q.databaseName
    @Field fun userName(q: PgActiveQuery) = q.userName
    @Field fun applicationName(q: PgActiveQuery) = q.applicationName
    @Field fun clientAddr(q: PgActiveQuery) = q.clientAddr
    @Field fun state(q: PgActiveQuery) = q.state
    @Field fun query(q: PgActiveQuery) = q.query
    @Field fun queryStart(q: PgActiveQuery) = q.queryStart
    @Field fun stateChange(q: PgActiveQuery) = q.stateChange
    @Field fun xactStart(q: PgActiveQuery) = q.xactStart
    @Field fun queryDurationSeconds(q: PgActiveQuery) = q.queryDurationSeconds
    @Field fun waitEventType(q: PgActiveQuery) = q.waitEventType
    @Field fun waitEvent(q: PgActiveQuery) = q.waitEvent
    @Field fun backendStart(q: PgActiveQuery) = q.backendStart
    @Field fun backendType(q: PgActiveQuery) = q.backendType
}

@TypeController
class PgBlockingChainTypeController : GraphQLController<PgBlockingChain> {
    @Field fun blockedPid(c: PgBlockingChain) = c.blockedPid
    @Field fun blockedUser(c: PgBlockingChain) = c.blockedUser
    @Field fun blockedQuery(c: PgBlockingChain) = c.blockedQuery
    @Field fun blockedState(c: PgBlockingChain) = c.blockedState
    @Field fun blockedDurationSeconds(c: PgBlockingChain) = c.blockedDurationSeconds
    @Field fun blockingPid(c: PgBlockingChain) = c.blockingPid
    @Field fun blockingUser(c: PgBlockingChain) = c.blockingUser
    @Field fun blockingQuery(c: PgBlockingChain) = c.blockingQuery
    @Field fun blockingState(c: PgBlockingChain) = c.blockingState
}

@TypeController
class PgCheckpointStatsTypeController : GraphQLController<PgCheckpointStats> {
    @Field fun checkpointsTimedCount(s: PgCheckpointStats) = s.checkpointsTimedCount
    @Field fun checkpointsRequestedCount(s: PgCheckpointStats) = s.checkpointsRequestedCount
    @Field fun buffersCheckpoint(s: PgCheckpointStats) = s.buffersCheckpoint
    @Field fun buffersClean(s: PgCheckpointStats) = s.buffersClean
    @Field fun maxwrittenClean(s: PgCheckpointStats) = s.maxwrittenClean
    @Field fun buffersBackend(s: PgCheckpointStats) = s.buffersBackend
    @Field fun buffersBackendFsync(s: PgCheckpointStats) = s.buffersBackendFsync
    @Field fun buffersAlloc(s: PgCheckpointStats) = s.buffersAlloc
    @Field fun statsReset(s: PgCheckpointStats) = s.statsReset
}

@TypeController
class PgConnectionPoolStatsTypeController : GraphQLController<PgConnectionPoolStats> {
    @Field fun poolName(s: PgConnectionPoolStats) = s.poolName
    @Field fun maxConnections(s: PgConnectionPoolStats) = s.maxConnections
    @Field fun activeConnections(s: PgConnectionPoolStats) = s.activeConnections
    @Field fun createdConnections(s: PgConnectionPoolStats) = s.createdConnections
    @Field fun hasAvailableConnections(s: PgConnectionPoolStats) = s.hasAvailableConnections
}

@TypeController
class PgDatabaseStatsTypeController : GraphQLController<PgDatabaseStats> {
    @Field fun databaseName(s: PgDatabaseStats) = s.databaseName
    @Field fun numBackends(s: PgDatabaseStats) = s.numBackends
    @Field fun xactCommit(s: PgDatabaseStats) = s.xactCommit
    @Field fun xactRollback(s: PgDatabaseStats) = s.xactRollback
    @Field fun blksRead(s: PgDatabaseStats) = s.blksRead
    @Field fun blksHit(s: PgDatabaseStats) = s.blksHit
    @Field fun tupReturned(s: PgDatabaseStats) = s.tupReturned
    @Field fun tupFetched(s: PgDatabaseStats) = s.tupFetched
    @Field fun tupInserted(s: PgDatabaseStats) = s.tupInserted
    @Field fun tupUpdated(s: PgDatabaseStats) = s.tupUpdated
    @Field fun tupDeleted(s: PgDatabaseStats) = s.tupDeleted
    @Field fun conflicts(s: PgDatabaseStats) = s.conflicts
    @Field fun tempFiles(s: PgDatabaseStats) = s.tempFiles
    @Field fun tempBytes(s: PgDatabaseStats) = s.tempBytes
    @Field fun deadlocks(s: PgDatabaseStats) = s.deadlocks
    @Field fun cacheHitRatio(s: PgDatabaseStats) = s.cacheHitRatio
    @Field fun databaseSize(s: PgDatabaseStats) = s.databaseSize
    @Field fun statsReset(s: PgDatabaseStats) = s.statsReset
}

@TypeController
class PgExtensionTypeController : GraphQLController<PgExtension> {
    @Field fun name(e: PgExtension) = e.name
    @Field fun installedVersion(e: PgExtension) = e.installedVersion
    @Field fun defaultVersion(e: PgExtension) = e.defaultVersion
    @Field fun description(e: PgExtension) = e.description
}

@TypeController
class PgIndexStatsTypeController : GraphQLController<PgIndexStats> {
    @Field fun schemaName(s: PgIndexStats) = s.schemaName
    @Field fun tableName(s: PgIndexStats) = s.tableName
    @Field fun indexName(s: PgIndexStats) = s.indexName
    @Field fun idxScan(s: PgIndexStats) = s.idxScan
    @Field fun idxTupRead(s: PgIndexStats) = s.idxTupRead
    @Field fun idxTupFetch(s: PgIndexStats) = s.idxTupFetch
    @Field fun indexSize(s: PgIndexStats) = s.indexSize
    @Field fun indexDef(s: PgIndexStats) = s.indexDef
}

@TypeController
class PgIndexSuggestionTypeController : GraphQLController<PgIndexSuggestion> {
    @Field fun schemaName(s: PgIndexSuggestion) = s.schemaName
    @Field fun tableName(s: PgIndexSuggestion) = s.tableName
    @Field fun seqScan(s: PgIndexSuggestion) = s.seqScan
    @Field fun seqTupRead(s: PgIndexSuggestion) = s.seqTupRead
    @Field fun idxScan(s: PgIndexSuggestion) = s.idxScan
    @Field fun tableSize(s: PgIndexSuggestion) = s.tableSize
    @Field fun reason(s: PgIndexSuggestion) = s.reason
}

@TypeController
class PgLockInfoTypeController : GraphQLController<PgLockInfo> {
    @Field fun pid(l: PgLockInfo) = l.pid
    @Field fun lockType(l: PgLockInfo) = l.lockType
    @Field fun databaseName(l: PgLockInfo) = l.databaseName
    @Field fun relationName(l: PgLockInfo) = l.relationName
    @Field fun mode(l: PgLockInfo) = l.mode
    @Field fun granted(l: PgLockInfo) = l.granted
    @Field fun query(l: PgLockInfo) = l.query
    @Field fun state(l: PgLockInfo) = l.state
    @Field fun durationSeconds(l: PgLockInfo) = l.durationSeconds
}

@TypeController
class PgLongTransactionTypeController : GraphQLController<PgLongTransaction> {
    @Field fun pid(t: PgLongTransaction) = t.pid
    @Field fun databaseName(t: PgLongTransaction) = t.databaseName
    @Field fun userName(t: PgLongTransaction) = t.userName
    @Field fun applicationName(t: PgLongTransaction) = t.applicationName
    @Field fun state(t: PgLongTransaction) = t.state
    @Field fun xactStartTime(t: PgLongTransaction) = t.xactStartTime
    @Field fun xactDurationSeconds(t: PgLongTransaction) = t.xactDurationSeconds
    @Field fun lastQuery(t: PgLongTransaction) = t.lastQuery
    @Field fun waitEventType(t: PgLongTransaction) = t.waitEventType
    @Field fun waitEvent(t: PgLongTransaction) = t.waitEvent
}

@TypeController
class PgObjectSizeTypeController : GraphQLController<PgObjectSize> {
    @Field fun schemaName(s: PgObjectSize) = s.schemaName
    @Field fun objectName(s: PgObjectSize) = s.objectName
    @Field fun objectType(s: PgObjectSize) = s.objectType
    @Field fun totalSize(s: PgObjectSize) = s.totalSize
    @Field fun tableSize(s: PgObjectSize) = s.tableSize
    @Field fun indexSize(s: PgObjectSize) = s.indexSize
}

@TypeController
class PgReplicationSlotTypeController : GraphQLController<PgReplicationSlot> {
    @Field fun slotName(s: PgReplicationSlot) = s.slotName
    @Field fun slotType(s: PgReplicationSlot) = s.slotType
    @Field fun active(s: PgReplicationSlot) = s.active
    @Field fun databaseName(s: PgReplicationSlot) = s.databaseName
    @Field fun confirmedFlushLsn(s: PgReplicationSlot) = s.confirmedFlushLsn
    @Field fun retainedWalBytes(s: PgReplicationSlot) = s.retainedWalBytes
}

@TypeController
class PgSequenceUsageTypeController : GraphQLController<PgSequenceUsage> {
    @Field fun schemaName(s: PgSequenceUsage) = s.schemaName
    @Field fun sequenceName(s: PgSequenceUsage) = s.sequenceName
    @Field fun dataType(s: PgSequenceUsage) = s.dataType
    @Field fun currentValue(s: PgSequenceUsage) = s.currentValue
    @Field fun maxValue(s: PgSequenceUsage) = s.maxValue
    @Field fun percentUsed(s: PgSequenceUsage) = s.percentUsed
}

@TypeController
class PgSettingTypeController : GraphQLController<PgSetting> {
    @Field fun name(s: PgSetting) = s.name
    @Field fun setting(s: PgSetting) = s.setting
    @Field fun unit(s: PgSetting) = s.unit
    @Field fun category(s: PgSetting) = s.category
    @Field fun shortDesc(s: PgSetting) = s.shortDesc
    @Field fun context(s: PgSetting) = s.context
    @Field fun vartype(s: PgSetting) = s.vartype
    @Field fun source(s: PgSetting) = s.source
    @Field fun minVal(s: PgSetting) = s.minVal
    @Field fun maxVal(s: PgSetting) = s.maxVal
    @Field fun enumVals(s: PgSetting) = s.enumVals
    @Field fun pendingRestart(s: PgSetting) = s.pendingRestart
}

@TypeController
class PgSlowQueryTypeController : GraphQLController<PgSlowQuery> {
    @Field fun query(q: PgSlowQuery) = q.query
    @Field fun calls(q: PgSlowQuery) = q.calls
    @Field fun totalTimeMs(q: PgSlowQuery) = q.totalTimeMs
    @Field fun meanTimeMs(q: PgSlowQuery) = q.meanTimeMs
    @Field fun minTimeMs(q: PgSlowQuery) = q.minTimeMs
    @Field fun maxTimeMs(q: PgSlowQuery) = q.maxTimeMs
    @Field fun stddevTimeMs(q: PgSlowQuery) = q.stddevTimeMs
    @Field fun rows(q: PgSlowQuery) = q.rows
    @Field fun sharedBlksHit(q: PgSlowQuery) = q.sharedBlksHit
    @Field fun sharedBlksRead(q: PgSlowQuery) = q.sharedBlksRead
    @Field fun hitRatio(q: PgSlowQuery) = q.hitRatio
}

@TypeController
class PgTableIOStatsTypeController : GraphQLController<PgTableIOStats> {
    @Field fun schemaName(s: PgTableIOStats) = s.schemaName
    @Field fun tableName(s: PgTableIOStats) = s.tableName
    @Field fun heapBlksRead(s: PgTableIOStats) = s.heapBlksRead
    @Field fun heapBlksHit(s: PgTableIOStats) = s.heapBlksHit
    @Field fun idxBlksRead(s: PgTableIOStats) = s.idxBlksRead
    @Field fun idxBlksHit(s: PgTableIOStats) = s.idxBlksHit
    @Field fun toastBlksRead(s: PgTableIOStats) = s.toastBlksRead
    @Field fun toastBlksHit(s: PgTableIOStats) = s.toastBlksHit
    @Field fun cacheHitRatio(s: PgTableIOStats) = s.cacheHitRatio
}

@TypeController
class PgTableStatsTypeController : GraphQLController<PgTableStats> {
    @Field fun schemaName(s: PgTableStats) = s.schemaName
    @Field fun tableName(s: PgTableStats) = s.tableName
    @Field fun seqScan(s: PgTableStats) = s.seqScan
    @Field fun seqTupRead(s: PgTableStats) = s.seqTupRead
    @Field fun idxScan(s: PgTableStats) = s.idxScan
    @Field fun idxTupFetch(s: PgTableStats) = s.idxTupFetch
    @Field fun nTupIns(s: PgTableStats) = s.nTupIns
    @Field fun nTupUpd(s: PgTableStats) = s.nTupUpd
    @Field fun nTupDel(s: PgTableStats) = s.nTupDel
    @Field fun nTupHotUpd(s: PgTableStats) = s.nTupHotUpd
    @Field fun nLiveTup(s: PgTableStats) = s.nLiveTup
    @Field fun nDeadTup(s: PgTableStats) = s.nDeadTup
    @Field fun lastVacuum(s: PgTableStats) = s.lastVacuum
    @Field fun lastAutovacuum(s: PgTableStats) = s.lastAutovacuum
    @Field fun lastAnalyze(s: PgTableStats) = s.lastAnalyze
    @Field fun lastAutoanalyze(s: PgTableStats) = s.lastAutoanalyze
    @Field fun vacuumCount(s: PgTableStats) = s.vacuumCount
    @Field fun autovacuumCount(s: PgTableStats) = s.autovacuumCount
    @Field fun analyzeCount(s: PgTableStats) = s.analyzeCount
    @Field fun autoanalyzeCount(s: PgTableStats) = s.autoanalyzeCount
    @Field fun totalSize(s: PgTableStats) = s.totalSize
    @Field fun tableSize(s: PgTableStats) = s.tableSize
    @Field fun indexSize(s: PgTableStats) = s.indexSize
    @Field fun bloatRatio(s: PgTableStats) = s.bloatRatio
}

@TypeController
class PgVacuumProgressTypeController : GraphQLController<PgVacuumProgress> {
    @Field fun pid(p: PgVacuumProgress) = p.pid
    @Field fun databaseName(p: PgVacuumProgress) = p.databaseName
    @Field fun schemaName(p: PgVacuumProgress) = p.schemaName
    @Field fun tableName(p: PgVacuumProgress) = p.tableName
    @Field fun phase(p: PgVacuumProgress) = p.phase
    @Field fun heapBlksTotal(p: PgVacuumProgress) = p.heapBlksTotal
    @Field fun heapBlksScanned(p: PgVacuumProgress) = p.heapBlksScanned
    @Field fun heapBlksVacuumed(p: PgVacuumProgress) = p.heapBlksVacuumed
    @Field fun numDeadTuples(p: PgVacuumProgress) = p.numDeadTuples
}

@TypeController
class PgWalStatsTypeController : GraphQLController<PgWalStats> {
    @Field fun walRecords(s: PgWalStats) = s.walRecords
    @Field fun walFpi(s: PgWalStats) = s.walFpi
    @Field fun walBytes(s: PgWalStats) = s.walBytes
    @Field fun walBuffersFull(s: PgWalStats) = s.walBuffersFull
    @Field fun walWrite(s: PgWalStats) = s.walWrite
    @Field fun walSync(s: PgWalStats) = s.walSync
    @Field fun walWriteTime(s: PgWalStats) = s.walWriteTime
    @Field fun walSyncTime(s: PgWalStats) = s.walSyncTime
    @Field fun statsReset(s: PgWalStats) = s.statsReset
}

@TypeController
class PgBouncerInfoTypeController : GraphQLController<PgBouncerInfo> {
    @Field fun available(i: PgBouncerInfo) = i.available
    @Field fun version(i: PgBouncerInfo) = i.version
    @Field fun pools(i: PgBouncerInfo) = i.pools
    @Field fun stats(i: PgBouncerInfo) = i.stats
    @Field fun databases(i: PgBouncerInfo) = i.databases
}

@TypeController
class PgBouncerPoolStatsTypeController : GraphQLController<PgBouncerPoolStats> {
    @Field fun database(s: PgBouncerPoolStats) = s.database
    @Field fun user(s: PgBouncerPoolStats) = s.user
    @Field fun clActive(s: PgBouncerPoolStats) = s.clActive
    @Field fun clWaiting(s: PgBouncerPoolStats) = s.clWaiting
    @Field fun clCancelReq(s: PgBouncerPoolStats) = s.clCancelReq
    @Field fun clActiveCancelReq(s: PgBouncerPoolStats) = s.clActiveCancelReq
    @Field fun svActive(s: PgBouncerPoolStats) = s.svActive
    @Field fun svActiveCancel(s: PgBouncerPoolStats) = s.svActiveCancel
    @Field fun svBeingCanceled(s: PgBouncerPoolStats) = s.svBeingCanceled
    @Field fun svIdle(s: PgBouncerPoolStats) = s.svIdle
    @Field fun svUsed(s: PgBouncerPoolStats) = s.svUsed
    @Field fun svTested(s: PgBouncerPoolStats) = s.svTested
    @Field fun svLogin(s: PgBouncerPoolStats) = s.svLogin
    @Field fun maxwait(s: PgBouncerPoolStats) = s.maxwait
    @Field fun maxwaitUs(s: PgBouncerPoolStats) = s.maxwaitUs
    @Field fun poolMode(s: PgBouncerPoolStats) = s.poolMode
}

@TypeController
class PgBouncerDatabaseStatsTypeController : GraphQLController<PgBouncerDatabaseStats> {
    @Field fun database(s: PgBouncerDatabaseStats) = s.database
    @Field fun totalXactCount(s: PgBouncerDatabaseStats) = s.totalXactCount
    @Field fun totalQueryCount(s: PgBouncerDatabaseStats) = s.totalQueryCount
    @Field fun totalReceived(s: PgBouncerDatabaseStats) = s.totalReceived
    @Field fun totalSent(s: PgBouncerDatabaseStats) = s.totalSent
    @Field fun totalXactTime(s: PgBouncerDatabaseStats) = s.totalXactTime
    @Field fun totalQueryTime(s: PgBouncerDatabaseStats) = s.totalQueryTime
    @Field fun totalWaitTime(s: PgBouncerDatabaseStats) = s.totalWaitTime
    @Field fun avgXactCount(s: PgBouncerDatabaseStats) = s.avgXactCount
    @Field fun avgQueryCount(s: PgBouncerDatabaseStats) = s.avgQueryCount
    @Field fun avgRecv(s: PgBouncerDatabaseStats) = s.avgRecv
    @Field fun avgSent(s: PgBouncerDatabaseStats) = s.avgSent
    @Field fun avgXactTime(s: PgBouncerDatabaseStats) = s.avgXactTime
    @Field fun avgQueryTime(s: PgBouncerDatabaseStats) = s.avgQueryTime
    @Field fun avgWaitTime(s: PgBouncerDatabaseStats) = s.avgWaitTime
}

@TypeController
class PgBouncerDatabaseTypeController : GraphQLController<PgBouncerDatabase> {
    @Field fun name(d: PgBouncerDatabase) = d.name
    @Field fun host(d: PgBouncerDatabase) = d.host
    @Field fun port(d: PgBouncerDatabase) = d.port
    @Field fun database(d: PgBouncerDatabase) = d.database
    @Field fun forceUser(d: PgBouncerDatabase) = d.forceUser
    @Field fun poolSize(d: PgBouncerDatabase) = d.poolSize
    @Field fun minPoolSize(d: PgBouncerDatabase) = d.minPoolSize
    @Field fun reservePool(d: PgBouncerDatabase) = d.reservePool
    @Field fun poolMode(d: PgBouncerDatabase) = d.poolMode
    @Field fun maxConnections(d: PgBouncerDatabase) = d.maxConnections
    @Field fun currentConnections(d: PgBouncerDatabase) = d.currentConnections
    @Field fun paused(d: PgBouncerDatabase) = d.paused
    @Field fun disabled(d: PgBouncerDatabase) = d.disabled
}

@TypeController
class PgReplicationStatusTypeController : GraphQLController<PgReplicationStatus> {
    @Field fun pid(r: PgReplicationStatus) = r.pid
    @Field fun userName(r: PgReplicationStatus) = r.userName
    @Field fun applicationName(r: PgReplicationStatus) = r.applicationName
    @Field fun clientAddr(r: PgReplicationStatus) = r.clientAddr
    @Field fun state(r: PgReplicationStatus) = r.state
    @Field fun sentLsn(r: PgReplicationStatus) = r.sentLsn
    @Field fun writeLsn(r: PgReplicationStatus) = r.writeLsn
    @Field fun flushLsn(r: PgReplicationStatus) = r.flushLsn
    @Field fun replayLsn(r: PgReplicationStatus) = r.replayLsn
    @Field fun replayLagSeconds(r: PgReplicationStatus) = r.replayLagSeconds
    @Field fun writeLagSeconds(r: PgReplicationStatus) = r.writeLagSeconds
    @Field fun flushLagSeconds(r: PgReplicationStatus) = r.flushLagSeconds
    @Field fun syncState(r: PgReplicationStatus) = r.syncState
    @Field fun syncPriority(r: PgReplicationStatus) = r.syncPriority
}
