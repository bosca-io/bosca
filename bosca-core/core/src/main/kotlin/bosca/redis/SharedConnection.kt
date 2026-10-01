@file:OptIn(DelicateCoroutinesApi::class)

package bosca.redis

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SharedConnection(
    private val pool: RedisConnectionPool,
) {

    private var connection: StatefulRedisConnection<String, String>? = null
    private var pubSubConnection: RedisPubSubConnection? = null
    private val mutex = Mutex()

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    suspend fun connection(): StatefulRedisConnection<*, *> {
        if (connection == null) {
            mutex.withLock {
                if (connection == null) {
                    connection = pool.connection()
                }
            }
        }
        try {
            connection!!.coroutines().ping()
        } catch (_: Exception) {
            mutex.withLock {
                try {
                    val connection = connection!!
                    GlobalScope.launch(Dispatchers.IO) {
                        pool.release(connection)
                    }
                } catch (_: Exception) {}
                connection = pool.connection()
            }
        }
        return connection!!
    }

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    suspend fun pubSubConnection(): RedisPubSubConnection {
        if (pubSubConnection == null) {
            mutex.withLock {
                if (pubSubConnection == null) {
                    pubSubConnection = pool.newPubSubConnection()
                }
            }
        }
        try {
            pubSubConnection!!.coroutines().ping()
        } catch (_: Exception) {
            mutex.withLock {
                try {
                    val connection = pubSubConnection!!
                    GlobalScope.launch(Dispatchers.IO) {
                        connection.closeAsync().await()
                    }
                } catch (_: Exception) {}
                pubSubConnection = pool.newPubSubConnection()
            }
        }
        return pubSubConnection!!
    }
}