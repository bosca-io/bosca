@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.communications.jobs

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.communications.configuration.CommunicationsMigration
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatusType
import bosca.communications.repository.DeliveryEventRepositoryImpl
import bosca.communications.repository.DeliveryStatusRepositoryImpl
import bosca.communications.repository.SuppressionListRepositoryImpl
import bosca.communications.service.DeliveryTrackingService
import bosca.communications.service.DeliveryTrackingServiceImpl
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.enqueue
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import bosca.test.resources.SharedNatsContainer
import org.testcontainers.containers.wait.strategy.Wait
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Proves the provider-accepted status handoff across the production queue boundary:
 * real NATS JetStream consumes the job through [JobRunner], the registered executor
 * deserializes it, and real PostgreSQL advances the recipient aggregate to SENT.
 */
class DeliveryStatusJobRunnerEndToEndTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_delivery_job_test")
            withReuse(true)
            start()
        }
        private val nats = SharedNatsContainer().apply {
            withExposedPorts(4222)
            withCommand("-js")
            withReuse(true)
            waitingFor(Wait.forListeningPort())
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "delivery-job-test",
            ),
        )
        private val natsPool = nats.newConnectionPool(2)
        private val lockFactory = NatsDistributedLockFactory(natsPool)
        private var schemaInitialized = false

        @JvmStatic
        @AfterClass
        fun shutdown() {
            runBlocking {
                natsPool.close()
                pool.close()
            }
            nats.stop()
            postgres.stop()
        }
    }

    private val tracking = DeliveryTrackingServiceImpl(
        DeliveryEventRepositoryImpl(),
        DeliveryStatusRepositoryImpl(),
        SuppressionListRepositoryImpl(),
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        provides<DeliveryTrackingService>(singleton = true) { tracking }
        provides<RecordDeliveryEventsJobExecutor> { RecordDeliveryEventsJobExecutor(tracking) }

        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(CommunicationsMigration())) }
            schemaInitialized = true
        }
        runBlocking {
            withDb {
                bosca.db.transaction {
                    connection().useStatement("DELETE FROM communications.delivery_events") { it.execute() }
                    connection().useStatement("DELETE FROM communications.delivery_status") { it.execute() }
                }
            }
        }
    }

    @Test
    fun `real queue runner persists provider acceptance`() = runBlocking {
        val messageId = UUID.random()
        val recipientId = UUID.random()
        withDb {
            tracking.recordEvent(DeliveryEvent(
                messageId = messageId,
                recipientId = recipientId,
                status = DeliveryStatusType.PENDING,
            ))
        }

        val queue = NatsJobQueueFactory(
            natsPool,
            Json,
            lockFactory,
            enqueueEventChannel = null,
            enqueueCallbacks = emptyList(),
            processExpiredJobs = false,
        ).create("delivery-status-${UUID.random().toString().substring(0, 8)}")
        RecordDeliveryEventsJob(listOf(
            DeliveryEvent(
                providerEventId = "accepted-$messageId-$recipientId",
                messageId = messageId,
                recipientId = recipientId,
                status = DeliveryStatusType.SENT,
            ),
        )).enqueue(queue, RecordDeliveryEventsJobExecutor::class)

        val runner = JobRunner(queue, 1, lockFactory)
        runner.run()
        try {
            withTimeout(60.seconds) {
                while (withDb { tracking.getStatus(messageId, recipientId)?.status } != DeliveryStatusType.SENT) {
                    delay(50)
                }
            }
        } finally {
            runner.shutdown()
        }

        withDb {
            val status = tracking.getStatus(messageId, recipientId)
            assertEquals(DeliveryStatusType.SENT, status?.status)
            assertEquals(1, status?.attempts)
            assertTrue(tracking.getEventsForRecipient(recipientId).map(DeliveryEvent::status).containsAll(
                listOf(DeliveryStatusType.PENDING, DeliveryStatusType.SENT),
            ))
        }
    }

    private suspend fun <T> withDb(block: suspend () -> T): T {
        val manager = pool.connection()
        return try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }
}
