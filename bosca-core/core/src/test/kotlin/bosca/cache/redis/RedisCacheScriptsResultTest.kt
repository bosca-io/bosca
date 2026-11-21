package bosca.cache.redis

import bosca.cache.StringCacheKey
import bosca.cache.serializers.StringKeySerializer
import bosca.redis.RedisConnectionPool
import bosca.redis.RedisScriptExecutor
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RedisCacheScriptsResultTest {
    private lateinit var scripts: RedisCacheScripts
    private val key = StringCacheKey("batch-test", "item")
    private val pool = mockk<RedisConnectionPool>()
    private val connection = mockk<StatefulRedisConnection<String, String>>()
    private val commands = mockk<RedisReactiveCommands<String, String>>()

    @BeforeTest
    fun setup() {
        coEvery { pool.connection() } returns connection
        coEvery { pool.release(connection) } returns Unit
        every { connection.reactive() } returns commands
        every { commands.scriptLoad(any<ByteArray>()) } returns Mono.just("script-hash")
        scripts = RedisCacheScripts(pool)
    }

    @Test
    fun `batch reads reject missing and incomplete script replies instead of reporting cache misses`() = runTest {
        for (reply in listOf(null, emptyList(), listOf("1"))) {
            batchReply(reply)

            val error = assertFailsWith<IllegalStateException> {
                scripts.getBatch("batch-test", "evictions", StringKeySerializer, listOf(key))
            }
            assertTrue(error.message.orEmpty().contains("incomplete batch"))
        }
    }

    @Test
    fun `get and touch rejects missing and incomplete script replies too`() = runTest {
        for (reply in listOf(null, emptyList(), listOf("1"))) {
            batchReply(reply)

            val error = assertFailsWith<IllegalStateException> {
                scripts.getAndTouchBatch("batch-test", "evictions", StringKeySerializer, listOf(key), 1_000)
            }
            assertTrue(error.message.orEmpty().contains("incomplete get-and-touch batch"))
        }
    }

    @Test
    fun `batch reads preserve empty cached values while distinguishing absent entries`() = runTest {
        batchReply(listOf("1", "", "0", ""))
        val keys = listOf(key, StringCacheKey("batch-test", "missing"))
        val expected = listOf(true to "", false to null)

        assertEquals(expected, scripts.getBatch("batch-test", "evictions", StringKeySerializer, keys))
        assertEquals(expected, scripts.getAndTouchBatch("batch-test", "evictions", StringKeySerializer, keys, 1_000))
    }

    @Test
    fun `optional removal and expiration replies can represent an empty result`() = runTest {
        batchReply(null)
        every { commands.evalsha<Long>(any<String>(), ScriptOutputType.INTEGER, any<Array<String>>(), *anyVararg()) } returns Flux.empty()

        assertTrue(scripts.removePrefix("batch-test", "evictions", StringKeySerializer, key).isEmpty())
        assertTrue(scripts.expiration("batch-test", "evictions").isEmpty())
        assertEquals(0L, scripts.removeBatch("batch-test", "evictions", StringKeySerializer, listOf(key), emptyList()))
    }

    @Test
    fun `empty batches avoid issuing any script commands`() = runTest {
        assertTrue(scripts.getBatch("batch-test", "evictions", StringKeySerializer, emptyList()).isEmpty())
        assertTrue(scripts.getAndTouchBatch("batch-test", "evictions", StringKeySerializer, emptyList(), 1_000).isEmpty())
        assertEquals(0L, scripts.removeBatch("batch-test", "evictions", StringKeySerializer, emptyList(), emptyList()))
        coVerify(exactly = 0) { pool.connection() }
    }

    @Test
    fun `executor reloads every supported script eviction response once and releases its connection`() = runTest {
        for (message in listOf("NOSCRIPT", "script not found", "unknown command")) {
            every { commands.evalsha<Long>(any<String>(), ScriptOutputType.INTEGER, any<Array<String>>(), *anyVararg()) } returnsMany listOf(
                Flux.error(RedisCommandExecutionException(message)), Flux.just(7L),
            )
            assertEquals(7L, RedisScriptExecutor(pool, "return 7").execute<Long>(ScriptOutputType.INTEGER, emptyArray()))
        }
        verify(exactly = 6) { commands.scriptLoad(any<ByteArray>()) }
        coVerify(exactly = 3) { pool.release(connection) }
    }

    @Test
    fun `executor propagates cancellation during script loading without executing or leaking the connection`() = runTest {
        every { commands.scriptLoad(any<ByteArray>()) } returns Mono.error(CancellationException("load cancelled"))

        assertFailsWith<CancellationException> {
            RedisScriptExecutor(pool, "return 1").execute<Long>(ScriptOutputType.INTEGER, emptyArray())
        }
        verify(exactly = 0) { commands.evalsha<Long>(any<String>(), any(), any<Array<String>>(), *anyVararg()) }
        coVerify(exactly = 1) { pool.release(connection) }
    }

    @Test
    fun `executor rejects a missing script hash before evaluation and releases its connection`() = runTest {
        every { commands.scriptLoad(any<ByteArray>()) } returns Mono.empty()

        val error = assertFailsWith<IllegalStateException> {
            RedisScriptExecutor(pool, "return 1").execute<Long>(ScriptOutputType.INTEGER, emptyArray())
        }
        assertTrue(error.message.orEmpty().contains("null hash"))
        verify(exactly = 0) { commands.evalsha<Long>(any<String>(), any(), any<Array<String>>(), *anyVararg()) }
        coVerify(exactly = 1) { pool.release(connection) }
    }

    @Test
    fun `executor propagates evaluation cancellation without retrying and still releases the connection`() = runTest {
        every { commands.evalsha<Long>(any<String>(), ScriptOutputType.INTEGER, any<Array<String>>(), *anyVararg()) } returns
            Flux.error(CancellationException("evaluation cancelled"))

        assertFailsWith<CancellationException> {
            RedisScriptExecutor(pool, "return 1").execute<Long>(ScriptOutputType.INTEGER, emptyArray())
        }
        verify(exactly = 1) { commands.scriptLoad(any<ByteArray>()) }
        coVerify(exactly = 1) { pool.release(connection) }
    }

    @Test
    fun `an evaluation failure with no message is reported without an eviction retry`() = runTest {
        every { commands.evalsha<Long>(any<String>(), ScriptOutputType.INTEGER, any<Array<String>>(), *anyVararg()) } returns
            Flux.error(IllegalArgumentException())

        val error = assertFailsWith<Exception> {
            RedisScriptExecutor(pool, "return 1").execute<Long>(ScriptOutputType.INTEGER, emptyArray())
        }
        assertTrue(error.message.orEmpty().contains("Redis execution failed"))
        verify(exactly = 1) { commands.scriptLoad(any<ByteArray>()) }
        coVerify(exactly = 1) { pool.release(connection) }
    }

    private fun batchReply(reply: List<String>?) {
        every { commands.evalsha<List<String>>(any<String>(), ScriptOutputType.MULTI, any<Array<String>>(), *anyVararg()) } returns
            (reply?.let { Flux.just(it) } ?: Flux.empty())
    }
}
