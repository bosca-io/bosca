package bosca.chat.message

import bosca.chat.state.*
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.db.*
import bosca.pubsub.PostgresPubSubService
import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class PostgresChatStorageIntegrationTest {
    private lateinit var fixture: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var factory: ConnectionFactory
    private lateinit var bus: PostgresPubSubService
    private lateinit var store: PostgresChatMessageStore
    private val channel = UUID.random()
    private val sender = UUID.random()
    private val content = listOf(MessageContent(MessageContentType.TEXT, "hello"))
    @BeforeTest fun setup() {
        fixture = SharedPostgreSQLContainer().withDatabaseName("postgres_chat"); fixture.start()
        factory = ConnectionFactoryImpl(ConnectionConfig(fixture.jdbcUrl, fixture.username, fixture.password), "postgres")
        factory.create().use { connection -> connection.createStatement().use { stmt ->
            stmt.execute(listOf("V200__postgres_infrastructure.sql", "V201__postgres_cache_counters.sql", "V202__postgres_locks.sql").joinToString(" ") { resource -> checkNotNull(javaClass.getResourceAsStream("/db/migrations/$resource")).bufferedReader().use { it.readText() } })
            stmt.execute("create schema chat; create table chat.channels(id uuid primary key)")
            stmt.execute(checkNotNull(javaClass.getResourceAsStream("/db/migrations/V5__postgres_chat_storage.sql")).bufferedReader().use { it.readText() })
            stmt.execute("insert into chat.channels values ('$channel')")
        } }
        pool = ConnectionPool(factory); bus = PostgresPubSubService(factory, pool, Json); store = PostgresChatMessageStore(pool, bus, Json)
    }
    @AfterTest fun teardown() = runBlocking { store.shutdown(); bus.shutdown(); pool.close(); fixture.stop() }
    private suspend fun send(client: UUID = UUID.random(), parent: Long? = null) = store.sendMessage(channel, sender, client, content, null, parent)

    @Test fun `message history preserves scoped deduplication threading and before after order`(): Unit = runBlocking {
        val client = UUID.random()
        val first = send(client)
        assertEquals(first.sequence, send(client).sequence); assertTrue(send(client).duplicate)
        val reply = send(parent = first.sequence)
        val last = send()
        assertEquals(listOf(first.sequence, reply.sequence, last.sequence), store.getMessages(channel, null, null, 10).map { it.sequence })
        assertEquals(first.sequence, store.getMessage(channel, reply.sequence)?.parentSequence)
        assertEquals(listOf(first.sequence, reply.sequence), store.getMessages(channel, last.sequence, null, 2).map { it.sequence })
        assertEquals(listOf(reply.sequence, last.sequence), store.getMessages(channel, null, first.sequence, 2).map { it.sequence })
        assertNull(store.getMessage(UUID.random(), first.sequence))
        assertEquals(content, store.getMessage(channel, first.sequence)?.content)
    }
    @Test fun `concurrent messages allocate unique durable sequences in commit order`(): Unit = runBlocking {
        val results = coroutineScope { List(20) { async { send() } }.awaitAll() }
        assertEquals(20, results.map { it.sequence }.toSet().size)
        assertEquals(results.map { it.sequence }.sorted(), store.getMessages(channel, null, null, 20).map { it.sequence })
    }
    @Test fun `live delivery and deletion reflect committed durable history`(): Unit = runBlocking {
        withTimeout(10.seconds) {
            bus.awaitReady()
            val live = async(start = CoroutineStart.UNDISPATCHED) { store.subscribe(channel).first() }
            val sent = send()
            assertEquals(sent.sequence, live.await().sequence)
            val deleted = async(start = CoroutineStart.UNDISPATCHED) { store.subscribe(channel).first() }
            assertTrue(store.deleteMessage(channel, sent.sequence))
            assertTrue(deleted.await().deleted)
            assertNull(store.getMessage(channel, sent.sequence))
            assertFalse(store.deleteMessage(channel, sent.sequence))
        }
    }
    @Test fun `reaction read state and ephemeral cleanup use PostgreSQL exclusively`(): Unit = runBlocking {
        val sequence = send().sequence
        val reactions = PostgresChatReactionStore(pool)
        val id = UUID.random()
        assertTrue(reactions.add(channel, sequence, sender, id, "smile"))
        assertFalse(reactions.add(channel, sequence, sender, UUID.random(), "smile"))
        assertEquals(id, reactions.getForMessage(channel, sequence).single().id)
        val reads = PostgresChatReadStateStore(pool); reads.set(channel, sender, sequence)
        assertEquals(sequence, reads.get(channel, sender))
        val typing = PostgresChatTypingStore(pool); typing.setTyping(channel, sender)
        assertEquals(sender, typing.activeTypers(channel).single().profileId)
        val presence = PostgresChatPresenceStore(pool); presence.setPresence(sender, "online")
        assertEquals("online", presence.getPresence(sender)?.status)
        reactions.clearProfile(sender); reads.clearProfile(sender); typing.clearProfile(sender); presence.clearPresence(sender)
        assertTrue(reactions.getForMessage(channel, sequence).isEmpty()); assertNull(reads.get(channel, sender))
        assertTrue(typing.activeTypers(channel).isEmpty()); assertNull(presence.getPresence(sender))
    }
    @Test fun `batch presence and read state lookups return only stored live entries`(): Unit = runBlocking {
        val presence = PostgresChatPresenceStore(pool)
        val online = UUID.random(); val absent = UUID.random()
        presence.setPresence(online, "online"); presence.setPresence(sender, "away")
        assertEquals(
            mapOf(online to bosca.chat.model.PresenceUpdateEvent(online, "online"), sender to bosca.chat.model.PresenceUpdateEvent(sender, "away")),
            presence.getPresences(listOf(online, sender, absent, online)),
        )
        assertEquals(emptyMap(), presence.getPresences(emptyList()))
        val reads = PostgresChatReadStateStore(pool)
        val other = UUID.random()
        reads.set(channel, sender, 3); reads.set(channel, other, 5)
        assertEquals(mapOf((channel to sender) to 3L, (channel to other) to 5L), reads.getAll(listOf(channel to sender, channel to other, channel to absent)))
        assertEquals(emptyMap(), reads.getAll(emptyList()))
    }
    @Test fun `durable message event survives a failed publisher and relay restart`(): Unit = runBlocking {
        store.shutdown()
        val unavailable = object : bosca.pubsub.PubSubService {
            override suspend fun <T> publish(channel: String, serializer: kotlinx.serialization.SerializationStrategy<T>, message: T) { throw java.io.IOException("publisher unavailable") }
            override fun <T> subscribe(channel: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>): kotlinx.coroutines.flow.Flow<bosca.pubsub.Message<T>> = kotlinx.coroutines.flow.emptyFlow()
        }
        store = PostgresChatMessageStore(pool, unavailable, Json)
        val sent = send()
        assertNotNull(store.getMessage(channel, sent.sequence))
        store.shutdown()
        bus.awaitReady()
        val delivery = async(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribe("bosca.chat.v1.channels.$channel.messages", bosca.chat.model.ChatMessage.serializer()).first()
        }
        store = PostgresChatMessageStore(pool, bus, Json)
        assertEquals(sent.sequence, withTimeout(10.seconds) { delivery.await() }.message.sequence)
    }

}
