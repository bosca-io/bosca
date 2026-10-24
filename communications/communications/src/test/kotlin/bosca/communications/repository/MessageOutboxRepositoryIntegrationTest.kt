@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.communications.repository

import bosca.communications.configuration.CommunicationsMigration
import bosca.communications.configuration.JobQueueNames
import bosca.communications.jobs.MessageOutboxDeliveryExecutor
import bosca.communications.jobs.MessageOutboxDeliveryExecutorProvider
import bosca.communications.service.MessageOutboxService
import bosca.communications.service.MessageOutboxServiceImplProvider
import bosca.communications.service.MessageService
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provide
import bosca.di.provides
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.serialize
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MessageOutboxRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_communications_message_outbox_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 3,
                ),
                key = "communications-message-outbox-test",
            ),
        )
        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    private val json = Json
    private val repository = MessageOutboxRepositoryImpl()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(CommunicationsMigration())) }
            schemaInitialized = true
        }
        withDb {
            transaction {
                connection().useStatement("delete from communications.message_outbox_batches") {
                    it.execute()
                }
            }
        }
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `source claim atomically persists the first complete delivery snapshot`() = withDb {
        val sourceId = UUID.random()
        val recipients = List(2) { UUID.random() }
        val firstSnapshot = listOf(
            Message(channels = listOf(MessageChannel.EMAIL), recipients = listOf(recipients[0])),
            Message(channels = listOf(MessageChannel.PUSH), recipients = listOf(recipients[1])),
        )
        val replacement = listOf(
            Message(channels = listOf(MessageChannel.PUSH), recipients = listOf(UUID.random())),
        )

        transaction {
            val created = repository.create(sourceId, encode(firstSnapshot))
            assertEquals(firstSnapshot, created.map { it.message })
            assertEquals(listOf(0, 1), created.map { it.position })

            assertEquals(emptyList(), repository.create(sourceId, encode(replacement)))
            assertEquals(firstSnapshot, repository.getBySource(sourceId).map { it.message })
        }
    }

    @Test
    fun `pending lookup and completion are idempotent`() = withDb {
        val sourceId = UUID.random()
        val created = transaction {
            repository.create(
                sourceId,
                encode(listOf(Message(
                    channels = listOf(MessageChannel.PUSH),
                    recipients = listOf(UUID.random()),
                ))),
            ).single()
        }

        assertEquals(created.message, repository.getPending(created.id)?.message)
        assertEquals(1, transaction { repository.markSent(created.id) })
        assertEquals(0, transaction { repository.markSent(created.id) })
        assertNull(repository.getPending(created.id))
        assertEquals(0, transaction { repository.markFailure(created.id, "late failure") })
        assertEquals(1, repository.getBySource(sourceId).single().attempts)
    }

    @Test
    fun `terminal failure retains diagnostics and cannot be updated or recovered`() = withDb {
        val sourceId = UUID.random()
        val created = transaction {
            repository.create(sourceId, encode(listOf(Message(
                channels = listOf(MessageChannel.PUSH), recipients = listOf(UUID.random()),
            )))).single()
        }
        repeat(9) { attempt ->
            assertEquals(1, transaction { repository.markFailure(created.id, "render failed") })
            assertEquals(attempt + 1, assertNotNull(repository.getPending(created.id)).attempts)
        }
        assertEquals(1, transaction { repository.markFailure(created.id, "render failed") })
        val failed = repository.getBySource(sourceId).single()
        assertNull(failed.sentAt)
        assertEquals(10, failed.attempts)
        assertEquals("render failed", failed.lastError)

        assertNull(repository.getPending(created.id))
        assertEquals(emptyList(), repository.getPending(200))
        assertEquals(0, transaction { repository.markFailure(created.id, "late retry") })
        assertEquals(0, transaction { repository.markSent(created.id) })
        assertEquals(failed, repository.getBySource(sourceId).single())
    }

    @Test
    fun `exhausted NATS delivery retries close the outbox and cannot be restarted`() =
        verifyExhaustedNatsRetries(rejectTerminalWrite = false)

    @Test
    fun `failed final write retains outbox attempts when NATS recovery recreates the job`() =
        verifyExhaustedNatsRetries(rejectTerminalWrite = true)

    private fun verifyExhaustedNatsRetries(rejectTerminalWrite: Boolean) = withDb {
        val nats = SharedNatsContainer()
        nats.start()
        try {
            val natsPool = nats.newConnectionPool(1)
            val queue = NatsJobQueueFactory(
                natsPool, json, NatsDistributedLockFactory(natsPool), null, emptyList(), processExpiredJobs = false,
            ).create("outbox-${UUID.random()}")
            ProviderRegistry.register(MessageOutboxRepository::class, MessageOutboxRepositoryProvider(), true)
            ProviderRegistry.register(MessageOutboxService::class, MessageOutboxServiceImplProvider(), true)
            ProviderRegistry.register(MessageOutboxDeliveryExecutor::class, MessageOutboxDeliveryExecutorProvider(), true)
            provides<CacheManager> { mockk(relaxed = true) }
            provides<RequestCacheSerializer> { mockk(relaxed = true) }
            provides<JobQueue>(name = JobQueueNames.messagesJobQueue) { queue }
            val messages = mockk<MessageService>()
            coEvery { messages.sendNow(any()) } throws IllegalStateException("render failed")
            provides<MessageService> { messages }
            val outbox = provide<MessageOutboxService>()
            val sourceId = UUID.random()
            val message = Message(channels = listOf(MessageChannel.PUSH), recipients = listOf(UUID.random()))
            outbox.enqueueOnce(sourceId, message)
            val id = repository.getBySource(sourceId).single().id
            val state = natsPool.systemConnection().keyValue("job-state-${queue.name}")
            val maxFailures = queue.getJob(id) { assertNotNull(it).serialize().maxFailures }

            if (rejectTerminalWrite) {
                connection().useStatement("""
                    CREATE FUNCTION communications.reject_final_attempt() RETURNS trigger
                    LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'simulated attempt write failure'; END; $$;
                    CREATE TRIGGER reject_final_attempt BEFORE UPDATE OF attempts
                    ON communications.message_outbox FOR EACH ROW WHEN (NEW.attempts = 10)
                    EXECUTE FUNCTION communications.reject_final_attempt();
                """.trimIndent()) { it.execute() }
            }

            // Drive the real queue's failure transitions without waiting through retry backoff.
            repeat(maxFailures) { attempt ->
                queue.getJob(id) { stored ->
                    val job = assertNotNull(stored)
                    withContext(queue.asCoroutineContext(job)) {
                        val failure = assertFailsWith<IllegalStateException> { job.newExecutor().execute() }
                        assertEquals("render failed", failure.message)
                        if (rejectTerminalWrite && attempt + 1 == maxFailures) {
                            assertTrue(failure.suppressed.single().message.orEmpty().contains("simulated attempt write failure"))
                        }
                        queue.markFailed(job, failure, retry = true)
                    }
                }
                val entry = repository.getBySource(sourceId).single()
                val expectedAttempts = if (rejectTerminalWrite && attempt + 1 == maxFailures) attempt else attempt + 1
                assertEquals(expectedAttempts, entry.attempts)
                if (attempt + 1 < maxFailures) {
                    assertNotNull(repository.getPending(id))
                    assertEquals(1, outbox.recoverPending())
                    queue.getJob(id) { assertEquals(attempt + 1, assertNotNull(it).serialize().failures) }
                }
            }

            if (rejectTerminalWrite) {
                assertEquals(maxFailures - 1, repository.getBySource(sourceId).single().attempts)
                assertNull(state.get(id.toString()))
                assertEquals(1, outbox.recoverPending())
                queue.getJob(id) { assertEquals(0, assertNotNull(it).serialize().failures) }
                connection().useStatement("DROP TRIGGER reject_final_attempt ON communications.message_outbox") {
                    it.execute()
                }

                queue.getJob(id) { stored ->
                    val job = assertNotNull(stored)
                    withContext(queue.asCoroutineContext(job)) {
                        val failure = assertFailsWith<IllegalStateException> { job.newExecutor().execute() }
                        assertEquals("render failed", failure.message)
                        assertNull(repository.getPending(id))
                        queue.markFailed(job, failure, retry = true)
                    }
                }
                // A queue redelivery is now a no-op even though its own retry counter restarted.
                queue.getJob(id) { stored ->
                    val job = assertNotNull(stored)
                    withContext(queue.asCoroutineContext(job)) {
                        job.newExecutor().execute()
                        queue.markComplete(job)
                    }
                }
            }

            val failed = repository.getBySource(sourceId).single()
            assertNull(failed.sentAt)
            assertTrue(failed.lastError.orEmpty().contains("render failed"))
            assertNull(state.get(id.toString()))
            assertEquals(0, outbox.recoverPending())
            outbox.enqueueOnce(sourceId, message)
            outbox.deliver(id)
            assertNull(state.get(id.toString()))
            val expectedAttempts = maxFailures + if (rejectTerminalWrite) 1 else 0
            assertEquals(maxFailures, failed.attempts)
            coVerify(exactly = expectedAttempts) { messages.sendNow(any()) }
        } finally {
            try {
                if (rejectTerminalWrite) {
                    connection().useStatement("DROP FUNCTION IF EXISTS communications.reject_final_attempt() CASCADE") {
                        it.execute()
                    }
                }
            } finally {
                nats.stop()
            }
        }
    }

    private fun encode(messages: List<Message>): String =
        json.encodeToString(ListSerializer(Message.serializer()), messages)

    private fun <T> withDb(block: suspend () -> T): T = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }
}
