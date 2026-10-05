@file:OptIn(ExperimentalAtomicApi::class)

package bosca.db

import bosca.di.provide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds

class ConnectionPool(private val factory: ConnectionFactory) {

    private val activeConnectionCount = AtomicInteger()
    private val createdConnectionCount = AtomicInteger()

    private val allConnectionsMutex = Mutex()
    private val allConnections = mutableSetOf<PoolConnection>()
    private val availableConnections = Channel<PoolConnection>(capacity = factory.maxConnections)
    private val waitingOnConnection = AtomicInteger()

    private val defaultTransactionIsolation: Int = Connection.TRANSACTION_READ_COMMITTED

    private val monitorScope = CoroutineScope(SupervisorJob() + DatabaseDispatcher)
    private val monitorJob: Job
    private val monitorIntervalMs: Long = 30_000L

    init {
        monitorJob = monitorScope.launch {
            while (isActive) {
                delay(monitorIntervalMs.milliseconds)
                runCatching { validateIdleConnections() }
                    .onFailure { t -> log.warn("Connection monitor tick failed", t) }
            }
        }
    }

    val name: String
        get() = factory.key

    val maxConnections: Int
        get() = factory.maxConnections

    val createdConnections: Int
        get() = createdConnectionCount.get()

    val activeConnections: Int
        get() = activeConnectionCount.get()

    @OptIn(ExperimentalCoroutinesApi::class)
    val hasAvailableConnections: Boolean
        get() = !availableConnections.isEmpty

    /** Runs one deterministic idle-health-check pass; internal so lifecycle tests need not wait for the monitor timer. */
    internal suspend fun validateIdleConnections() = withContext(NonCancellable) {
        repeat((0 until createdConnections).count()) {
            val connection = availableConnections.tryReceive().getOrNull() ?: return@repeat
            val keep = runCatching {
                withTimeout(3_000.milliseconds) {
                    isHealthyForPool(connection)
                }
            }.getOrElse { false }
            if (!keep) {
                discardConnection(connection, IllegalStateException("Connection is not healthy (monitor)"))
            } else {
                availableConnections.send(connection)
            }
        }
    }

    private suspend fun createConnectionIfPossible(): PoolConnection? = withContext(NonCancellable) {
        return@withContext try {
            val current = createdConnectionCount.incrementAndGet()
            if (current > factory.maxConnections) {
                createdConnectionCount.decrementAndGet()
                log.warn("Max connection count reached")
                return@withContext null
            }
            val connection = withContext(DatabaseDispatcher) {
                withTimeout(5000.milliseconds) {
                    log.debug("Creating a new connection")
                    val connection = PoolConnection(factory.create(), defaultTransactionIsolation)
                    log.debug("New connection created")
                    connection
                }
            }
            allConnectionsMutex.withLock {
                allConnections.add(connection)
            }
            connection
        } catch (t: Throwable) {
            log.error("Failed to create a new connection", t)
            createdConnectionCount.decrementAndGet()
            throw t
        }
    }

    private suspend fun discardConnection(connection: PoolConnection, cause: Throwable? = null) {
        connection.state = ConnectionState.RELEASED
        log.warn("discarding a connection", cause)
        try {
            withContext(NonCancellable) {
                withContext(DatabaseDispatcher) {
                    withTimeout(3_000.milliseconds) {
                        runCatching { connection.close() }.onFailure { log.error("Failed to close connection", it) }
                    }
                }
            }
        } finally {
            createdConnectionCount.decrementAndGet()
            allConnectionsMutex.withLock {
                allConnections.remove(connection)
            }
        }
        if (waitingOnConnection.get() > 0) {
            withContext(NonCancellable) {
                createConnectionIfPossible()?.let { connection ->
                    availableConnections.send(connection)
                }
            }
        }
    }

    private suspend fun isHealthyForPool(connection: PoolConnection): Boolean {
        return withContext(DatabaseDispatcher) {
            try {
                if (connection.isClosed) {
                    log.error("Connection failed health check: closed")
                    return@withContext false
                }
                if (!connection.validate()) {
                    log.error("Connection failed health check: invalid")
                    return@withContext false
                }
                true
            } catch (t: Throwable) {
                log.error("Connection failed health check", t)
                false
            }
        }
    }

