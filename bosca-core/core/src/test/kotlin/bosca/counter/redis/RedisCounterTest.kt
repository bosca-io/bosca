@file:OptIn(ExperimentalLettuceCoroutinesApi::class)

package bosca.counter.redis

import bosca.redis.RedisConnection
import bosca.redis.RedisConnectionPool
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.KeyValue
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [RedisCounter]: `INCRBY` for the atomic add (with a TTL refresh) and `MGET` for a whole-window read. */
class RedisCounterTest {

    private val commands = mockk<RedisCoroutinesCommands<String, String>>()
    private val connection = mockk<RedisConnection>()
    private val pool = mockk<RedisConnectionPool>()
    private val counter = RedisCounter(pool)

    init {
        mockkStatic("io.lettuce.core.api.StatefulRedisConnectionExtensionsKt")
        every { connection.coroutines() } returns commands
        coEvery { pool.connection() } returns connection
        coEvery { pool.release(connection) } returns Unit
    }

    @AfterTest
    fun teardown() = unmockkAll()

    @Test
    fun `increment adds and returns the new total, refreshing the key TTL`() = runTest {
        coEvery { commands.incrby("req.5xx.42", 3) } returns 8L
        coEvery { commands.expire("req.5xx.42", any<Long>()) } returns true

        assertEquals(8L, counter.increment("req.5xx.42", 3))
        coVerify(exactly = 1) { commands.incrby("req.5xx.42", 3) }
        coVerify(exactly = 1) { commands.expire("req.5xx.42", any<Long>()) }
    }

    @Test
    fun `get returns the value, or zero when absent`() = runTest {
        coEvery { commands.get("a") } returns "12"
        coEvery { commands.get("b") } returns null

        assertEquals(12L, counter.get("a"))
        assertEquals(0L, counter.get("b"))
    }

    @Test
    fun `get(keys) reads a window in one call, missing buckets as zero`() = runTest {
        coEvery { commands.mget("k1", "k2", "k3") } returns
            flowOf(KeyValue.just("k1", "10"), KeyValue.empty("k2"), KeyValue.just("k3", "5"))

        assertEquals(mapOf("k1" to 10L, "k2" to 0L, "k3" to 5L), counter.get(listOf("k1", "k2", "k3")))
    }

    @Test
    fun `get(empty) short-circuits without touching Redis`() = runTest {
        assertEquals(emptyMap(), counter.get(emptyList()))
    }
}
