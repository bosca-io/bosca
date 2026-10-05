package bosca.nats

import bosca.pool.PoolableConnectionFactory
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NatsPoolableConnectionFactory(
    private val options: Options,
    override val maxConnections: Int = 50,
) : PoolableConnectionFactory<Connection> {

    override suspend fun create(): Connection {
        return withContext(Dispatchers.IO) {
            Nats.connectReconnectOnConnect(options)
        }
    }

    override suspend fun validate(connection: Connection): Boolean {
        return try {
            connection.status == Connection.Status.CONNECTED
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun destroy(connection: Connection) {
        try {
            withContext(Dispatchers.IO) {
                connection.close()
            }
        } catch (_: Exception) {
        }
    }

    override fun isAlive(connection: Connection): Boolean {
        return connection.status == Connection.Status.CONNECTED
    }
}
