package bosca.nats

import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.Message
import io.nats.client.MessageHandler
import io.nats.client.PushSubscribeOptions
import io.nats.client.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the dispatcher shared by every logical handle assigned to one physical NATS connection.
 *
 * JNATS dedicates a platform thread to each dispatcher, so dispatcher ownership belongs at the
 * physical-connection boundary rather than the logical-subscriber boundary.
 */
internal class BoscaNatsPhysicalConnection(
    val connection: Connection,
    private val closeDispatcherWhenUnused: Boolean = false,
) {
    private val lifecycleMutex = Mutex()
    private val logicalConnections = ConcurrentHashMap.newKeySet<BoscaNatsConnection>()

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var dispatcher: Dispatcher? = null

    val hasActiveDispatcher: Boolean
        get() = dispatcher != null

    fun register(connection: BoscaNatsConnection) {
        check(failure == null) { "NATS physical connection is closed" }
        logicalConnections.add(connection)
        if (failure != null) {
            logicalConnections.remove(connection)
            error("NATS physical connection is closed")
        }
    }

    suspend fun unregister(connection: BoscaNatsConnection) {
        logicalConnections.remove(connection)
        if (closeDispatcherWhenUnused && logicalConnections.isEmpty()) {
            closeDispatcher()
        }
    }

    suspend fun subscribeCore(subject: String, handler: MessageHandler): Pair<Dispatcher, Subscription> =
        lifecycleMutex.withLock {
            checkOpen()
            val activeDispatcher = dispatcher ?: connection.createDispatcher().also { dispatcher = it }
            activeDispatcher to activeDispatcher.subscribe(subject, handler)
        }

    suspend fun subscribeJetStream(
        subject: String,
        options: PushSubscribeOptions,
        handler: MessageHandler,
    ): Pair<Dispatcher, Subscription> = lifecycleMutex.withLock {
        checkOpen()
        val activeDispatcher = dispatcher ?: connection.createDispatcher().also { dispatcher = it }
        activeDispatcher to connection.jetStream().subscribe(
            subject,
            activeDispatcher,
            handler,
            false,
            options,
        )
    }

    /**
     * Registers a JetStream subject and a core subject on the same dispatcher and handler. JNATS
     * invokes that handler serially, preserving the physical connection's cross-subject arrival
     * order before messages enter coroutine code.
     */
    suspend fun subscribeJetStreamAndCore(
        jetStreamSubject: String,
        options: PushSubscribeOptions,
        coreSubject: String,
        handler: MessageHandler,
    ): Pair<Dispatcher, List<Subscription>> = lifecycleMutex.withLock {
        checkOpen()
        val activeDispatcher = dispatcher ?: connection.createDispatcher().also { dispatcher = it }
        val jetStreamSubscription = connection.jetStream().subscribe(
            jetStreamSubject,
            activeDispatcher,
            handler,
            false,
            options,
        )
        try {
            val coreSubscription = activeDispatcher.subscribe(coreSubject, handler)
            activeDispatcher to listOf(jetStreamSubscription, coreSubscription)
        } catch (t: Throwable) {
            runCatching { activeDispatcher.unsubscribe(jetStreamSubscription) }
            throw t
        }
    }

    /** Fails every logical handle when its shared physical connection becomes terminally closed. */
    suspend fun fail(cause: Throwable) {
        val resources = lifecycleMutex.withLock {
            if (failure != null) return
            failure = cause
            val handles = logicalConnections.toList()
            logicalConnections.clear()
            val activeDispatcher = dispatcher
            dispatcher = null
            handles to activeDispatcher
        }
        resources.first.forEach { it.fail(cause) }
        resources.second?.let { runCatching { connection.closeDispatcher(it) } }
    }

    /** Closes dispatcher resources without closing the underlying physical connection. */
    suspend fun close() {
        fail(IllegalStateException("NATS physical connection was closed"))
    }

    private suspend fun closeDispatcher() {
        val activeDispatcher = lifecycleMutex.withLock {
            val current = dispatcher
            dispatcher = null
            current
        }
        activeDispatcher?.let { runCatching { connection.closeDispatcher(it) } }
    }

    private fun checkOpen() {
        failure?.let { throw IllegalStateException("NATS physical connection is closed", it) }
    }
}

/**
 * A closeable logical NATS connection backed by a shared physical connection.
 *
 * The handle owns its subscriptions and flows, but the physical connection and its dispatcher are
 * owned by [NatsConnectionPool].
 */