    private suspend fun onObtained(connection: PoolConnection, retryOnFailure: Boolean = true): PoolConnection = withContext(DatabaseDispatcher + NonCancellable) {
        connection.validateState(ConnectionState.RELEASED)
        connection.state = ConnectionState.OBTAINED
        try {
            if (connection.isClosed) {
                error("Connection is closed")
            }
            try {
                withTimeout(5000.milliseconds) {
                    connection.beginRequest()
                }
            } catch (e: Throwable) {
                runCatching { discardConnection(connection, e) }
                if (retryOnFailure) {
                    return@withContext obtainConnection(retryOnFailure = false, acquire = false)
                } else {
                    throw e
                }
            }
            check(connection.markActive()) { "Connection is already active" }
            val current = activeConnectionCount.incrementAndGet()
            if (current > factory.maxConnections) {
                log.error("Active connection count went over max")
            } else if (log.isTraceEnabled) {
                log.trace("onObtained: active=$current")
            }
        } catch (t: Throwable) {
            // A beginRequest failure discards the connection before retrying. If the retry also
            // fails, its exception propagates through this outer frame; do not discard the
            // original connection a second time and drive the pool counters negative.
            if (connection.state != ConnectionState.RELEASED) {
                connection.state = ConnectionState.RELEASED
                discardConnection(connection, t)
            }
            throw t
        }
        connection
    }

    fun connection(): ConnectionManager {
        return ConnectionManager(this)
    }

    internal suspend fun obtainConnection(retryOnFailure: Boolean = true, acquire: Boolean = true): PoolConnection {
        if (acquire) {
            connectionLimiterOrNull()?.acquire()
        }
        try {
            availableConnections.tryReceive().getOrNull()?.let { connection ->
                if (!connection.isClosed) {
                    return onObtained(connection, retryOnFailure)
                }
                discardConnection(connection, IllegalStateException("Available connection is closed"))
            }

            createConnectionIfPossible()?.let { connection ->
                return onObtained(connection, retryOnFailure)
            }

            val connection = try {
                waitingOnConnection.incrementAndGet()
                val connection = withTimeout(3000.milliseconds) { availableConnections.receive() }
                if (connection.isClosed) {
                    if (retryOnFailure) {
                        return obtainConnection(retryOnFailure = false, acquire = false)
                    } else {
                        error("Connection is closed")
                    }
                }
                connection
            } finally {
                waitingOnConnection.decrementAndGet()
            }
            return onObtained(connection, retryOnFailure)
        } catch (e: Exception) {
            if (acquire) {
                connectionLimiterOrNull()?.release()
            }
            throw e
        }
    }

    internal suspend fun releaseConnection(connection: PoolConnection) = withContext(DatabaseDispatcher + NonCancellable) {
        connection.validateState(ConnectionState.OBTAINED)
        connectionLimiterOrNull()?.release()
        connection.state = ConnectionState.RELEASED

        var shouldReturnToPool = true

        try {
            if (!connection.isClosed) {
                connection.endRequest()
            } else {
                shouldReturnToPool = false
            }
            if (shouldReturnToPool) {
                connection.reset()
            }
        } catch (t: Throwable) {
            log.error("Failed to release connection", t)
            shouldReturnToPool = false
        } finally {
            check(connection.markInactive()) { "Connection is already inactive" }
            val active = activeConnectionCount.decrementAndGet()
            if (active < 0) {
                log.error("Active connection count went negative", Throwable("Stack trace"))
            }
        }

        if (!shouldReturnToPool) {
            discardConnection(connection, null)
            return@withContext
        }

        try {
            availableConnections.send(connection)
        } catch (ce: CancellationException) {
            log.warn("Release was cancelled, closing connection", ce)
            discardConnection(connection, ce)
            throw ce
        } catch (t: Throwable) {
            log.warn("Release failed, closing connection", t)
            discardConnection(connection, t)
            throw t
        }
    }

    fun setDataSource(flyway: FluentConfiguration) = factory.setDataSource(flyway)

    suspend fun close() = withContext(NonCancellable) {
        availableConnections.close()
        monitorJob.cancel()
        val snapshot = allConnectionsMutex.withLock {
            val snapshot = allConnections.toList()
            allConnections.clear()
            snapshot
        }
        withContext(DatabaseDispatcher) {
            snapshot.forEach { runCatching { it.close() } }
        }
        createdConnectionCount.set(0)
        activeConnectionCount.set(0)
    }

    companion object {

        private val log = LoggerFactory.getLogger(ConnectionPool::class.java)
    }
}

suspend fun <T> withConnectionManager(block: suspend () -> T): T {
    val pool = provide<ConnectionPool>()
    val connection = pool.connection()
    try {
        return withContext(connection.asCoroutineContext()) {
            block()
        }
    } finally {
        withContext(NonCancellable) {
            connection.release()
        }
    }
}
