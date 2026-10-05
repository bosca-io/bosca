package bosca.analytics.service

import bosca.analytics.configuration.AnalyticsQueryCacheConfiguration
import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryCacheEntry
import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.repository.QueryCacheEntryRepository
import bosca.db.ConnectionManagerCallback
import bosca.db.afterCommit
import bosca.db.connectionOrNull
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * A parameter combination reduced to a stable, order-independent form: the same
 * logical parameters always produce the same [parameters] element and [hash],
 * regardless of parameter ordering or nested JSON object-key ordering.
 */
internal class CanonicalQueryParameters(
    val parameters: JsonElement,
    val hash: String,
)

internal object QueryResultCacheParameters {

    /**
     * Canonicalizes effective execution parameters for use as a cache identity.
     * Parameter order and object-key order do not affect the resulting hash.
     */
    fun canonicalize(json: Json, parameters: List<AnalyticsQueryExecutionParameterInput>): CanonicalQueryParameters {
        val canonical = parameters
            .map { AnalyticsQueryExecutionParameterInput(it.parameter, canonicalizeJson(it.value)) }
            .sortedBy { it.parameter }
        val element = json.encodeToJsonElement(
            ListSerializer(AnalyticsQueryExecutionParameterInput.serializer()),
            canonical,
        )
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(element.toString().toByteArray(Charsets.UTF_8))
        return CanonicalQueryParameters(element, digest.joinToString("") { "%02x".format(it) })
    }

    private fun canonicalizeJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonicalizeJson(it.value) })
        is JsonArray -> JsonArray(value.map(::canonicalizeJson))
        else -> value
    }
}

/**
 * Stores immutable cached result records in object storage, addressed by query
 * generation, canonical parameter hash, and object version. The
 * `analytics_query_cache_entries` row is the atomic index:
 * a result is only served while its row exists, and the row also carries the
 * refresh timestamp. Object storage is used (rather than the distributed cache)
 * because result sets are unbounded — cache backends hold values in memory and
 * NATS-backed deployments cap value sizes.
 */
