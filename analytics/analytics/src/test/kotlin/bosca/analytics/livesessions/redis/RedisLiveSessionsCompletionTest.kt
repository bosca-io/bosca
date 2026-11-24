@file:OptIn(io.lettuce.core.ExperimentalLettuceCoroutinesApi::class)

package bosca.analytics.livesessions.redis

import bosca.redis.RedisConnection
import bosca.redis.RedisConnectionPool
import bosca.redis.RedisPubSubConnection
import io.lettuce.core.KeyScanCursor
import io.lettuce.core.ScanArgs
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands
import io.mockk.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.test.Test
import kotlin.test.assertTrue

class RedisLiveSessionsCompletionTest {
    @Test fun `normal tail completion releases the last app or pattern subscriber and preserves other holders`(): Unit = runBlocking {
        val pool = mockk<RedisConnectionPool>()
        val connection = mockk<RedisConnection>()
        val pubSubConnection = mockk<RedisPubSubConnection>()
        val snapshot = mockk<RedisCoroutinesCommands<String, String>>()
        val commands = mockk<RedisCoroutinesCommands<String, String>>()
        val reactive = mockk<RedisPubSubReactiveCommands<String, String>>()
        coEvery { pool.connection() } returns connection
        coEvery { pool.release(connection) } just Runs
        coEvery { pool.newPubSubConnection() } returns pubSubConnection
        val cursor = mockk<KeyScanCursor<String>>()
        every { cursor.keys } returns emptyList()
        every { cursor.isFinished } returns true
        coEvery { snapshot.scan(any<ScanArgs>()) } returns cursor
        coEvery { snapshot.zremrangebyscore(any<String>(), any()) } returns 0
        every { snapshot.zrangebyscore(any<String>(), any()) } returns emptyFlow()
        coEvery { commands.ping() } returns "PONG"
        every { pubSubConnection.reactive() } returns reactive
        every { reactive.subscribe(any<String>()) } returns Mono.empty()
        every { reactive.psubscribe(any<String>()) } returns Mono.empty()
        every { reactive.unsubscribe(any<String>()) } returns Mono.empty()
        every { reactive.punsubscribe(any<String>()) } returns Mono.empty()
        every { reactive.observeChannels() } returns Flux.empty()
        every { reactive.observePatterns() } returns Flux.empty()
        mockkStatic("io.lettuce.core.api.StatefulRedisConnectionExtensionsKt")
        try {
            every { connection.coroutines() } returns snapshot
            every { pubSubConnection.coroutines() } returns commands
            val service = RedisLiveSessions(pool, Json)
            assertTrue(service.subscribe("normal").toList().isEmpty())
            verify(exactly = 1) { reactive.unsubscribe(RedisLiveSessions.channel("normal")) }
            assertTrue(service.subscribe(null).toList().isEmpty())
            verify(exactly = 1) { reactive.punsubscribe(RedisLiveSessions.CHANNEL_PATTERN) }
            val held = RedisLiveSessions.channel("held")
            assertTrue(service.acquireSubscription(held))
            assertTrue(service.subscribe("held").toList().isEmpty())
            verify(exactly = 0) { reactive.unsubscribe(held) }
            assertTrue(service.releaseSubscription(held))
        } finally { unmockkStatic("io.lettuce.core.api.StatefulRedisConnectionExtensionsKt") }
    }
}
