package bosca.db

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.sql.PreparedStatement
import java.sql.Savepoint
import java.util.*
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Lifecycle callbacks invoked by [ConnectionManager] during transaction and connection events.
 *
 * Register callbacks via [ConnectionManager.addCallback] to perform side effects (e.g., cache
 * invalidation, event dispatch) that should only occur after a transaction completes successfully,
 * or to perform cleanup when the connection is released.
 */
interface ConnectionManagerCallback {

    /**
     * Called after a transaction is successfully committed.
     * Use this for actions that depend on data having been persisted (e.g., publishing events).
     */
    suspend fun onCommit()

    /**
     * Called after a transaction is rolled back.
     * Default implementation is a no-op. Override to perform rollback-specific cleanup.
     */
    suspend fun onRollback() {
    }

    /**
     * Called when the [ConnectionManager] releases its underlying connection back to the pool.
     * This is invoked regardless of whether a transaction was committed or rolled back.
     */
    suspend fun onRelease()
}

class ConnectionManager(
    private val pool: ConnectionPool
) {

    private var _connection: PoolConnection? = null
    private var transactionCallbacks: MutableList<ConnectionManagerCallback>? = null
    private var releaseCallbacks: MutableList<ConnectionManagerCallback>? = null
    private var savepointCounter = 0
    private var savepoints: Stack<Savepoint>? = null
    private var released = false
    private val connectionMutex = Mutex()

    private suspend fun connection(): PoolConnection {
        if (released) error("Connection manager released")
        _connection?.let { return it }
        connectionMutex.withLock {
            if (released) error("Connection manager released")
            _connection?.let { return it }
            val connection = pool.obtainConnection()
            if (released) {
                pool.releaseConnection(connection)
                error("Connection manager released")
            }
            _connection = connection
            return connection
        }
    }

    fun addCallback(callback: ConnectionManagerCallback) {
        if (transactionCallbacks == null) transactionCallbacks = mutableListOf()
        transactionCallbacks!!.add(callback)
        if (releaseCallbacks == null) releaseCallbacks = mutableListOf()
        releaseCallbacks!!.add(callback)
    }

    var inTransaction: Boolean = false
        private set

    var needsCommitOrRollback: Boolean = false
        private set

    suspend fun <T> createArrayOf(typeName: String, elements: Array<T>): java.sql.Array = connection().createArrayOf(typeName, elements)

    suspend fun <T> useStatement(sql: String, block: suspend (stmt: PreparedStatement) -> T): T = withContext(DatabaseDispatcher) {
        val connection = connection()
        withContext(NonCancellable) {
            try {
                connection.prepareStatement(sql).use { statement ->
                    block(statement)
                }
            } finally {
                if (!inTransaction && !needsCommitOrRollback) {
                    withContext(NonCancellable) {
                        internalRelease()
                    }
                }
            }
        }
    }

    suspend fun <T> useReadOnlyStatement(sql: String, block: suspend (stmt: PreparedStatement) -> T): T = withContext(DatabaseDispatcher) {
        val connection = connection()
        connection.readOnly = true
        withContext(NonCancellable) {
            try {
                withTimeout(5000.milliseconds) {
                    connection.prepareStatement(sql).use { statement ->
                        block(statement)
                    }
                }
            } finally {
                if (!inTransaction && !needsCommitOrRollback) {
                    withContext(NonCancellable) {
                        internalRelease()
                    }
                }
            }
        }
    }

    fun markNeedsCommitOrRollback() {
        if (inTransaction) {
            needsCommitOrRollback = true
        }
    }

    suspend fun beginTransaction(): Unit = withContext(DatabaseDispatcher) {
        val connection = connection()
        if (inTransaction) {
            if (savepoints == null) savepoints = Stack()
            val savepoint = savepointCounter++
            savepoints!!.push(connection.setSavepoint("SAVEPOINT_${savepoint}"))
        } else {
            if (connection.autoCommit) {
                connection.autoCommit = false
            }
            inTransaction = true
            needsCommitOrRollback = true
        }
    }

    private val hasSavepoint: Boolean
        get() = savepoints != null && savepoints!!.isNotEmpty()

    suspend fun rollbackTransaction() = withContext(DatabaseDispatcher) {
        val connection = _connection ?: return@withContext
        withContext(NonCancellable) {
            if (internalRollbackTransaction(connection)) {
                internalRelease()
            }
        }
    }

    suspend fun commitTransaction(all: Boolean = false): Unit = withContext(DatabaseDispatcher) {
        val connection = _connection ?: return@withContext
        withContext(NonCancellable) {
            if (internalCommitTransaction(connection, all)) {
                internalRelease()
            }
        }
    }

    private suspend fun internalRollbackTransaction(connection: PoolConnection): Boolean {
        if (hasSavepoint) {
            val savepoint = savepoints?.pop() ?: error("No savepoint to rollback to")
            connection.rollback(savepoint)
            return false
        } else {
            if (!connection.autoCommit) {
                connection.rollback()
                connection.autoCommit = true
            }
            inTransaction = false
            needsCommitOrRollback = false
            val callbacks = transactionCallbacks
            transactionCallbacks = null
            callbacks?.forEach { it.onRollback() }
            return true
        }
    }

    private suspend fun internalCommitTransaction(connection: PoolConnection, all: Boolean = false): Boolean {
        if (hasSavepoint && !all) {
            savepoints!!.pop()
            return false
        } else {
            if (!connection.autoCommit) {
                connection.commit()
                connection.autoCommit = true
            }
            inTransaction = false
            needsCommitOrRollback = false
            val callbacks = transactionCallbacks
            transactionCallbacks = null
            callbacks?.forEach {
                try {
                    it.onCommit()
                } catch (e: Exception) {
                    log.error("Error in commit callback", e)
                }
            }
            savepoints = null
            savepointCounter = 0
            return true
        }
    }

    private suspend fun internalRelease() {
        connectionMutex.withLock {
            val connection = _connection ?: return
            _connection = null
            try {
                if (inTransaction) internalRollbackTransaction(connection)
                else if (needsCommitOrRollback) internalCommitTransaction(connection)
            } finally {
                pool.releaseConnection(connection)
            }
        }
    }

    suspend fun release() {
        connectionMutex.withLock {
            if (released) return
            released = true
        }
        withContext(NonCancellable) {
            internalRelease()
        }
        assert(_connection == null)
        val callbacks = releaseCallbacks
        releaseCallbacks = null
        callbacks?.forEach { it.onRelease() }
    }

    companion object {

        private val log = LoggerFactory.getLogger(ConnectionManager::class.java)
    }
}

