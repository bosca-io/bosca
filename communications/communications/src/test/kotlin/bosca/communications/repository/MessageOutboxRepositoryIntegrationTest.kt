@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.communications.repository

import bosca.communications.configuration.CommunicationsMigration
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
