package bosca.pool

/**
 * Factory and lifecycle manager for pooled connections of type [T].
 *
 * Provides the operations a connection pool needs to create, validate, and tear down
 * connections. Implementations define the connection type (e.g., JDBC, Redis, NATS)
 * and the validation/cleanup logic specific to that transport.
 *
 * @param T the connection type managed by this factory
 */
interface PoolableConnectionFactory<T> {

    /** The maximum number of connections the pool should maintain. */
    val maxConnections: Int

    /**
     * Creates a new connection instance.
     *
     * @return a newly established connection
     */
    suspend fun create(): T

    /**
     * Validates that the given [connection] is still usable (e.g., not closed, responsive to a ping).
     *
     * @param connection the connection to validate
     * @return `true` if the connection is valid and can be returned to the pool
     */
    suspend fun validate(connection: T): Boolean

    /**
     * Permanently closes and releases any resources held by the given [connection].
     *
     * @param connection the connection to destroy
     */
    suspend fun destroy(connection: T)

    /**
     * Performs a quick, non-suspending check to determine if the [connection] is still alive.
     *
     * Unlike [validate], this is intended for fast synchronous checks (e.g., checking a closed flag)
     * rather than performing I/O.
     *
     * @param connection the connection to check
     * @return `true` if the connection appears to be alive
     */
    fun isAlive(connection: T): Boolean
}
