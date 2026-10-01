package bosca.redis

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.CancellationException

class RedisScriptExecutor(
    private val connections: RedisConnectionPool,
    private val script: String
) {

    @Volatile
    private var cachedHash: String? = null

    suspend fun <T> execute(returnType: ScriptOutputType, keys: Array<String>, vararg values: String): T? {
        val connection = connections.connection()
        try {
            return connection.executeScript(returnType, keys, *values)
        } finally {
            connections.release(connection)
        }
    }

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    suspend fun <T : Any> StatefulRedisConnection<String, String>.executeScript(returnType: ScriptOutputType, keys: Array<String>, vararg values: String): T? {
        val scriptHash = getOrLoadDequeueScriptHash()
        val coroutine = coroutines()
        val result = try {
            coroutine.evalsha<T>(
                scriptHash,
                returnType,
                keys,
                *values,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only handle Redis script eviction errors (NOSCRIPT)
            if (e.isScriptEvictionError()) {
                cachedHash = null
                val newScriptHash = getOrLoadDequeueScriptHash()
                coroutine.evalsha<T>(
                    newScriptHash,
                    returnType,
                    keys,
                    *values,
                )
            } else {
                throw Exception("Redis execution failed: ${e.message} : $script", e)
            }
        }
        return result
    }

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    private suspend fun StatefulRedisConnection<String, String>.getOrLoadDequeueScriptHash(): String {
        return cachedHash ?: run {
            try {
                val script = script.toByteArray()
                val hash = coroutines().scriptLoad(script) ?: error("Failed to load script: Redis returned null hash")
                cachedHash = hash
                hash
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error("Failed to load script from Redis: ${e.message}")
            }
        }
    }

    private fun Exception.isScriptEvictionError(): Boolean {
        val message = message?.lowercase() ?: ""
        return message.contains("noscript") ||
                message.contains("script not found") ||
                message.contains("unknown command")
    }
}