suspend fun <T> ConnectionManager.use(block: suspend (ConnectionManager) -> T): T {
    return try {
        withContext(DatabaseDispatcher) {
            block(this@use)
        }
    } finally {
        withContext(DatabaseDispatcher + NonCancellable) {
            release()
        }
    }
}

fun ConnectionManager.asCoroutineContext(): CoroutineContext = ConnectionManagerContext(this)

private class ConnectionManagerContext(val manager: ConnectionManager) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ConnectionManagerContext>

    override val key: CoroutineContext.Key<*> get() = Key
}

suspend fun connection(): ConnectionManager = currentCoroutineContext().connection()

suspend fun <T> transaction(block: suspend () -> T): T {
    val manager = connection()
    try {
        withContext(NonCancellable) {
            manager.beginTransaction()
        }
        val response = block()
        withContext(NonCancellable) {
            manager.commitTransaction()
        }
        return response
    } catch (e: Exception) {
        try {
            withContext(NonCancellable) {
                manager.rollbackTransaction()
            }
        } catch (err: Exception) {
            log.error("Error rolling back transaction", err)
        }
        throw e
    }
}

fun CoroutineContext.connection(): ConnectionManager {
    return this[ConnectionManagerContext.Key]?.manager ?: throw IllegalStateException("Connection not found in coroutine context")
}

suspend fun connectionOrNull(): ConnectionManager? = currentCoroutineContext()[ConnectionManagerContext.Key]?.manager

/**
 * Defers [block] to run ONLY after the current (outermost) transaction COMMITS — never on rollback — so a
 * non-transactional side effect (a distributed-cache counter, an external call) stays consistent with the DB
 * write that gates it. Use this for "consume X iff the change actually persists" semantics.
 *
 * When there is nothing to wait for — no active connection, or a connection with no open transaction (an
 * auto-commit context) — [block] runs immediately. The block is therefore never silently dropped: it is
 * deferred only while a transaction is genuinely in flight (whose commit will fire it).
 */
suspend fun afterCommit(block: suspend () -> Unit) {
    val manager = connectionOrNull()
    if (manager == null || !manager.inTransaction) {
        block()
        return
    }
    manager.addCallback(object : ConnectionManagerCallback {
        override suspend fun onCommit() = block()
        override suspend fun onRelease() {}
    })
}

val DatabaseDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

private val log = LoggerFactory.getLogger(ConnectionManager::class.java)