package bosca.analytics.service

import bosca.analytics.configuration.AnalyticsQueryCacheConfiguration
import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryCacheEntry
import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.repository.QueryCacheEntryRepository
import bosca.db.ConnectionManager
import bosca.db.ConnectionManagerCallback
import bosca.db.asCoroutineContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.slot
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AnalyticsQueryResultCacheServiceImplTest {
    private val repository = mockk<QueryCacheEntryRepository>()
    private val storage = mockk<ObjectStorageService>()
    private val json = Json
    private val configuration = AnalyticsQueryCacheConfiguration(maxEntriesPerQuery = 2, pruneBatchSize = 10)
    private val service = AnalyticsQueryResultCacheServiceImpl(repository, storage, json, configuration)
    private val queryId = UUID.random()
    private val query = AnalyticsQuery(
        id = queryId,
        key = "query",
        name = "Query",
        description = "",
        query = "select 1",
        refreshIntervalSeconds = 60,
        cacheGeneration = 4,
    )
    private val parameters = listOf(AnalyticsQueryExecutionParameterInput("limit", JsonPrimitive(3)))
    private val canonical = QueryResultCacheParameters.canonicalize(json, parameters)

    private fun entry(
        id: UUID = queryId,
        hash: String = canonical.hash,
        objectVersion: UUID? = UUID.random(),
        supersededObjectVersion: UUID? = null,
        supersededLegacyObject: Boolean = false,
        lastRefreshedAt: OffsetDateTime = OffsetDateTime.now(),
    ) = AnalyticsQueryCacheEntry(
        queryId = id,
        parametersHash = hash,
        parameters = canonical.parameters,
        lastRefreshedAt = lastRefreshedAt,
        lastAccessedAt = OffsetDateTime.now(),
        queryGeneration = query.cacheGeneration,
        objectVersion = objectVersion,
        supersededObjectVersion = supersededObjectVersion,
        supersededLegacyObject = supersededLegacyObject,
    )

    @Test
    fun `get returns null when bookkeeping row is absent`() = runTest {
        coEvery {
            repository.getEntry(queryId, query.cacheGeneration, canonical.hash)
        } returns null

        assertNull(service.get(query, parameters))
    }

    @Test
    fun `get returns cached records and touches access time`() = runTest {
        val entry = entry()
        coEvery {
            repository.getEntry(queryId, query.cacheGeneration, canonical.hash)
        } returns entry
        coEvery {
            storage.getString(match { canonical.hash in it.toString() && entry.objectVersion.toString() in it.toString() })
        } returns "[{\"value\":1}]"
        coJustRun { repository.touchAccessed(queryId, query.cacheGeneration, canonical.hash) }

        val response = service.get(query, parameters)

        assertEquals(true, response?.cached)
        assertEquals(false, response?.stale)
        assertEquals(entry.lastRefreshedAt, response?.refreshedAt)
        assertEquals(1, response?.records?.size)
    }

    @Test
    fun `get returns an overdue cached result marked stale`() = runTest {
        val now = OffsetDateTime.parse("2026-07-28T12:00:00Z")
        val entry = entry(lastRefreshedAt = now.minusSeconds(61))
        service.currentTime = { now }
        coEvery {
            repository.getEntry(queryId, query.cacheGeneration, canonical.hash)
        } returns entry
        coEvery { storage.getString(any()) } returns "[]"
        coJustRun { repository.touchAccessed(queryId, query.cacheGeneration, canonical.hash) }

        val response = service.get(query, parameters)

        assertEquals(true, response?.cached)
        assertEquals(true, response?.stale)
        assertEquals(entry.lastRefreshedAt, response?.refreshedAt)
    }

    @Test
    fun `legacy get resolves current generation and interval before classifying freshness`() = runTest {
        val now = OffsetDateTime.parse("2026-07-28T12:00:00Z")
        val entry = entry(lastRefreshedAt = now.minusSeconds(60))
        service.currentTime = { now }
        coEvery { repository.getCurrentGeneration(queryId) } returns query.cacheGeneration
        coEvery { repository.getCurrentRefreshInterval(queryId) } returns 60
        coEvery {
            repository.getEntry(queryId, query.cacheGeneration, canonical.hash)
        } returns entry
        coEvery { storage.getString(any()) } returns "[]"
        coJustRun { repository.touchAccessed(queryId, query.cacheGeneration, canonical.hash) }

        assertEquals(true, service.get(queryId, parameters)?.stale)
    }

    @Test
    fun `get skips caching when the query is missing or caching is disabled`() = runTest {
        coEvery { repository.getCurrentGeneration(queryId) } returns null
        assertNull(service.get(queryId, parameters))

        coEvery { repository.getCurrentGeneration(queryId) } returns query.cacheGeneration
        coEvery { repository.getCurrentRefreshInterval(queryId) } returns null
        assertNull(service.get(queryId, parameters))

        assertNull(service.get(query.copy(refreshIntervalSeconds = null), parameters))
        coVerify(exactly = 0) { repository.getEntry(any(), any(), any()) }
    }

    @Test
    fun `unreadable cached object degrades to a miss while cancellation propagates`() = runTest {
        coEvery {
            repository.getEntry(queryId, query.cacheGeneration, canonical.hash)
        } returns entry()
        coEvery { storage.getString(any()) } throws IllegalStateException("missing")
        assertNull(service.get(query, parameters))

        coEvery { storage.getString(any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { service.get(query, parameters) }
    }

    @Test
    fun `access bookkeeping failure does not discard a readable cached response`() = runTest {
        val entry = entry()
        coEvery {
            repository.getEntry(queryId, query.cacheGeneration, canonical.hash)
        } returns entry
        coEvery { storage.getString(any()) } returns "[]"
        coEvery {
            repository.touchAccessed(queryId, query.cacheGeneration, canonical.hash)
        } throws IllegalStateException("database unavailable")

        assertEquals(true, service.get(query, parameters)?.cached)
    }

    @Test
    fun `background store publishes before its update-only pointer and does not mark caller access`() = runTest {
        val response = AnalyticsQueryResponse(listOf(JsonObject(mapOf("value" to JsonPrimitive(1)))))
        val version = slot<UUID>()
        coEvery { storage.setInputStream(any(), any(), null) } returns 12
        coEvery {
            repository.markRefreshedInBackground(
                queryId,
                query.cacheGeneration,
                canonical.hash,
                canonical.parameters,
                capture(version),
            )
        } answers { entry(objectVersion = version.captured) }
        coEvery {
            repository.deleteOverflowEntries(queryId, query.cacheGeneration, configuration.maxEntriesPerQuery)
        } returns emptyList()

        service.store(query, parameters, response, callerAccess = false)

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            storage.setInputStream(
                match { canonical.hash in it.toString() && query.cacheGeneration.toString() in it.toString() },
                any(),
                null,
            )
            repository.markRefreshedInBackground(
                queryId,
                query.cacheGeneration,
                canonical.hash,
                canonical.parameters,
                any(),
            )
        }
        coVerify(exactly = 0) { repository.markRefreshed(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.getStoredEntry(any(), any(), any()) }
    }

    @Test
    fun `invalidate deletes rows and every reachable object while isolating delete failures`() = runTest {
        val other = entry(UUID.random(), "other")
        coEvery { repository.deleteByQueryId(queryId) } returns listOf(entry(), other)
        coEvery { storage.delete(match { canonical.hash in it.toString() }) } just Runs
        coEvery { storage.delete(match { "other" in it.toString() }) } throws IllegalStateException("storage")

        service.invalidate(queryId)

        coVerify(exactly = 2) { storage.delete(any()) }
    }

    @Test
    fun `object deletion cancellation is preserved`() = runTest {
        coEvery { repository.deleteByQueryId(queryId) } returns listOf(entry())
        coEvery { storage.delete(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { service.invalidate(queryId) }
    }

    @Test
    fun `refresh entry selection and due query delegation cover both branches`() = runTest {
        val entry = entry()
        val due = listOf(queryId)
        coEvery { repository.getStaleEntries(queryId) } returns listOf(entry)
        coEvery { repository.getEntries(queryId) } returns listOf(entry)
        coEvery { repository.getDueQueryIds(configuration.refreshQueryBatchSize) } returns due

        assertEquals(listOf(entry), service.getRefreshableEntries(queryId, onlyStale = true))
        assertEquals(listOf(entry), service.getRefreshableEntries(queryId, onlyStale = false))
        assertSame(due, service.getQueryIdsDueForRefresh())
    }

    @Test
    fun `default cache configuration applies the documented refresh batch size`() = runTest {
        val defaultService = AnalyticsQueryResultCacheServiceImpl(repository, storage, json)
        coEvery { repository.getDueQueryIds(500) } returns listOf(queryId)

        assertEquals(listOf(queryId), defaultService.getQueryIdsDueForRefresh())
    }

    @Test
    fun `legacy store skips missing queries and delegates current generations`() = runTest {
        coEvery { repository.getCurrentGeneration(queryId) } returns null andThen query.cacheGeneration
        coEvery { storage.setInputStream(any(), any(), null) } returns 2
        coEvery { repository.markRefreshed(any(), any(), any(), any(), any()) } returns null
        coEvery { storage.delete(any()) } just Runs

        service.store(queryId, parameters, AnalyticsQueryResponse(emptyList()))
        service.store(queryId, parameters, AnalyticsQueryResponse(emptyList()))

        coVerify(exactly = 1) { storage.setInputStream(any(), any(), null) }
        coVerify(exactly = 1) { repository.markRefreshed(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `prune removes legacy entries first and uses the remaining bounded budget for idle entries`() = runTest {
        val legacy = entry(objectVersion = null)
        val idle = entry(hash = "idle")
        coEvery { repository.deleteLegacyEntries(configuration.pruneBatchSize) } returns
            emptyList() andThen listOf(legacy)
        coEvery {
            repository.deleteIdleEntries(configuration.idleRetentionDays, configuration.pruneBatchSize)
        } returns emptyList()
        coEvery {
            repository.deleteIdleEntries(configuration.idleRetentionDays, configuration.pruneBatchSize - 1)
        } returns listOf(idle)
        coEvery { storage.delete(any()) } just Runs

        service.pruneIdleEntries()
        service.pruneIdleEntries()

        coVerify(exactly = 2) { repository.deleteLegacyEntries(configuration.pruneBatchSize) }
        coVerify(exactly = 1) {
            repository.deleteIdleEntries(configuration.idleRetentionDays, configuration.pruneBatchSize)
        }
        coVerify(exactly = 1) {
            repository.deleteIdleEntries(configuration.idleRetentionDays, configuration.pruneBatchSize - 1)
        }
        coVerify(exactly = 2) { storage.delete(any()) }
    }

    @Test
    fun `prune does not request idle entries after legacy rows consume the batch`() = runTest {
        val legacy = List(configuration.pruneBatchSize) { entry(hash = "legacy-$it", objectVersion = null) }
        coEvery { repository.deleteLegacyEntries(configuration.pruneBatchSize) } returns legacy
        coEvery { storage.delete(any()) } just Runs

        service.pruneIdleEntries()

        coVerify(exactly = 0) { repository.deleteIdleEntries(any(), any()) }
        coVerify(exactly = configuration.pruneBatchSize) { storage.delete(any()) }
    }

    @Test
    fun `stale generation store deletes its unpublished object`() = runTest {
        coEvery { storage.setInputStream(any(), any(), null) } returns 12
        coEvery { repository.markRefreshed(any(), any(), any(), any(), any()) } returns null
        coEvery { storage.delete(any()) } just Runs

        service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)

        coVerify(exactly = 1) { storage.delete(any()) }
        coVerify(exactly = 0) { repository.deleteOverflowEntries(any(), any(), any()) }
    }

    @Test
    fun `upload failure cleans the unique unpublished path and preserves the original failure`() = runTest {
        val failure = IllegalStateException("upload failed")
        coEvery { storage.setInputStream(any(), any(), null) } throws failure
        coEvery { storage.delete(any()) } just Runs

        val thrown = assertFailsWith<IllegalStateException> {
            service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
        }

        assertSame(failure, thrown)
        coVerify(exactly = 0) { repository.getStoredEntry(any(), any(), any()) }
        coVerify(exactly = 1) { storage.delete(any()) }
    }

    @Test
    fun `upload failure retains cleanup failure as suppressed context`() = runTest {
        val failure = IllegalStateException("upload failed")
        val cleanupFailure = IllegalArgumentException("delete failed")
        coEvery { storage.setInputStream(any(), any(), null) } throws failure
        coEvery { storage.delete(any()) } throws cleanupFailure

        val thrown = assertFailsWith<IllegalStateException> {
            service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
        }

        assertSame(failure, thrown)
        assertEquals(listOf(cleanupFailure), thrown.suppressed.toList())
    }

    @Test
    fun `cancellation during upload remains primary when cleanup is also cancelled`() = runTest {
        val cancellation = CancellationException("upload cancelled")
        val cleanupCancellation = CancellationException("delete cancelled")
        coEvery { storage.setInputStream(any(), any(), null) } throws cancellation
        coEvery { storage.delete(any()) } throws cleanupCancellation

        val thrown = assertFailsWith<CancellationException> {
            service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf(cleanupCancellation), thrown.suppressed.toList())
    }

    @Test
    fun `cleanup cancellation supersedes a non-cancellation publication failure`() = runTest {
        val failure = IllegalStateException("upload failed")
        val cleanupCancellation = CancellationException("delete cancelled")
        coEvery { storage.setInputStream(any(), any(), null) } throws failure
        coEvery { storage.delete(any()) } throws cleanupCancellation

        val thrown = assertFailsWith<CancellationException> {
            service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
        }

        assertEquals(cleanupCancellation.message, thrown.message)
        assertEquals(listOf(failure), cleanupCancellation.suppressed.toList())
    }

    @Test
    fun `ambiguous mark failure retains an object that the database now references`() = runTest {
        val failure = IllegalStateException("commit acknowledgement lost")
        val version = slot<UUID>()
        coEvery {
            repository.getStoredEntry(queryId, query.cacheGeneration, canonical.hash)
        } answers { entry(objectVersion = version.captured) }
        coEvery { storage.setInputStream(any(), any(), null) } returns 2
        coEvery {
            repository.markRefreshed(any(), any(), any(), any(), capture(version))
        } throws failure

        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
            },
        )
        coVerify(exactly = 0) { storage.delete(any()) }
    }

    @Test
    fun `ambiguous mark failure retains the object when pointer verification fails`() = runTest {
        val failure = IllegalStateException("commit acknowledgement lost")
        val verificationFailure = IllegalArgumentException("verification unavailable")
        coEvery { storage.setInputStream(any(), any(), null) } returns 2
        coEvery { repository.markRefreshed(any(), any(), any(), any(), any()) } throws failure
        coEvery {
            repository.getStoredEntry(queryId, query.cacheGeneration, canonical.hash)
        } throws verificationFailure

        val thrown = assertFailsWith<IllegalStateException> {
            service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
        }

        assertSame(failure, thrown)
        assertEquals(listOf(verificationFailure), thrown.suppressed.toList())
        coVerify(exactly = 0) { storage.delete(any()) }
    }

    @Test
    fun `pointer verification cancellation supersedes a non-cancellation mark failure`() = runTest {
        val failure = IllegalStateException("commit acknowledgement lost")
        val verificationCancellation = CancellationException("verification cancelled")
        coEvery { storage.setInputStream(any(), any(), null) } returns 2
        coEvery { repository.markRefreshed(any(), any(), any(), any(), any()) } throws failure
        coEvery {
            repository.getStoredEntry(queryId, query.cacheGeneration, canonical.hash)
        } throws verificationCancellation

        val thrown = assertFailsWith<CancellationException> {
            service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
        }

        assertEquals(verificationCancellation.message, thrown.message)
        assertEquals(listOf(failure), verificationCancellation.suppressed.toList())
        coVerify(exactly = 0) { storage.delete(any()) }
    }

    @Test
    fun `mark cancellation remains primary when pointer verification is also cancelled`() = runTest {
        val cancellation = CancellationException("mark cancelled")
        val verificationCancellation = CancellationException("verification cancelled")
        coEvery { storage.setInputStream(any(), any(), null) } returns 2
        coEvery { repository.markRefreshed(any(), any(), any(), any(), any()) } throws cancellation
        coEvery {
            repository.getStoredEntry(queryId, query.cacheGeneration, canonical.hash)
        } throws verificationCancellation

        val thrown = assertFailsWith<CancellationException> {
            service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf(verificationCancellation), thrown.suppressed.toList())
        coVerify(exactly = 0) { storage.delete(any()) }
    }

    @Test
    fun `repeated pointer cancellation is not suppressed onto itself`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { storage.setInputStream(any(), any(), null) } returns 2
        coEvery { repository.markRefreshed(any(), any(), any(), any(), any()) } throws cancellation
        coEvery { repository.getStoredEntry(queryId, query.cacheGeneration, canonical.hash) } throws cancellation

        val thrown = assertFailsWith<CancellationException> {
            service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
        }

        assertSame(cancellation, thrown)
        assertEquals(emptyList(), cancellation.suppressed.toList())
    }

    @Test
    fun `repeated cleanup cancellation is not suppressed onto itself`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { storage.setInputStream(any(), any(), null) } throws cancellation
        coEvery { storage.delete(any()) } throws cancellation

        val thrown = assertFailsWith<CancellationException> {
            service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
        }

        assertSame(cancellation, thrown)
        assertEquals(emptyList(), cancellation.suppressed.toList())
    }

    @Test
    fun `failed mark inside a transaction cleans the unpublished object without pointer verification`() = runTest {
        val manager = mockk<ConnectionManager>()
        val failure = IllegalStateException("mark failed")
        every { manager.inTransaction } returns true
        coEvery { storage.setInputStream(any(), any(), null) } returns 2
        coEvery { repository.markRefreshed(any(), any(), any(), any(), any()) } throws failure
        coEvery { storage.delete(any()) } just Runs

        val thrown = assertFailsWith<IllegalStateException> {
            withContext(manager.asCoroutineContext()) {
                service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
            }
        }
        assertEquals(failure.message, thrown.message)
        coVerify(exactly = 0) { repository.getStoredEntry(any(), any(), any()) }
        coVerify(exactly = 1) { storage.delete(any()) }
    }

    @Test
    fun `failed mark outside a transaction verifies the pointer before cleanup`() = runTest {
        val manager = mockk<ConnectionManager>()
        val failure = IllegalStateException("mark failed")
        every { manager.inTransaction } returns false
        coEvery { storage.setInputStream(any(), any(), null) } returns 2
        coEvery { repository.markRefreshed(any(), any(), any(), any(), any()) } throws failure
        coEvery { repository.getStoredEntry(queryId, query.cacheGeneration, canonical.hash) } returns null
        coEvery { storage.delete(any()) } just Runs

        val thrown = assertFailsWith<IllegalStateException> {
            withContext(manager.asCoroutineContext()) {
                service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
            }
        }
        assertEquals(failure.message, thrown.message)
        coVerify(exactly = 1) { repository.getStoredEntry(queryId, query.cacheGeneration, canonical.hash) }
        coVerify(exactly = 1) { storage.delete(any()) }
    }

    @Test
    fun `invalidation does not delete a current version twice or invent a missing legacy object`() = runTest {
        val version = UUID.random()
        coEvery { repository.deleteByQueryId(queryId) } returns listOf(
            entry(objectVersion = version, supersededObjectVersion = version),
            entry(hash = "legacy", objectVersion = null, supersededLegacyObject = true),
        )
        coEvery { storage.delete(any()) } just Runs

        service.invalidate(queryId)

        coVerify(exactly = 2) { storage.delete(any()) }
    }

    @Test
    fun `failed mark deletes an object that no cache row references and preserves cancellation`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery {
            repository.getStoredEntry(queryId, query.cacheGeneration, canonical.hash)
        } returns null
        coEvery { storage.setInputStream(any(), any(), null) } returns 2
        coEvery { repository.markRefreshed(any(), any(), any(), any(), any()) } throws cancellation
        coEvery { storage.delete(any()) } just Runs

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
            },
        )
        coVerify(exactly = 1) { repository.getStoredEntry(queryId, query.cacheGeneration, canonical.hash) }
        coVerify(exactly = 1) { storage.delete(any()) }
    }

    @Test
    fun `transaction rollback removes the object published by a successful pointer swap`() = runTest {
        val manager = mockk<ConnectionManager>()
        val callbacks = mutableListOf<ConnectionManagerCallback>()
        val version = slot<UUID>()
        every { manager.inTransaction } returns true
        every { manager.addCallback(capture(callbacks)) } just Runs
        coEvery { storage.setInputStream(any(), any(), null) } returns 2
        coEvery {
            repository.markRefreshed(any(), any(), any(), any(), capture(version))
        } answers { entry(objectVersion = version.captured) }
        coEvery {
            repository.deleteOverflowEntries(queryId, query.cacheGeneration, configuration.maxEntriesPerQuery)
        } returns emptyList()
        coEvery { storage.delete(any()) } just Runs

        withContext(manager.asCoroutineContext()) {
            service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)
        }
        callbacks.forEach { it.onCommit() }
        callbacks.forEach { it.onRelease() }
        callbacks.forEach { it.onRollback() }

        coVerify(exactly = 1) {
            storage.delete(match { version.captured.toString() in it.toString() })
        }
    }

    @Test
    fun `store removes superseded and overflow objects only after the row points at the replacement`() = runTest {
        val concurrentlySuperseded = UUID.random()
        val overflow = entry(hash = "overflow")
        val version = slot<UUID>()
        coEvery { storage.setInputStream(any(), any(), null) } returns 2
        coEvery {
            repository.markRefreshed(any(), any(), any(), any(), capture(version))
        } answers {
            entry(
                objectVersion = version.captured,
                supersededObjectVersion = concurrentlySuperseded,
            )
        }
        coEvery {
            repository.deleteOverflowEntries(queryId, query.cacheGeneration, configuration.maxEntriesPerQuery)
        } returns listOf(overflow)
        coEvery { storage.delete(any()) } just Runs

        service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)

        coVerify(exactly = 2) { storage.delete(any()) }
        coVerify(exactly = 1) {
            storage.delete(match { concurrentlySuperseded.toString() in it.toString() })
        }
    }

    @Test
    fun `first hardened replacement removes the legacy unversioned object without a pre-read`() = runTest {
        val version = slot<UUID>()
        coEvery { storage.setInputStream(any(), any(), null) } returns 2
        coEvery {
            repository.markRefreshed(any(), any(), any(), any(), capture(version))
        } answers {
            entry(
                objectVersion = version.captured,
                supersededLegacyObject = true,
            )
        }
        coEvery {
            repository.deleteOverflowEntries(queryId, query.cacheGeneration, configuration.maxEntriesPerQuery)
        } returns emptyList()
        coEvery { storage.delete(any()) } just Runs

        service.store(query, parameters, AnalyticsQueryResponse(emptyList()), callerAccess = true)

        coVerify(exactly = 0) { repository.getStoredEntry(any(), any(), any()) }
        coVerify(exactly = 1) {
            storage.delete(match { it.toString().endsWith("/${canonical.hash}.json") })
        }
    }
}
