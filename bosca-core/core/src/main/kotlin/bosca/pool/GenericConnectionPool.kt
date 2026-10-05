package bosca.pool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

class GenericConnectionPool<T>(
    private val factory: PoolableConnectionFactory<T>,
    private val name: String = "generic-pool",
    private val monitorIntervalMs: Long = 30_000L,
    private val createTimeoutMs: Long = 5_000L,
    private val validateTimeoutMs: Long = 3_000L,
    private val acquireTimeoutMs: Long = 3_000L,
) {

    private val activeConnectionCount = AtomicInteger()
    private val createdConnectionCount = AtomicInteger()

    private val allConnectionsMutex = Mutex()
    private val allConnections = mutableSetOf<T>()
    private val availableConnections = Channel<T>(capacity = factory.maxConnections)
    private val waitingOnConnection = AtomicInteger()

    private val poolDispatcher = Dispatchers.IO
    private val monitorScope = CoroutineScope(SupervisorJob() + poolDispatcher)
    private val monitorJob: Job

    init {
        monitorJob = monitorScope.launch {
            while (isActive) {
                delay(monitorIntervalMs)
                runCatching { validateIdleConnections() }
                    .onFailure { t -> log.warn("[$name] Connection monitor tick failed", t) }
            }
        }
    }

    val maxConnections: Int get() = factory.maxConnections

    val createdConnections: Int get() = createdConnectionCount.get()

    val activeConnections: Int get() = activeConnectionCount.get()

    private suspend fun validateIdleConnections() = withContext(NonCancellable) {
        repeat(createdConnections) {
            val connection = availableConnections.tryReceive().getOrNull() ?: return@repeat
            val keep = runCatching {
                withTimeout(validateTimeoutMs) {
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

    private suspend fun createConnectionIfPossible(): T? = withContext(NonCancellable) {
        try {
            val current = createdConnectionCount.incrementAndGet()
            if (current > factory.maxConnections) {
                createdConnectionCount.decrementAndGet()
                return@withContext null
            }
            val connection = withContext(poolDispatcher) {
                withTimeout(createTimeoutMs) {
                    log.debug("[$name] Creating a new connection")
                    val connection = factory.create()
                    log.debug("[$name] New connection created")
                    connection
                }
            }
            allConnectionsMutex.withLock {
                allConnections.add(connection)
            }
            connection
        } catch (t: Throwable) {
            log.error("[$name] Failed to create a new connection", t)
            createdConnectionCount.decrementAndGet()
            throw t
        }
    }

    private suspend fun discardConnection(connection: T, cause: Throwable? = null) {
        log.warn("[$name] Discarding a connection", cause)
        try {
            withContext(NonCancellable) {
                withTimeout(validateTimeoutMs) {
                    runCatching { factory.destroy(connection) }
                        .onFailure { log.error("[$name] Failed to close connection", it) }
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
                createConnectionIfPossible()?.let {
                    availableConnections.send(it)
                }
            }
        }
    }

    private suspend fun isHealthyForPool(connection: T): Boolean {
        return try {
            if (!factory.isAlive(connection)) {
                log.error("[$name] Connection failed health check: not alive")
                return false
            }
            if (!factory.validate(connection)) {
                log.error("[$name] Connection failed health check: invalid")
                return false
            }
            true
        } catch (t: Throwable) {
            log.error("[$name] Connection failed health check", t)
            false
        }
    }

    suspend fun obtain(): T {
        availableConnections.tryReceive().getOrNull()?.let { connection ->
            if (factory.isAlive(connection)) {
                activeConnectionCount.incrementAndGet()
                return connection
            }
            discardConnection(connection, IllegalStateException("Available connection is closed"))
        }

        createConnectionIfPossible()?.let { connection ->
            activeConnectionCount.incrementAndGet()
            return connection
        }

        val connection = try {
            waitingOnConnection.incrementAndGet()
            withTimeout(acquireTimeoutMs) { availableConnections.receive() }
        } finally {
            waitingOnConnection.decrementAndGet()
        }

        if (!factory.isAlive(connection)) {
            discardConnection(connection, IllegalStateException("Received closed connection"))
            return obtain()
        }

        activeConnectionCount.incrementAndGet()
        return connection
    }

    suspend fun release(connection: T) = withContext(NonCancellable) {
        activeConnectionCount.decrementAndGet()

        if (!factory.isAlive(connection)) {
            discardConnection(connection)
            return@withContext
        }

        try {
            availableConnections.send(connection)
        } catch (ce: CancellationException) {
            log.warn("[$name] Release was cancelled, closing connection", ce)
            discardConnection(connection, ce)
            throw ce
        } catch (t: Throwable) {
            log.warn("[$name] Release failed, closing connection", t)
            discardConnection(connection, t)
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        availableConnections.close()
        monitorJob.cancel()
        val snapshot = allConnectionsMutex.withLock {
            val snapshot = allConnections.toList()
            allConnections.clear()
            snapshot
        }
        snapshot.forEach { runCatching { factory.destroy(it) } }
        createdConnectionCount.set(0)
        activeConnectionCount.set(0)
    }

    companion object {

        private val log = LoggerFactory.getLogger(GenericConnectionPool::class.java)
    }
}
