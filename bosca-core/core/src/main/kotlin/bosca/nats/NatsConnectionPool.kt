package bosca.nats

import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import io.nats.client.Connection
import io.nats.client.ConnectionListener
import io.nats.client.Nats
import io.nats.client.Options
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

private class RetryNatsConnectionException(message: String) : IllegalStateException(message)

private class ManagedConnectionFactory(
    val create: suspend () -> Connection,
    private val executor: ExecutorService? = null,
    private val scheduledExecutor: ScheduledExecutorService? = null,
) {
    fun close() {
        // JNATS deliberately leaves caller-supplied executors running, so the pool that supplies
        // them must also own their lifecycle.
        scheduledExecutor?.shutdownNow()
        executor?.shutdownNow()
    }
}

/**
 * Owns the physical NATS connections used by the application.
 *
 * System infrastructure uses one dedicated connection through [systemConnection]. Client-facing
 * pub/sub work uses logical [BoscaNatsConnection] handles returned by [openConnection]. Each handle
 * is assigned to one of [maxConnections] physical slots, and every slot owns one shared dispatcher.
 */
class NatsConnectionPool private constructor(
    private val connectionFactory: ManagedConnectionFactory?,
    val maxConnections: Int,
    private val ownsConnections: Boolean,
    private val connectionCreationTimeoutMs: Long,
    initialSystemConnection: Connection? = null,
    initialConnections: List<Connection?> = List(maxConnections) { null },
) {
    private class PhysicalResource(
        val physical: BoscaNatsPhysicalConnection,
        val listener: ConnectionListener?,
    ) {
        private val closed = AtomicBoolean(false)

        val connection: Connection
            get() = physical.connection

        suspend fun close(cause: Throwable, closePhysicalConnection: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            listener?.let { runCatching { connection.removeConnectionListener(it) } }
            physical.fail(cause)
            if (closePhysicalConnection) {
                withContext(Dispatchers.IO) {
                    runCatching { connection.close() }
                }
            }
        }
    }

    private inner class ConnectionSlot(initialConnection: Connection?) {
        private val mutex = Mutex()

        @Volatile
        private var resource: PhysicalResource? = initialConnection?.let {
            PhysicalResource(
                // The manager owns dispatcher lifetime even when the injected physical connection
                // itself remains caller-owned. This avoids a last-close/new-open dispatcher race.
                BoscaNatsPhysicalConnection(it),
                listener = null,
            )
        }
        private var creation: Deferred<PhysicalResource>? = null

        val hasActiveDispatcher: Boolean
            get() = resource?.physical?.hasActiveDispatcher == true

        suspend fun get(): PhysicalResource {
            while (true) {
                try {
                    return getOnce()
                } catch (_: RetryNatsConnectionException) {
                    currentCoroutineContext().ensureActive()
                    check(!closed) { "NATS connection manager is closed" }
                }
            }
        }

        private suspend fun getOnce(): PhysicalResource {
            var stale: PhysicalResource? = null
            val pending = mutex.withLock {
                check(!closed) { "NATS connection manager is closed" }
                resource?.let { current ->
                    if (!needsReplacement(current.connection)) return current
                    resource = null
                    stale = current
                }
                creation ?: scope.async {
                    createPhysicalResource(this@ConnectionSlot)
                }.also { creation = it }
            }

            stale?.close(
                IllegalStateException("NATS physical connection was replaced after closing"),
                closePhysicalConnection = ownsConnections,
            )

            val created = try {
                pending.await()
            } catch (t: Throwable) {
                if (pending.isCompleted) {
                    mutex.withLock {
                        if (creation === pending) creation = null
                    }
                }
                throw t
            }

            if (needsReplacement(created.connection)) {
                mutex.withLock {
                    if (creation === pending) creation = null
                }
                created.close(
                    IllegalStateException("NATS connection closed while it was being established"),
                    closePhysicalConnection = ownsConnections,
                )
                throw RetryNatsConnectionException("NATS connection closed while it was being established")
            }

            var rejection: IllegalStateException? = null
            val selected = mutex.withLock {
                if (closed) {
                    rejection = IllegalStateException("NATS connection manager closed during connection creation")
                    null
                } else {
                    val current = resource
                    if (current != null && !needsReplacement(current.connection)) {
                        current
                    } else if (needsReplacement(created.connection)) {
                        // The terminal-close listener can run after the first status check but
                        // before installation. Recheck while holding the slot lock so its callback
                        // either observes an installed resource or this path rejects it.
                        if (creation === pending) creation = null
                        rejection = RetryNatsConnectionException(
                            "NATS connection closed while it was being installed",
                        )
                        null
                    } else {
                        resource = created
                        if (creation === pending) creation = null
                        created
                    }
                }
            }
            if (selected == null) {
                val cause = checkNotNull(rejection)
                created.close(
                    cause,
                    closePhysicalConnection = ownsConnections,
                )
                throw cause
            }
            if (selected !== created) {
                created.close(
                    IllegalStateException("A concurrent NATS connection creation already completed"),
                    closePhysicalConnection = ownsConnections,
                )
            }
            return selected
        }

        suspend fun physicalClosed(connection: Connection) {
            val closedResource = mutex.withLock {
                resource?.takeIf { it.connection === connection }?.also { resource = null }
            }
            closedResource?.close(
                IllegalStateException("NATS physical connection closed after reconnect attempts were exhausted"),
                closePhysicalConnection = false,
            )
        }

        suspend fun close(cause: Throwable) {
            val resources = mutex.withLock {
                val current = resource
                resource = null
                val pending = creation
                creation = null
                current to pending
            }
            resources.second?.cancel()
            resources.first?.close(cause, closePhysicalConnection = ownsConnections)
        }
    }

    private val lifecycleMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nextConnection = 0
    private val openConnections = ConcurrentHashMap.newKeySet<BoscaNatsConnection>()
    private val systemSlot = ConnectionSlot(initialSystemConnection)
    private val connectionSlots = initialConnections.map(::ConnectionSlot)

    @Volatile
    private var closed = false

    init {
        require(maxConnections > 0) { "maxConnections must be greater than zero" }
        require(initialConnections.size == maxConnections) {
            "initialConnections must contain maxConnections entries"
        }
        require(connectionCreationTimeoutMs > 0) { "connectionCreationTimeoutMs must be greater than zero" }
    }

    constructor(url: String, token: String, maxConnections: Int = DEFAULT_MAX_CONNECTIONS) : this(
        connectionFactory = connectionFactory(
            Options.Builder()
                .server(url)
                .token(token.toCharArray()),
            maxConnections,
        ),
        maxConnections = maxConnections,
        ownsConnections = true,
        connectionCreationTimeoutMs = DEFAULT_CONNECTION_CREATION_TIMEOUT_MS,
    )

    /** Creates a connection manager using NATS username/password authentication. */
    constructor(
        url: String,
        username: String,
        password: String,
        maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    ) : this(
        connectionFactory = connectionFactory(
            Options.Builder()
                .server(url)
                .userInfo(username, password),
            maxConnections,
        ),
        maxConnections = maxConnections,
        ownsConnections = true,
        connectionCreationTimeoutMs = DEFAULT_CONNECTION_CREATION_TIMEOUT_MS,
    )

    /** Creates a non-owning single-connection manager for tests and embedded use. */
    constructor(connection: Connection) : this(
        connectionFactory = null,
        maxConnections = 1,
        ownsConnections = false,
        connectionCreationTimeoutMs = DEFAULT_CONNECTION_CREATION_TIMEOUT_MS,
        initialSystemConnection = connection,
        initialConnections = listOf(connection),
    )

    internal constructor(
        systemConnection: Connection,
        connections: List<Connection>,
        ownsConnections: Boolean = false,
    ) : this(
        connectionFactory = null,
        maxConnections = connections.size,
        ownsConnections = ownsConnections,
        connectionCreationTimeoutMs = DEFAULT_CONNECTION_CREATION_TIMEOUT_MS,
        initialSystemConnection = systemConnection,
        initialConnections = connections,
    )

    internal constructor(
        maxConnections: Int,
        connectionCreationTimeoutMs: Long = DEFAULT_CONNECTION_CREATION_TIMEOUT_MS,
        connectionFactory: suspend () -> Connection,
    ) : this(
        connectionFactory = ManagedConnectionFactory(connectionFactory),
        maxConnections = maxConnections,
        ownsConnections = true,
        connectionCreationTimeoutMs = connectionCreationTimeoutMs,
    )

    /** Number of currently open logical handles. */
    val activeLogicalConnectionCount: Int
        get() = openConnections.size

    /** Number of dispatcher threads owned by client-facing physical slots. */
    val activeDispatcherCount: Int
        get() = connectionSlots.count { it.hasActiveDispatcher }

    /** Returns the dedicated physical connection reserved for system infrastructure. */
    suspend fun systemConnection(): Connection = systemSlot.get().connection

    /** Opens a logical connection on the next shared physical pub/sub slot. */
    suspend fun openConnection(): BoscaNatsConnection {
        val index = lifecycleMutex.withLock {
            check(!closed) { "NATS connection manager is closed" }
            nextConnection.also {
                nextConnection = (nextConnection + 1) % maxConnections
            }
        }
        return openConnection(index)
    }

    /**
     * Opens a logical connection with stable affinity between [partitionKey] and a physical slot.
     * This retains NATS per-connection publication order for a subject or aggregate.
     */
    suspend fun openConnection(partitionKey: String): BoscaNatsConnection {
        lifecycleMutex.withLock {
            check(!closed) { "NATS connection manager is closed" }
        }
        return openConnection(Math.floorMod(partitionKey.hashCode(), maxConnections))
    }

    private suspend fun openConnection(index: Int): BoscaNatsConnection {
        while (true) {
            val physical = connectionSlots[index].get().physical
            try {
                return lifecycleMutex.withLock {
                    check(!closed) { "NATS connection manager is closed" }
                    BoscaNatsConnection(physical) { connection ->
                        openConnections.remove(connection)
                    }.also(openConnections::add)
                }
            } catch (e: IllegalStateException) {
                if (closed) throw e
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private suspend fun createPhysicalResource(slot: ConnectionSlot): PhysicalResource {
        var connection: Connection? = null
        try {
            connection = try {
                withTimeout(connectionCreationTimeoutMs.milliseconds) { createConnection() }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException(
                    "Timed out creating a NATS connection after ${connectionCreationTimeoutMs}ms",
                    e,
                )
            }
            currentCoroutineContext().ensureActive()
            val listener = ConnectionListener { connection, event ->
                if (event == ConnectionListener.Events.CLOSED && !closed) {
                    scope.launch { slot.physicalClosed(connection) }
                }
            }
            connection.addConnectionListener(listener)
            return PhysicalResource(BoscaNatsPhysicalConnection(connection), listener)
        } catch (t: Throwable) {
            connection?.let { created ->
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { created.close() }
                }
            }
            throw t
        }
    }

    private suspend fun createConnection(): Connection {
        val factory = connectionFactory ?: error("No physical connection factory is available")
        return factory.create()
    }

    private fun needsReplacement(connection: Connection): Boolean = ownsConnections && connection.status == Connection.Status.CLOSED

    /** Closes all logical handles, dispatchers, and physical connections owned by this manager. */
    suspend fun close() = withContext(NonCancellable) {
        val logicalConnections = lifecycleMutex.withLock {
            if (closed) return@withContext
            closed = true
            openConnections.toList().also { openConnections.clear() }
        }
        scope.cancel("NatsConnectionPool closing")
        try {
            logicalConnections.forEach { connection -> connection.close() }
            val cause = IllegalStateException("NATS connection manager is closed")
            systemSlot.close(cause)
            connectionSlots.forEach { it.close(cause) }
        } finally {
            connectionFactory?.close()
        }
    }

    companion object {
        const val DEFAULT_MAX_CONNECTIONS = 50
        const val DEFAULT_CONNECTION_CREATION_TIMEOUT_MS = 5_000L

        private fun connectionFactory(builder: Options.Builder, maxConnections: Int): ManagedConnectionFactory {
            require(maxConnections > 0) { "maxConnections must be greater than zero" }
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val dispatcher = executor.asCoroutineDispatcher()
            val scheduledExecutor = ScheduledThreadPoolExecutor(
                3,
                Thread.ofVirtual().name("bosca-nats-scheduled-", 0).factory(),
            ).apply {
                setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
                removeOnCancelPolicy = true
            }
            val options = try {
                builder
                    .executor(executor)
                    .scheduledExecutor(scheduledExecutor)
                    .build()
            } catch (t: Throwable) {
                scheduledExecutor.shutdownNow()
                executor.shutdownNow()
                throw t
            }
            return ManagedConnectionFactory(
                create = {
                    withContext(dispatcher) {
                        // Initial connection attempts fail within the configured connection timeout. Once
                        // connected, JNATS still performs its ordinary automatic reconnect sequence.
                        Nats.connect(options)
                    }
                },
                executor = executor,
                scheduledExecutor = scheduledExecutor,
            )
        }

        @OptIn(InternalDI::class)
        fun register(url: String, token: String, maxConnections: Int = DEFAULT_MAX_CONNECTIONS) {
            ProviderRegistry.register(NatsConnectionPool::class, object : ObjectProvider<NatsConnectionPool> {
                override val type: KClass<NatsConnectionPool> = NatsConnectionPool::class
                override suspend fun get(): NatsConnectionPool = NatsConnectionPool(url, token, maxConnections)
            }, true)
        }

        /** Registers a pool authenticated as one NATS account user. */
        @OptIn(InternalDI::class)
        fun register(
            url: String,
            username: String,
            password: String,
            maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
        ) {
            ProviderRegistry.register(NatsConnectionPool::class, object : ObjectProvider<NatsConnectionPool> {
                override val type: KClass<NatsConnectionPool> = NatsConnectionPool::class
                override suspend fun get(): NatsConnectionPool = NatsConnectionPool(url, username, password, maxConnections)
            }, true)
        }
    }
}
