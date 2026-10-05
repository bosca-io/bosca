@file:OptIn(bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)

package bosca.analytics

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.jobs.SqlJob
import bosca.analytics.jobs.SqlJobExecutor
import bosca.analytics.model.Event
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.repository.ErrorGroupEventRepository
import bosca.analytics.repository.EventRepository
import bosca.analytics.repository.fallback.DefaultEventRepository
import bosca.analytics.service.ErrorGroupService
import bosca.analytics.service.EventProcessingServiceImpl
import bosca.analytics.transform.EventPipelineTransform
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.Headers
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.sql.PreparedStatement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InfrastructureCoverageTest {
    private val events = Events(
        events = listOf(Event(created = 1, type = EventType.Session)),
        sent = 2,
        sentMicros = 3,
    )

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `fallback and error-group repositories implement process and flush contracts`() = runTest {
        val fallback = DefaultEventRepository()
        fallback.process(events)
        fallback.flush()

        val errors = mockk<ErrorGroupService>()
        coJustRun { errors.recordBatch(events) }
        val repository = ErrorGroupEventRepository(errors)
        repository.process(events)
        repository.flush()
        coVerify { errors.recordBatch(events) }
    }

    @Test
    fun `event processing preserves cancellation and drains through shutdown hook`() = runTest {
        val cacheManager = mockk<CacheManager>(relaxed = true)
        val connectionPool = mockk<ConnectionPool>()
        val connection = mockk<ConnectionManager>(relaxed = true)
        coEvery { connectionPool.connection() } returns connection
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<ConnectionPool> { connectionPool }

        val hook = slot<suspend () -> Unit>()
        val application = mockk<BoscaApplication>()
        every { application.onShutdown(capture(hook)) } just Runs
        val repository = mockk<EventRepository>()
        coJustRun { repository.process(any()) }
        coJustRun { repository.flush() }
        val cancellation = object : EventPipelineTransform {
            override suspend fun transform(context: EventPipelineContext, events: Events): Events =
                throw CancellationException("cancel")
        }
        val service = EventProcessingServiceImpl(
            application,
            repository,
            EventPipelineTransforms(listOf(cancellation)),
            EventProcessingConfiguration(workerCount = 1),
        )

        assertFailsWith<CancellationException> {
            service.processRequest(EventPipelineContext(Headers.Empty), events)
        }
        hook.captured.invoke()
        coVerify { repository.flush() }
    }

    @Test
    fun `SQL job executes its serialized statement with the admin pool`() = runTest {
        val json = Json
        val pool = mockk<ConnectionPool>()
        val connection = mockk<ConnectionManager>(relaxed = true)
        val statement = mockk<PreparedStatement>(relaxed = true)
        coEvery { pool.connection() } returns connection
        coEvery { connection.useStatement(any(), any<suspend (PreparedStatement) -> Any>()) } coAnswers {
            secondArg<suspend (PreparedStatement) -> Any>().invoke(statement)
        }
        provides<Json> { json }
        provides<ConnectionPool>(name = "trino-admin") { pool }
        val definition = SqlJob("select 42")
        val job = InternalJobConstructor(
            json.encodeToJsonElement(SqlJob.serializer(), definition),
            SqlJobExecutor::class,
        )
        val queue = mockk<JobQueue>()

        withContext(queue.asCoroutineContext(job)) { SqlJobExecutor().execute() }

        verify { statement.execute() }
        coVerify { connection.useStatement("select 42", any<suspend (PreparedStatement) -> Any>()) }
    }
}