class BoscaNatsConnection internal constructor(
    private val physical: BoscaNatsPhysicalConnection,
    private val onClose: (BoscaNatsConnection) -> Unit = {},
) {
    internal constructor(connection: Connection) : this(
        BoscaNatsPhysicalConnection(connection, closeDispatcherWhenUnused = true),
    )

    private class ActiveSubscription(
        val dispatcher: Dispatcher,
        val subscriptions: List<Subscription>,
        val messages: Channel<Message>,
    ) {
        private val lifecycleMutex = Mutex()
        private var unsubscribed = false

        suspend fun unsubscribe() = lifecycleMutex.withLock {
            if (!unsubscribed) {
                unsubscribed = true
                subscriptions.forEach { subscription ->
                    runCatching { dispatcher.unsubscribe(subscription) }
                }
            }
        }
    }

    private val lifecycleMutex = Mutex()
    private val subscriptions = mutableSetOf<ActiveSubscription>()
    private var closed = false

    init {
        physical.register(this)
    }

    /** Publishes [data] on [subject] through the shared physical connection. */
    suspend fun publish(subject: String, data: ByteArray) = lifecycleMutex.withLock {
        checkOpen()
        physical.connection.publish(subject, data)
    }

    /**
     * Runs a bounded operation against the assigned physical connection while this logical handle
     * is open. The operation must not retain the physical connection or create resources that live
     * beyond the call; subscriptions should use [subscribe] or [subscribeJetStream] instead.
     */
    suspend fun <T> withConnection(operation: (Connection) -> T): T = lifecycleMutex.withLock {
        checkOpen()
        operation(physical.connection)
    }

    /** Flushes pending protocol commands so a newly registered subscription is active server-side. */
    suspend fun flush(timeout: Duration) = lifecycleMutex.withLock {
        checkOpen()
        withContext(Dispatchers.IO) {
            physical.connection.flush(timeout)
        }
    }

    /**
     * Returns a cold flow backed by a NATS subscription created on this logical connection.
     * Cancelling collection unsubscribes only that flow.
     */
    fun subscribe(subject: String, onSubscribed: (suspend () -> Unit)? = null): Flow<Message> = flow {
        collectSubscription(openSubscription(subject), onSubscribed)
    }

    /**
     * Returns a cold flow backed by a JetStream push subscription on this logical connection.
     * Message acknowledgement remains the collector's responsibility.
     */
    fun subscribeJetStream(subject: String, options: PushSubscribeOptions): Flow<Message> = flow {
        collectSubscription(openJetStreamSubscription(subject, options))
    }

    /**
     * Returns one ordered flow for a JetStream subject and a related core NATS subject.
     */
    fun subscribeJetStreamAndCore(
        jetStreamSubject: String,
        options: PushSubscribeOptions,
        coreSubject: String,
        onSubscribed: (suspend () -> Unit)? = null,
    ): Flow<Message> = flow {
        collectSubscription(
            openJetStreamAndCoreSubscription(jetStreamSubject, options, coreSubject),
            onSubscribed,
        )
    }

    /** Closes resources created through this handle without closing the physical connection. */
    suspend fun close() = closeInternal(null)

    internal suspend fun fail(cause: Throwable) = closeInternal(cause)

    private suspend fun closeInternal(cause: Throwable?) = withContext(NonCancellable) {
        val activeSubscriptions = lifecycleMutex.withLock {
            if (closed) return@withContext
            closed = true
            subscriptions.toList().also { subscriptions.clear() }
        }
        activeSubscriptions.forEach { activeSubscription ->
            activeSubscription.unsubscribe()
            activeSubscription.messages.close(cause)
        }
        physical.unregister(this@BoscaNatsConnection)
        onClose(this@BoscaNatsConnection)
    }

    private suspend fun FlowCollector<Message>.collectSubscription(
        activeSubscription: ActiveSubscription,
        onSubscribed: (suspend () -> Unit)? = null,
    ) {
        try {
            onSubscribed?.invoke()
            emitAll(activeSubscription.messages.receiveAsFlow())
        } finally {
            withContext(NonCancellable) {
                closeSubscription(activeSubscription)
            }
        }
    }

    private suspend fun openSubscription(subject: String): ActiveSubscription = lifecycleMutex.withLock {
        checkOpen()
        val messages = Channel<Message>(Channel.BUFFERED)
        val overflowLogged = AtomicBoolean(false)
        val (dispatcher, subscription) = physical.subscribeCore(subject) { message ->
            messages.offerOrLog(message, overflowLogged)
        }
        ActiveSubscription(dispatcher, listOf(subscription), messages).also(subscriptions::add)
    }

    private suspend fun openJetStreamSubscription(
        subject: String,
        options: PushSubscribeOptions,
    ): ActiveSubscription = lifecycleMutex.withLock {
        checkOpen()
        val messages = Channel<Message>(Channel.BUFFERED)
        val overflowLogged = AtomicBoolean(false)
        val (dispatcher, subscription) = physical.subscribeJetStream(subject, options) { message ->
            messages.offerOrLog(message, overflowLogged)
        }
        ActiveSubscription(dispatcher, listOf(subscription), messages).also(subscriptions::add)
    }

    private suspend fun openJetStreamAndCoreSubscription(
        jetStreamSubject: String,
        options: PushSubscribeOptions,
        coreSubject: String,
    ): ActiveSubscription = lifecycleMutex.withLock {
        checkOpen()
        val messages = Channel<Message>(Channel.BUFFERED)
        val overflowLogged = AtomicBoolean(false)
        val (dispatcher, activeSubscriptions) = physical.subscribeJetStreamAndCore(
            jetStreamSubject,
            options,
            coreSubject,
        ) { message -> messages.offerOrLog(message, overflowLogged) }
        ActiveSubscription(dispatcher, activeSubscriptions, messages).also(subscriptions::add)
    }

    private suspend fun closeSubscription(activeSubscription: ActiveSubscription) {
        lifecycleMutex.withLock {
            subscriptions.remove(activeSubscription)
        }
        activeSubscription.unsubscribe()
        activeSubscription.messages.close()
    }

    private fun checkOpen() {
        check(!closed) { "NATS logical connection is closed" }
    }

    private fun Channel<Message>.offerOrLog(message: Message, overflowLogged: AtomicBoolean) {
        val result = trySend(message)
        if (result.isFailure && !result.isClosed && overflowLogged.compareAndSet(false, true)) {
            log.warn(
                "NATS consumer buffer is full for subject '{}'; leaving the message unacknowledged and continuing",
                message.subject,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(BoscaNatsConnection::class.java)
    }
}
