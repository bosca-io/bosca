package bosca.postgres

import bosca.db.*
import bosca.pubsub.PostgresPubSubService
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.hours

class PostgresPubSubIntegrationTest {
    private lateinit var fixture: SharedPostgreSQLContainer
    private lateinit var factory: ConnectionFactory
    private lateinit var pool: ConnectionPool
    private val services = mutableListOf<PostgresPubSubService>()

    @BeforeTest fun setup() {
        fixture = SharedPostgreSQLContainer().withDatabaseName("postgres_infrastructure")
        fixture.start()
        factory = ConnectionFactoryImpl(ConnectionConfig(fixture.jdbcUrl, fixture.username, fixture.password), "postgres")
        factory.create().use { connection ->
            val sql = checkNotNull(javaClass.getResourceAsStream("/db/migrations/V200__postgres_infrastructure.sql")).bufferedReader().use { it.readText() }
            connection.createStatement().use { it.execute(sql) }
        }
        pool = ConnectionPool(factory)
    }
    @AfterTest fun teardown() = runBlocking {
        services.forEach { it.shutdown() }
        pool.close(); fixture.stop()
    }
    private fun bus() = PostgresPubSubService(factory, pool, Json).also(services::add)

    @Test fun `fanout supports payloads larger than notification limit and multiple subscribers`() = runBlocking {
        withTimeout(10.seconds) {
            val a = bus(); val b = bus()
            val readyA = CompletableDeferred<Unit>(); val readyB = CompletableDeferred<Unit>()
            val first = async { a.subscribe("channel", String.serializer()) { readyA.complete(Unit) }.first() }
            val second = async { b.subscribe("channel", String.serializer()) { readyB.complete(Unit) }.first() }
            readyA.await(); readyB.await()
            val content = "x".repeat(32_000)
            a.publish("channel", String.serializer(), content)
            assertEquals(content, first.await().message)
            assertEquals(content, second.await().message)
        }
    }
    @Test fun `publishing inside rolled back transaction is invisible`() = runBlocking {
        val service = bus()
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) {
                manager.beginTransaction()
                service.publish("rolledback", String.serializer(), "uncommitted")
                manager.rollbackTransaction()
            }
        } finally { manager.release() }
        pool.connection().use { m -> m.useStatement("select count(*) from infrastructure.pubsub_messages") { stmt -> stmt.executeQuery().use { it.next(); assertEquals(0, it.getInt(1)) } } }
    }
    @Test fun `shutdown completes active subscriptions and releases the dedicated session`(): Unit = runBlocking {
        val service = bus()
        val ready = CompletableDeferred<Unit>()
        val collection = async { service.subscribe("shutdown", String.serializer()) { ready.complete(Unit) }.toList() }
        ready.await()
        withTimeout(2.seconds) { service.shutdown(); assertTrue(collection.await().isEmpty()) }
    }

    @Test fun `overflow resubscribes only the slow subscriber while same and unrelated channels keep delivering`(): Unit = runBlocking {
        val service = bus()
        val slowSubscriptions = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val peerReady = CompletableDeferred<Unit>(); val otherReady = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val slowReceived = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val slow = launch {
            service.subscribe("busy", String.serializer()) { slowSubscriptions.trySend(Unit) }.collect {
                slowReceived.send(it.message)
                entered.complete(Unit)
                release.await()
            }
        }
        val peer = async { service.subscribe("busy", String.serializer()) { peerReady.complete(Unit) }.take(101).toList() }
        val other = async { service.subscribe("other", String.serializer()) { otherReady.complete(Unit) }.first() }
        try {
            withTimeout(10.seconds) { slowSubscriptions.receive(); peerReady.await(); otherReady.await() }
            service.publish("busy", String.serializer(), "first")
            withTimeout(5.seconds) { entered.await() }
            repeat(100) { service.publish("busy", String.serializer(), "message-$it") }
            service.publish("other", String.serializer(), "unrelated")
            withTimeout(5.seconds) {
                assertEquals("unrelated", other.await().message)
                assertEquals(listOf("first") + List(100) { "message-$it" }, peer.await().map { it.message })
            }
            release.complete(Unit)
            withTimeout(5.seconds) { slowSubscriptions.receive() }
            service.publish("busy", String.serializer(), "after-overflow")
            withTimeout(5.seconds) {
                while (true) { if (slowReceived.receive() == "after-overflow") break }
            }
            assertTrue(slow.isActive, "An overflowing subscriber must resubscribe instead of failing")
        } finally { slow.cancelAndJoin(); peer.cancelAndJoin(); other.cancelAndJoin() }
    }

    @Test fun `malformed payloads are skipped without ending the subscription`(): Unit = runBlocking {
        val service = bus()
        val ready = CompletableDeferred<Unit>()
        val received = async { service.subscribe("typed", Int.serializer()) { ready.complete(Unit) }.first() }
        withTimeout(10.seconds) { ready.await() }
        service.publish("typed", String.serializer(), "not-a-number")
        service.publish("typed", Int.serializer(), 42)
        withTimeout(5.seconds) { assertEquals(42, received.await().message) }
    }

    @Test fun `subscriptions after shutdown complete without waiting for listener readiness`(): Unit = runBlocking {
        val service = bus()
        service.awaitReady()
        service.shutdown()
        withTimeout(2.seconds) { assertTrue(service.subscribe("closed", String.serializer()).toList().isEmpty()) }
    }

}
