@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ai.chat.repository

import bosca.ai.chat.model.ChatHistoryMessage
import bosca.db.*
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.*
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

class ChatHistoryStreamingIntegrationTest {
    @Test
    fun `stream updates one row preserves ordering and cannot overwrite another conversation`(): Unit = runBlocking {
        val postgres = SharedPostgreSQLContainer().withReuse(true)
        postgres.start()
        val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(postgres.jdbcUrl, postgres.username,
            postgres.password, maxConnections = 2), key = "chat-streaming"))
        ProviderRegistry.clear()
        provides<ConnectionPool> { pool }
        provides<Json> { Json { serializersModule = SerializersModule {
            contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer())
        } } }
        try {
            FlywayMigration(pool).migrate(listOf(CoreMigration(), AiMigration()))
            val principal = UUID.random()
            withConnectionManager { transaction {
                connection().useStatement("insert into principals (id) values (?)") {
                    it.setObject(1, java.util.UUID.fromString(principal.toString())); it.executeUpdate()
                }
                val repository = ChatHistoryRepositoryImpl()
                val session = repository.createSession(UUID.random(), principal, null, "kit", "Streaming", JsonObject(emptyMap()))
                val other = repository.createSession(UUID.random(), principal, null, "kit", "Other", JsonObject(emptyMap()))
                val message = ChatHistoryMessage(sessionId = session.id, author = "kit", event = JsonPrimitive("first"))
                val first = assertNotNull(repository.upsertMessage(message))
                repeat(100) { revision ->
                    val updated = assertNotNull(repository.upsertMessage(message.copy(event = buildJsonObject { put("revision", revision) })))
                    assertEquals(first.created, updated.created)
                }
                val final = repository.getMessages(session.id).single()
                assertEquals(message.id, final.id)
                assertEquals(99, final.event.jsonObject.getValue("revision").jsonPrimitive.int)
                assertNull(repository.upsertMessage(message.copy(sessionId = other.id)))
                assertNull(repository.upsertMessage(message.copy(author = "user")))
                assertTrue(repository.getMessages(other.id).isEmpty())
                assertEquals(final, repository.getMessages(session.id).single())
                val before = OffsetDateTime.now().withNano(0)
                val earlier = message.copy(id = UUID.random(), author = "User", created = before.minusSeconds(5))
                repository.addMessage(earlier)
                repository.upsertMessage(earlier.copy(id = UUID.random(), author = "Assistant", created = before.minusSeconds(2)))
                repository.upsertMessage(earlier.copy(id = UUID.random(), created = before))
                repository.upsertMessage(earlier.copy(id = UUID.random(), created = before.plusSeconds(1)))
                val recent = repository.getRecentMessages(session.id, before, 1)
                assertEquals(1, recent.size)
                assertEquals("Assistant", recent.single().author)
                assertEquals(2, repository.getRecentMessages(session.id, before, 30).size)
                assertTrue(repository.getRecentMessages(other.id, before, 30).isEmpty())
            } }
        } finally {
            ProviderRegistry.clear()
            pool.close()
            postgres.stop()
        }
    }
}
