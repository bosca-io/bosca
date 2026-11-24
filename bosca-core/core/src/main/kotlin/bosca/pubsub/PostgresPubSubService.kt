package bosca.pubsub

import bosca.db.ConnectionFactory
import bosca.db.ConnectionPool
import bosca.db.DatabaseDispatcher
import bosca.db.connectionOrNull
import bosca.db.use
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Live fan-out using one dedicated PostgreSQL session and expiring payload references.
 * Each subscriber has a bounded mailbox; overflow drops that subscriber's pending messages and
 * resubscribes it without blocking others, matching the at-most-once delivery of the other backends.
 */
class PostgresPubSubService(
    private val factory: ConnectionFactory,
    private val pool: ConnectionPool,
    private val json: Json,
) : PubSubService {
    private val scope = CoroutineScope(SupervisorJob() + DatabaseDispatcher)
    private val subscribers = ConcurrentHashMap<Channel<String>, String>()
    private val ready = MutableStateFlow(false)
    private val physicalConnection = AtomicReference<Connection?>()
    private val log = LoggerFactory.getLogger(javaClass)
    private val cleanup = scope.launch {
        while (isActive) {
            delay(60_000)
            try { evictExpiredItems() } catch (e: CancellationException) { throw e } catch (e: Exception) { log.warn("PostgreSQL pub/sub cleanup failed", e) }
        }
    }
    private val listener = scope.launch {
        while (isActive) {
            try {
                runInterruptible { factory.create().also { physicalConnection.set(it) } }.use { connection ->
                    connection.autoCommit = true
                    connection.createStatement().use { it.execute("listen bosca_pubsub") }
                    ready.value = true
                    val pg = connection.unwrap(PGConnection::class.java)
                    while (isActive) {
                        for (notification in pg.getNotifications(500).orEmpty()) {
                            // The notification names its channel so instances without subscribers skip the payload read.
                            val channel = notification.parameter.substringAfter(':')
                            if (subscribers.values.none { it == channel }) continue
                            val id = UUID.fromString(notification.parameter.substringBefore(':'))
                            val payload = pool.connection().use { manager ->
                                manager.useStatement("select payload from infrastructure.pubsub_messages where id = ? and expires > clock_timestamp()") { stmt ->
                                    stmt.setObject(1, id)
                                    stmt.executeQuery().use { if (it.next()) it.getString(1) else null }
                                }
                            }
                            if (payload != null) {
                                for ((mailbox, subscribed) in subscribers) {
                                    if (subscribed == channel && mailbox.trySend(payload).isFailure) {
                                        if (mailbox.close(IllegalStateException("PostgreSQL pub/sub subscriber overflow on channel $channel"))) {
                                            log.warn("Closing slow PostgreSQL pub/sub subscriber on channel {}", channel)
                                        }
                                        subscribers.remove(mailbox)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) log.warn("PostgreSQL pub/sub listener disconnected; reconnecting", e)
            } finally {
                ready.value = false
                physicalConnection.getAndSet(null)?.close()
            }
            delay(250)
        }
    }

    /** Waits until LISTEN has committed on the dedicated session. */
    suspend fun awaitReady() { ready.first { it } }

    override suspend fun <T> publish(channel: String, serializer: SerializationStrategy<T>, message: T) {
        val payload = json.encodeToString(serializer, message)
        val id = UUID.randomUUID()
        val existing = connectionOrNull()
        suspend fun publish(manager: bosca.db.ConnectionManager) {
            manager.useStatement("""
                with inserted as (
                    insert into infrastructure.pubsub_messages(id, channel, payload) values (?, ?, ?) returning id, channel
                ) select pg_notify('bosca_pubsub', id::text || ':' || channel) from inserted
            """.trimIndent()) { stmt ->
                stmt.setObject(1, id); stmt.setString(2, channel); stmt.setString(3, payload)
                stmt.executeQuery().close()
            }
        }
        if (existing != null) publish(existing) else pool.connection().use { publish(it) }
    }

    override fun <T> subscribe(channel: String, deserializer: DeserializationStrategy<T>): Flow<Message<T>> = subscribe(channel, deserializer, null)

    internal fun <T> subscribe(channel: String, deserializer: DeserializationStrategy<T>, onSubscribed: (() -> Unit)?): Flow<Message<T>> = channelFlow {
        val mailbox = Channel<String>(64)
        subscribers[mailbox] = channel
        val collector = launch {
            awaitReady(); onSubscribed?.invoke()
            for (payload in mailbox) {
                val message = try {
                    json.decodeFromString(deserializer, payload)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Ignoring malformed PostgreSQL pub/sub message on channel '{}'", channel, e)
                    continue
                }
                send(Message(channel, message))
            }
        }
        val shutdown = launch { listener.join(); close() }
        awaitClose { subscribers.remove(mailbox); mailbox.cancel(); collector.cancel(); shutdown.cancel() }
    }.buffer(0).retryWhen { cause, attempt ->
        if (cause is CancellationException) return@retryWhen false
        val delayMs = (attempt + 1).coerceAtMost(10) * RETRY_DELAY_MS
        log.warn("PostgreSQL pub/sub subscription on '{}' failed; resubscribing in {}ms", channel, delayMs, cause)
        delay(delayMs)
        true
    }

    /** Reclaims expired payloads; active subscriptions do not retain historical delivery. */
    suspend fun evictExpiredItems() {
        pool.connection().use { manager ->
            manager.useStatement("delete from infrastructure.pubsub_messages where expires <= clock_timestamp()") { it.executeUpdate() }
        }
    }

    companion object {
        private const val RETRY_DELAY_MS = 100L

        /**
         * Creates one lifecycle-managed service at a composition root. Payload reads, cleanup, and
         * publishes outside a transaction use the infrastructure pool; LISTEN keeps its own session.
         */
        suspend fun create(application: bosca.server.BoscaApplication): PostgresPubSubService =
            PostgresPubSubService(bosca.di.provide(), bosca.di.provide(bosca.db.PostgresInfrastructurePoolProvider.NAME), bosca.di.provide()).also { service ->
                application.onShutdown { service.shutdown() }
            }
    }

    /** Stops subscriptions and closes the dedicated listener session. */
    override suspend fun shutdown() {
        scope.cancel()
        withContext(NonCancellable + DatabaseDispatcher) { physicalConnection.getAndSet(null)?.close() }
        listener.join()
        cleanup.join()
    }
}