@ServiceImplementation
class AnalyticsQueryResultCacheServiceImpl(
    private val entryRepository: QueryCacheEntryRepository,
    private val objectStorage: ObjectStorageService,
    private val json: Json,
    private val configuration: AnalyticsQueryCacheConfiguration = AnalyticsQueryCacheConfiguration(),
) : AnalyticsQueryResultCacheService {

    private val recordsSerializer = ListSerializer(JsonElement.serializer())
    internal var currentTime: () -> OffsetDateTime = OffsetDateTime::now

    override suspend fun get(
        queryId: UUID,
        parameters: List<AnalyticsQueryExecutionParameterInput>,
    ): AnalyticsQueryResponse? {
        val generation = entryRepository.getCurrentGeneration(queryId) ?: return null
        val refreshIntervalSeconds = entryRepository.getCurrentRefreshInterval(queryId) ?: return null
        return get(queryId, generation, refreshIntervalSeconds, parameters)
    }

    override suspend fun get(
        query: AnalyticsQuery,
        parameters: List<AnalyticsQueryExecutionParameterInput>,
    ): AnalyticsQueryResponse? {
        val refreshIntervalSeconds = query.refreshIntervalSeconds ?: return null
        return get(query.id, query.cacheGeneration, refreshIntervalSeconds, parameters)
    }

    private suspend fun get(
        queryId: UUID,
        queryGeneration: Long,
        refreshIntervalSeconds: Int,
        parameters: List<AnalyticsQueryExecutionParameterInput>,
    ): AnalyticsQueryResponse? {
        val canonical = QueryResultCacheParameters.canonicalize(json, parameters)
        val entry = entryRepository.getEntry(
            queryId,
            queryGeneration,
            canonical.hash,
        )
        if (entry == null) {
            log.debug(
                "analytics_query_cache status=miss queryId={} generation={} parameterHash={} reason=absent",
                queryId,
                queryGeneration,
                canonical.hash,
            )
            return null
        }
        val records = try {
            json.decodeFromString(recordsSerializer, objectStorage.getString(resultPath(entry)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A row without a readable object (failed write, pruned object, corrupt
            // payload) degrades to a cache miss: the caller executes the query and
            // store() rewrites the object.
            log.warn(
                "Failed to load stored result for analytics query {} combination {}; treating as a cache miss",
                queryId,
                canonical.hash,
                e,
            )
            return null
        }
        log.debug(
            "analytics_query_cache status=hit queryId={} generation={} parameterHash={} rows={}",
            queryId,
            queryGeneration,
            canonical.hash,
            records.size,
        )
        try {
            entryRepository.touchAccessed(queryId, queryGeneration, canonical.hash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "Failed to record analytics query cache access for query {} combination {}",
                queryId,
                canonical.hash,
                e,
            )
        }
        val stale = !entry.lastRefreshedAt
            .plusSeconds(refreshIntervalSeconds.toLong())
            .isAfter(currentTime())
        return AnalyticsQueryResponse(
            records = records,
            cached = true,
            refreshedAt = entry.lastRefreshedAt,
            stale = stale,
        )
    }

    override suspend fun store(
        queryId: UUID,
        parameters: List<AnalyticsQueryExecutionParameterInput>,
        response: AnalyticsQueryResponse,
    ) {
        val generation = entryRepository.getCurrentGeneration(queryId) ?: return
        store(queryId, generation, parameters, response, callerAccess = true)
    }

    override suspend fun store(
        query: AnalyticsQuery,
        parameters: List<AnalyticsQueryExecutionParameterInput>,
        response: AnalyticsQueryResponse,
        callerAccess: Boolean,
    ) {
        store(query.id, query.cacheGeneration, parameters, response, callerAccess)
    }

    private suspend fun store(
        queryId: UUID,
        queryGeneration: Long,
        parameters: List<AnalyticsQueryExecutionParameterInput>,
        response: AnalyticsQueryResponse,
        callerAccess: Boolean,
    ) {
        val canonical = QueryResultCacheParameters.canonicalize(json, parameters)
        val encoded = json.encodeToString(recordsSerializer, response.records)
        val encodedBytes = encoded.toByteArray(Charsets.UTF_8)
        val objectVersion = UUID.random()
        val path = resultPath(queryId, queryGeneration, canonical.hash, objectVersion)
        // Payloads are immutable and the row is the atomic pointer. Readers either
        // see the complete previous object or the complete replacement.
        var pointerMayHaveCommitted = false
        val stored = try {
            objectStorage.setInputStream(path, encodedBytes.inputStream())
            pointerMayHaveCommitted = true
            if (callerAccess) {
                entryRepository.markRefreshed(
                    queryId,
                    queryGeneration,
                    canonical.hash,
                    canonical.parameters,
                    objectVersion,
                )
            } else {
                // Refreshing is update-only. If pruning removed this combination
                // after the job enumerated it, background work must not resurrect it.
                entryRepository.markRefreshedInBackground(
                    queryId,
                    queryGeneration,
                    canonical.hash,
                    canonical.parameters,
                    objectVersion,
                )
            }
        } catch (e: Exception) {
            val pointerCouldBeCommitted =
                pointerMayHaveCommitted && connectionOrNull()?.inTransaction != true
            cleanupFailedPublication(
                path = path,
                queryId = queryId,
                queryGeneration = queryGeneration,
                parametersHash = canonical.hash,
                objectVersion = objectVersion,
                pointerMayHaveCommitted = pointerCouldBeCommitted,
                originalFailure = e,
            )
            throw e
        }
        if (stored == null) {
            // The query was edited, deleted, or caching was disabled while it ran.
            deleteStoredResult(path, queryId, canonical.hash)
            return
        }

        registerRollbackCleanup(path, queryId, canonical.hash)

        afterCommit { deleteSupersededStoredResult(stored) }

        val overflow = entryRepository.deleteOverflowEntries(
            queryId,
            queryGeneration,
            configuration.maxEntriesPerQuery,
        )
        afterCommit { deleteStoredResults(overflow) }
        log.debug(
            "analytics_query_cache status=stored queryId={} generation={} parameterHash={} bytes={} rows={} evicted={}",
            queryId,
            queryGeneration,
            canonical.hash,
            encodedBytes.size,
            response.records.size,
            overflow.size,
        )
    }

    override suspend fun invalidate(queryId: UUID) {
        val entries = entryRepository.deleteByQueryId(queryId)
        afterCommit { deleteStoredResults(entries) }
    }

    override suspend fun getRefreshableEntries(queryId: UUID, onlyStale: Boolean): List<AnalyticsQueryCacheEntry> {
        return if (onlyStale) {
            entryRepository.getStaleEntries(queryId)
        } else {
            entryRepository.getEntries(queryId)
        }
    }

    override suspend fun getQueryIdsDueForRefresh(): List<UUID> {
        return entryRepository.getDueQueryIds(configuration.refreshQueryBatchSize)
    }

    override suspend fun pruneIdleEntries() {
        val legacy = entryRepository.deleteLegacyEntries(configuration.pruneBatchSize)
        val remaining = configuration.pruneBatchSize - legacy.size
        val idle = if (remaining > 0) {
            entryRepository.deleteIdleEntries(configuration.idleRetentionDays, remaining)
        } else {
            emptyList()
        }
        val pruned = legacy + idle
        if (pruned.isEmpty()) return
        afterCommit { deleteStoredResults(pruned) }
        log.info(
            "Pruned {} analytics query cache entries (legacy={}, idle={})",
            pruned.size,
            legacy.size,
            idle.size,
        )
    }

    private suspend fun registerRollbackCleanup(
        path: StringObjectPath,
        queryId: UUID,
        parametersHash: String,
    ) {
        val manager = connectionOrNull()
        if (manager == null || !manager.inTransaction) return
        manager.addCallback(object : ConnectionManagerCallback {
            override suspend fun onCommit() {
            }

            override suspend fun onRollback() {
                deleteStoredResult(path, queryId, parametersHash)
            }

            override suspend fun onRelease() {
            }
        })
    }

    private suspend fun cleanupFailedPublication(
        path: StringObjectPath,
        queryId: UUID,
        queryGeneration: Long,
        parametersHash: String,
        objectVersion: UUID,
        pointerMayHaveCommitted: Boolean,
        originalFailure: Exception,
    ) {
        withContext(NonCancellable) {
            val referenced = if (pointerMayHaveCommitted) {
                try {
                    entryRepository.getStoredEntry(queryId, queryGeneration, parametersHash)
                } catch (verificationCancellation: CancellationException) {
                    if (originalFailure is CancellationException) {
                        if (originalFailure !== verificationCancellation) {
                            originalFailure.addSuppressed(verificationCancellation)
                        }
                        return@withContext
                    }
                    verificationCancellation.addSuppressed(originalFailure)
                    throw verificationCancellation
                } catch (verificationFailure: Exception) {
                    originalFailure.addSuppressed(verificationFailure)
                    log.error(
                        "Could not verify a failed analytics cache publication for query {} combination {}; " +
                            "retaining the immutable object to avoid breaking a possibly committed pointer",
                        queryId,
                        parametersHash,
                        verificationFailure,
                    )
                    return@withContext
                }
            } else {
                null
            }
            if (referenced?.objectVersion == objectVersion) {
                deleteSupersededStoredResult(referenced)
                return@withContext
            }
            try {
                objectStorage.delete(path)
            } catch (cleanupCancellation: CancellationException) {
                if (originalFailure is CancellationException) {
                    if (originalFailure !== cleanupCancellation) {
                        originalFailure.addSuppressed(cleanupCancellation)
                    }
                    return@withContext
                }
                cleanupCancellation.addSuppressed(originalFailure)
                throw cleanupCancellation
            } catch (cleanupFailure: Exception) {
                originalFailure.addSuppressed(cleanupFailure)
                log.error(
                    "Failed to clean up an unpublished analytics query result for query {} combination {}",
                    queryId,
                    parametersHash,
                    cleanupFailure,
                )
            }
        }
    }

    private suspend fun deleteStoredResults(entries: List<AnalyticsQueryCacheEntry>) {
        for (entry in entries) {
            deleteStoredResult(resultPath(entry), entry.queryId, entry.parametersHash)
            deleteSupersededStoredResult(entry)
        }
    }

    private suspend fun deleteSupersededStoredResult(entry: AnalyticsQueryCacheEntry) {
        val supersededVersion = entry.supersededObjectVersion
        if (supersededVersion != null && supersededVersion != entry.objectVersion) {
            deleteStoredResult(
                resultPath(entry.queryId, entry.queryGeneration, entry.parametersHash, supersededVersion),
                entry.queryId,
                entry.parametersHash,
            )
        } else if (entry.supersededLegacyObject && entry.objectVersion != null) {
            deleteStoredResult(
                legacyResultPath(entry.queryId, entry.parametersHash),
                entry.queryId,
                entry.parametersHash,
            )
        }
    }

    private suspend fun deleteStoredResult(path: StringObjectPath, queryId: UUID, parametersHash: String) {
        try {
            objectStorage.delete(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(
                "Failed to delete stored result for analytics query {} combination {}",
                queryId,
                parametersHash,
                e,
            )
        }
    }

    private fun resultPath(entry: AnalyticsQueryCacheEntry) =
        entry.objectVersion?.let {
            resultPath(entry.queryId, entry.queryGeneration, entry.parametersHash, it)
        } ?: legacyResultPath(entry.queryId, entry.parametersHash)

    private fun legacyResultPath(queryId: UUID, parametersHash: String) =
        StringObjectPath("analytics-query-results/$queryId/$parametersHash.json")

    private fun resultPath(queryId: UUID, queryGeneration: Long, parametersHash: String, objectVersion: UUID) =
        StringObjectPath("analytics-query-results/$queryId/$queryGeneration/$parametersHash/$objectVersion.json")

    companion object {
        private val log = LoggerFactory.getLogger(AnalyticsQueryResultCacheServiceImpl::class.java)
    }
}
