@file:OptIn(ExperimentalAtomicApi::class)

package bosca.db

import bosca.serialization.UUID
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Savepoint
import java.sql.Statement
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

enum class ConnectionState {
    OBTAINED,
    RELEASED
}

class PoolConnection(
    private val connection: Connection,
    private val defaultTransactionIsolation: Int,
    private val active: AtomicBoolean = AtomicBoolean(false),
) {

    val id = UUID.random()

    val isClosed get() = connection.isClosed

    fun validateState(state: ConnectionState) = require(this.state == state) {
        "Connection state mismatch: expected $state, actual ${this.state}"
    }

    var state: ConnectionState = ConnectionState.RELEASED
        internal set

    internal var autoCommit: Boolean
        get() = connection.autoCommit
        set(value) {
            connection.autoCommit = value
        }

    internal var readOnly: Boolean
        get() = connection.isReadOnly
        set(value) {
            connection.isReadOnly = value
        }

    internal fun beginRequest() {
        if (state != ConnectionState.OBTAINED) error("Connection is not obtained")
        connection.beginRequest()
    }

    internal fun markActive() = active.compareAndSet(expectedValue = false, newValue = true)

    internal fun markInactive() = active.compareAndSet(expectedValue = true, newValue = false)

    internal fun endRequest() {
        if (state != ConnectionState.RELEASED) error("Connection is not released")
        connection.endRequest()
    }

    internal fun setSavepoint(name: String? = null): Savepoint {
        if (state != ConnectionState.OBTAINED) error("Connection is not obtained: $state")
        return name?.let { connection.setSavepoint(it) } ?: connection.setSavepoint()
    }

    internal fun rollback(savepoint: Savepoint? = null) {
        if (state != ConnectionState.OBTAINED) error("Connection is not obtained: $state")
        savepoint?.let { connection.rollback(it) } ?: connection.rollback()
    }

    internal fun commit() {
        if (state != ConnectionState.OBTAINED) error("Connection is not obtained: $state")
        connection.commit()
    }

    internal fun <T> createArrayOf(typeName: String, elements: Array<T>): java.sql.Array {
        if (state != ConnectionState.OBTAINED) error("Connection is not obtained: $state")
        return connection.createArrayOf(typeName, elements)
    }

    @Suppress("SqlSourceToSinkFlow")
    fun prepareStatement(sql: String): PreparedStatement {
        if (state != ConnectionState.OBTAINED) error("Connection is not obtained: $state")
        return connection.prepareStatement(sql)
    }

    fun createStatement(): Statement {
        if (state != ConnectionState.OBTAINED) error("Connection is not obtained: $state")
        return connection.createStatement()
    }

    internal fun validate(): Boolean {
        return connection.isValid(2)
    }

    internal fun reset() {
        if (state == ConnectionState.OBTAINED) error("Connection is obtained")

        if (connection.isClosed) error("Connection is closed")

        if (!connection.autoCommit) {
            connection.rollback()
            connection.autoCommit = true
        }

        if (connection.isReadOnly) {
            connection.isReadOnly = false
        }

        if (connection.transactionIsolation != defaultTransactionIsolation) {
            connection.transactionIsolation = defaultTransactionIsolation
        }

        connection.clearWarnings()
    }

    internal fun close() {
        if (state == ConnectionState.OBTAINED) error("Connection is obtained")
        connection.close()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PoolConnection

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}