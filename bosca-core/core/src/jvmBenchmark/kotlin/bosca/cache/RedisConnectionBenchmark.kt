package bosca.cache

import bosca.cache.redis.RedisCacheScripts
import bosca.redis.RedisConnection
import bosca.redis.RedisConnectionPool
import bosca.redis.RedisScriptExecutor
import bosca.test.resources.SharedValkeyContainer
import io.lettuce.core.ScriptOutputType
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Threads
import java.util.concurrent.ThreadLocalRandom

/**
 * The production single-key read script (`getBatchScript`) through [RedisScriptExecutor], borrowing a pooled
 * connection per call as production does, against the same executor on one shared Lettuce connection, which
 * multiplexes concurrent commands. The difference is the pool's cost and the effect of multiplexing.
 */
@State(Scope.Benchmark)
open class RedisConnectionBenchmark {

    private lateinit var valkey: SharedValkeyContainer
    private lateinit var pool: RedisConnectionPool
    private lateinit var shared: RedisConnection
    private lateinit var read: RedisScriptExecutor
    private val keys = List(KEY_COUNT) { "uuid::$CACHE::$it" }

    @Setup
    open fun setup(): Unit = runBlocking {
        valkey = SharedValkeyContainer().also { it.start() }
        pool = valkey.newConnectionPool(POOL_SIZE)
        val script = RedisCacheScripts::class.java.getDeclaredField("getBatchScript").apply { isAccessible = true }.get(null) as String
        read = RedisScriptExecutor(pool, script)
        shared = pool.connection()
        val seed = RedisScriptExecutor(pool, "for i = 1, #ARGV do redis.call('HSET', KEYS[1], ARGV[i], 'value'); redis.call('ZADD', KEYS[2], 9999999999999, ARGV[i]) end return #ARGV")
        seed.execute<Long>(ScriptOutputType.INTEGER, arrayOf(CACHE, "$CACHE:expirations"), *keys.toTypedArray())
        Unit
    }

    @TearDown
    open fun tearDown(): Unit = runBlocking {
        pool.release(shared)
        valkey.stop()
    }

    private fun args(): Array<String> = arrayOf(CHANNEL, keys[ThreadLocalRandom.current().nextInt(KEY_COUNT)])

    private suspend fun pooled(): List<String>? =
        read.execute(ScriptOutputType.MULTI, arrayOf(CACHE, "$CACHE:expirations"), *args())

    private suspend fun sharedConnection(): List<String>? = with(read) {
        shared.executeScript(ScriptOutputType.MULTI, arrayOf(CACHE, "$CACHE:expirations"), *args())
    }

    @Benchmark
    open fun pooledRead(): List<String>? = runBlocking { pooled() }

    @Benchmark
    open fun sharedConnectionRead(): List<String>? = runBlocking { sharedConnection() }

    @Benchmark
    @Threads(64)
    open fun pooledReadAcross64Callers(): List<String>? = runBlocking { pooled() }

    @Benchmark
    @Threads(64)
    open fun sharedConnectionReadAcross64Callers(): List<String>? = runBlocking { sharedConnection() }

    private companion object {
        const val CACHE = "benchmark:connection"
        const val CHANNEL = "$CACHE:evictions"
        const val KEY_COUNT = 1_000
        const val POOL_SIZE = 50
    }
}
