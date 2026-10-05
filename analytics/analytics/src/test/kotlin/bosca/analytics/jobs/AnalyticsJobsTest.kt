@file:OptIn(
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package bosca.analytics.jobs

import bosca.analytics.model.AnalyticsQueryCacheEntry
import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryGitSyncService
import bosca.analytics.service.AnalyticsQueryResultCacheService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.SerializationException
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalyticsJobsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val queue = mockk<JobQueue>(relaxed = true)
    private val refreshLock = mockk<DistributedLock>()
    private val lockFactory = mockk<DistributedLockFactory>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<JobQueue>(name = "analytics", singleton = true) { queue }
        coEvery { queue.enqueue(any()) } returns UUID.random()
        coEvery { lockFactory.create(any()) } returns refreshLock
        coEvery { refreshLock.tryAcquire(AnalyticsQueryRefreshExecutor.REFRESH_LOCK_TTL_MILLIS) } returns true
        coEvery { refreshLock.release() } returns true
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun <T : bosca.queue.annotations.IJobDefinition> job(
        definition: T,
        serializer: KSerializer<T>,
        executor: KClass<out JobExecutor>,
    ) = InternalJobConstructor(
        definition = json.encodeToJsonElement(serializer, definition),
        executor = executor,
    )

    private fun cacheEntry(queryId: UUID, parameters: List<AnalyticsQueryExecutionParameterInput>) =
        AnalyticsQueryCacheEntry(
            queryId = queryId,
            parametersHash = "hash",
            parameters = json.encodeToJsonElement(
                ListSerializer(AnalyticsQueryExecutionParameterInput.serializer()),
                parameters,
            ),
            lastRefreshedAt = OffsetDateTime.now(),
            lastAccessedAt = OffsetDateTime.now(),
        )

    @Test
    fun `refresh executor is a no-op when no combinations are tracked`() = runTest {
        val queryId = UUID.random()
        val cache = mockk<AnalyticsQueryResultCacheService>()
        val execution = mockk<AnalyticsQueryExecutionService>()
        coEvery { cache.getRefreshableEntries(queryId, false) } returns emptyList()
        val executor = AnalyticsQueryRefreshExecutor(cache, execution, json, lockFactory)

        withContext(queue.asCoroutineContext(job(AnalyticsQueryRefreshJob(queryId), AnalyticsQueryRefreshJob.serializer(), AnalyticsQueryRefreshExecutor::class))) {
            executor.execute()
        }

        coVerify(exactly = 0) { execution.refresh(any(), any()) }
    }

    @Test
    fun `refresh executor refreshes valid combinations and skips independent failures`() = runTest {
        val queryId = UUID.random()
        val parameters = listOf(AnalyticsQueryExecutionParameterInput("limit", JsonPrimitive(5)))
        val valid = cacheEntry(queryId, parameters)
        val invalid = valid.copy(parametersHash = "invalid", parameters = JsonPrimitive("invalid"))
        val cache = mockk<AnalyticsQueryResultCacheService>()
        val execution = mockk<AnalyticsQueryExecutionService>()
        coEvery { cache.getRefreshableEntries(queryId, true) } returns listOf(invalid, valid, valid.copy(parametersHash = "failed"))
        coEvery { execution.refresh(queryId, any()) } returns AnalyticsQueryResponse(emptyList()) andThenThrows IllegalStateException("trino failed")
        val executor = AnalyticsQueryRefreshExecutor(cache, execution, json, lockFactory)

        withContext(queue.asCoroutineContext(job(AnalyticsQueryRefreshJob(queryId, onlyStale = true), AnalyticsQueryRefreshJob.serializer(), AnalyticsQueryRefreshExecutor::class))) {
            executor.execute()
        }

        coVerify(exactly = 2) { execution.refresh(queryId, any()) }
    }

    @Test
    fun `refresh executor preserves cancellation`() = runTest {
        val queryId = UUID.random()
        val parameters = listOf(AnalyticsQueryExecutionParameterInput("limit", JsonPrimitive(5)))
        val cache = mockk<AnalyticsQueryResultCacheService>()
        val execution = mockk<AnalyticsQueryExecutionService>()
        coEvery { cache.getRefreshableEntries(queryId, false) } returns listOf(cacheEntry(queryId, parameters))
        coEvery { execution.refresh(queryId, any()) } throws CancellationException("cancelled")
        val executor = AnalyticsQueryRefreshExecutor(cache, execution, json, lockFactory)

        assertFailsWith<CancellationException> {
            withContext(queue.asCoroutineContext(job(AnalyticsQueryRefreshJob(queryId), AnalyticsQueryRefreshJob.serializer(), AnalyticsQueryRefreshExecutor::class))) {
                executor.execute()
            }
        }
    }

    @Test
    fun `refresh executor skips duplicate work when another process holds the query lock`() = runTest {
        val queryId = UUID.random()
        val cache = mockk<AnalyticsQueryResultCacheService>()
        val execution = mockk<AnalyticsQueryExecutionService>()
        coEvery { refreshLock.tryAcquire(AnalyticsQueryRefreshExecutor.REFRESH_LOCK_TTL_MILLIS) } returns false

        val executor = AnalyticsQueryRefreshExecutor(cache, execution, json, lockFactory)
        withContext(
            queue.asCoroutineContext(
                job(AnalyticsQueryRefreshJob(queryId), AnalyticsQueryRefreshJob.serializer(), AnalyticsQueryRefreshExecutor::class),
            ),
        ) {
            executor.execute()
        }

        coVerify(exactly = 0) { cache.getRefreshableEntries(any(), any()) }
        coVerify(exactly = 0) { refreshLock.release() }
    }

    @Test
    fun `refresh executor does not retry completed work when lock release fails`() = runTest {
        val queryId = UUID.random()
        val cache = mockk<AnalyticsQueryResultCacheService>()
        val execution = mockk<AnalyticsQueryExecutionService>()
        coEvery { cache.getRefreshableEntries(queryId, false) } returns emptyList()
        coEvery { refreshLock.release() } throws IllegalStateException("lock backend unavailable")
        val executor = AnalyticsQueryRefreshExecutor(cache, execution, json, lockFactory)

        withContext(
            queue.asCoroutineContext(
                job(
                    AnalyticsQueryRefreshJob(queryId),
                    AnalyticsQueryRefreshJob.serializer(),
                    AnalyticsQueryRefreshExecutor::class,
                ),
            ),
        ) {
            executor.execute()
        }

        coVerify(exactly = 1) { refreshLock.release() }
    }

    @Test
    fun `refresh executor records a lock that was already lost at release`() = runTest {
        val queryId = UUID.random()
        val cache = mockk<AnalyticsQueryResultCacheService>()
        val execution = mockk<AnalyticsQueryExecutionService>()
        coEvery { cache.getRefreshableEntries(queryId, false) } returns emptyList()
        coEvery { refreshLock.release() } returns false
        val executor = AnalyticsQueryRefreshExecutor(cache, execution, json, lockFactory)

        withContext(
            queue.asCoroutineContext(
                job(
                    AnalyticsQueryRefreshJob(queryId),
                    AnalyticsQueryRefreshJob.serializer(),
                    AnalyticsQueryRefreshExecutor::class,
                ),
            ),
        ) {
            executor.execute()
        }

        coVerify(exactly = 1) { refreshLock.release() }
    }

    @Test
    fun `refresh executor renews its lock while a query is still running`() = runTest {
        val queryId = UUID.random()
        val parameters = listOf(AnalyticsQueryExecutionParameterInput("limit", JsonPrimitive(5)))
        val cache = mockk<AnalyticsQueryResultCacheService>()
        val execution = mockk<AnalyticsQueryExecutionService>()
        coEvery {
            cache.getRefreshableEntries(queryId, false)
        } returns listOf(cacheEntry(queryId, parameters))
        coEvery { refreshLock.renew(AnalyticsQueryRefreshExecutor.REFRESH_LOCK_TTL_MILLIS) } returns true
        coEvery { execution.refresh(queryId, any()) } coAnswers {
            delay(AnalyticsQueryRefreshExecutor.REFRESH_LOCK_RENEW_MILLIS + 1)
            AnalyticsQueryResponse(emptyList())
        }
        val executor = AnalyticsQueryRefreshExecutor(cache, execution, json, lockFactory)

        withContext(
            queue.asCoroutineContext(
                job(
                    AnalyticsQueryRefreshJob(queryId),
                    AnalyticsQueryRefreshJob.serializer(),
                    AnalyticsQueryRefreshExecutor::class,
                ),
            ),
        ) {
            executor.execute()
        }

        coVerify(atLeast = 1) {
            refreshLock.renew(AnalyticsQueryRefreshExecutor.REFRESH_LOCK_TTL_MILLIS)
        }
    }

    @Test
    fun `refresh executor fails when its in-flight lock cannot be renewed`() = runTest {
        val queryId = UUID.random()
        val parameters = listOf(AnalyticsQueryExecutionParameterInput("limit", JsonPrimitive(5)))
        val cache = mockk<AnalyticsQueryResultCacheService>()
        val execution = mockk<AnalyticsQueryExecutionService>()
        coEvery { cache.getRefreshableEntries(queryId, false) } returns listOf(cacheEntry(queryId, parameters))
        coEvery { refreshLock.renew(AnalyticsQueryRefreshExecutor.REFRESH_LOCK_TTL_MILLIS) } returns false
        coEvery { execution.refresh(queryId, any()) } coAnswers {
            delay(AnalyticsQueryRefreshExecutor.REFRESH_LOCK_RENEW_MILLIS + 1)
            AnalyticsQueryResponse(emptyList())
        }
        val executor = AnalyticsQueryRefreshExecutor(cache, execution, json, lockFactory)

        assertFailsWith<IllegalStateException> {
            withContext(
                queue.asCoroutineContext(
                    job(
                        AnalyticsQueryRefreshJob(queryId),
                        AnalyticsQueryRefreshJob.serializer(),
                        AnalyticsQueryRefreshExecutor::class,
                    ),
                ),
            ) {
                executor.execute()
            }
        }
        coVerify(atLeast = 1) { refreshLock.renew(AnalyticsQueryRefreshExecutor.REFRESH_LOCK_TTL_MILLIS) }
    }

    @Test
    fun `refresh sweep prunes and returns when no query is due`() = runTest {
        val cache = mockk<AnalyticsQueryResultCacheService>()
        coEvery { cache.pruneIdleEntries() } just Runs
        coEvery { cache.getQueryIdsDueForRefresh() } returns emptyList()

        withContext(queue.asCoroutineContext(job(AnalyticsQueryRefreshSweepJob(), AnalyticsQueryRefreshSweepJob.serializer(), AnalyticsQueryRefreshSweepExecutor::class))) {
            AnalyticsQueryRefreshSweepExecutor(cache).execute()
        }

        coVerify(exactly = 1) { cache.pruneIdleEntries() }
        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `refresh sweep enqueues one stale-only job per due query`() = runTest {
        val ids = listOf(UUID.random(), UUID.random())
        val cache = mockk<AnalyticsQueryResultCacheService>()
        coEvery { cache.pruneIdleEntries() } just Runs
        coEvery { cache.getQueryIdsDueForRefresh() } returns ids

        withContext(queue.asCoroutineContext(job(AnalyticsQueryRefreshSweepJob(), AnalyticsQueryRefreshSweepJob.serializer(), AnalyticsQueryRefreshSweepExecutor::class))) {
            AnalyticsQueryRefreshSweepExecutor(cache).execute()
        }

        coVerify(exactly = 2) { queue.enqueue(any()) }
    }

    @Test
    fun `sync executor delegates every pushed revision field`() = runTest {
        val definition = AnalyticsQuerySyncJob(UUID.random(), "refs/heads/main", "before", "after")
        val service = mockk<AnalyticsQueryGitSyncService>()
        coEvery { service.onPushEvent(any(), any(), any(), any()) } just Runs

        withContext(queue.asCoroutineContext(job(definition, AnalyticsQuerySyncJob.serializer(), AnalyticsQuerySyncExecutor::class))) {
            AnalyticsQuerySyncExecutor(service).execute()
        }

        coVerify(exactly = 1) {
            service.onPushEvent(definition.repositoryId, definition.ref, definition.beforeSha, definition.afterSha)
        }
    }

    @Test
    fun `job payload defaults and explicit values round trip`() {
        val id = UUID.random()
        assertEquals(false, AnalyticsQueryRefreshJob(id).onlyStale)
        assertEquals(true, AnalyticsQueryRefreshJob(id, onlyStale = true).onlyStale)
        assertEquals("select 1", SqlJob("select 1").sql)

        val omitDefaults = Json { encodeDefaults = false }
        val defaultRefresh = omitDefaults.encodeToJsonElement(
            AnalyticsQueryRefreshJob.serializer(),
            AnalyticsQueryRefreshJob(id),
        ) as JsonObject
        assertEquals(false, "onlyStale" in defaultRefresh)
        val explicitRefresh = omitDefaults.encodeToJsonElement(
            AnalyticsQueryRefreshJob.serializer(),
            AnalyticsQueryRefreshJob(id, onlyStale = true),
        ) as JsonObject
        assertEquals(true, "onlyStale" in explicitRefresh)
        assertEquals(
            id,
            omitDefaults.decodeFromJsonElement(AnalyticsQueryRefreshJob.serializer(), explicitRefresh).queryId,
        )

        val sync = AnalyticsQuerySyncJob(id, "main", "before", "after")
        assertEquals(
            sync,
            json.decodeFromString(
                AnalyticsQuerySyncJob.serializer(),
                json.encodeToString(AnalyticsQuerySyncJob.serializer(), sync),
            ),
        )
        assertEquals(
            "select 1",
            json.decodeFromString(SqlJob.serializer(), json.encodeToString(SqlJob.serializer(), SqlJob("select 1"))).sql,
        )
        assertEquals(
            AnalyticsQueryRefreshSweepJob.serializer().descriptor.serialName,
            json.decodeFromString(
                AnalyticsQueryRefreshSweepJob.serializer(),
                json.encodeToString(AnalyticsQueryRefreshSweepJob.serializer(), AnalyticsQueryRefreshSweepJob()),
            ).let { AnalyticsQueryRefreshSweepJob.serializer().descriptor.serialName },
        )
        assertFailsWith<SerializationException> { json.decodeFromString(SqlJob.serializer(), "{}") }
        assertFailsWith<SerializationException> { json.decodeFromString(AnalyticsQuerySyncJob.serializer(), "{}") }
        assertFailsWith<SerializationException> { json.decodeFromString(AnalyticsQueryRefreshJob.serializer(), "{}") }
        assertEquals(
            id,
            Json { ignoreUnknownKeys = true }.decodeFromString(
                AnalyticsQueryRefreshJob.serializer(),
                "{\"queryId\":\"$id\",\"unknown\":1}",
            ).queryId,
        )
    }
}
