package bosca.analytics.livesessions.redis

import bosca.analytics.model.LiveSession
import bosca.redis.RedisConnectionPool
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.every
import io.lettuce.core.KeyScanCursor
import io.lettuce.core.ScanArgs
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Key/channel naming and version filtering for [RedisLiveSessions]. */
@OptIn(io.lettuce.core.ExperimentalLettuceCoroutinesApi::class)
class RedisLiveSessionsTest {

    private val session = LiveSession(sessionId = "s1", latitude = 1.0, longitude = 2.0, appVersion = "1.0.0")

    @Test
    fun `namespaces the sorted-set key and the channel per app`() {
        assertEquals("sessions.geo:app-1", RedisLiveSessions.key("app-1"))
        assertEquals("sessions.geo.ch:app-1", RedisLiveSessions.channel("app-1"))
    }

    @Test
    fun `the all-apps scan prefix and channel pattern cover every per-app key and channel`() {
        assertEquals(RedisLiveSessions.key("app-1"), "${RedisLiveSessions.KEY_PREFIX}app-1")
        assertEquals("sessions.geo.ch:*", RedisLiveSessions.CHANNEL_PATTERN)
    }

    @Test
    fun `matches every session when no version filter is set`() {
        assertTrue(RedisLiveSessions.matches(session, null))
        assertTrue(RedisLiveSessions.matches(session.copy(appVersion = null), null))
    }

    @Test
    fun `matches only the requested version when a filter is set`() {
        assertTrue(RedisLiveSessions.matches(session, "1.0.0"))
        assertFalse(RedisLiveSessions.matches(session, "2.0.0"))
        assertFalse(RedisLiveSessions.matches(session.copy(appVersion = null), "1.0.0"))
    }

    @Test
    fun `decode accepts sessions and rejects malformed members`() {
        val service = RedisLiveSessions(mockk<RedisConnectionPool>(), Json)
        val encoded = Json.encodeToString(LiveSession.serializer(), session)
        assertEquals(session, service.decode(encoded))
        assertEquals(null, service.decode("not-json"))
    }

    @Test
    fun `scanKeys follows cursors and handles an absent first cursor`() = runTest {
        val service = RedisLiveSessions(mockk<RedisConnectionPool>(), Json)
        val commands = mockk<RedisCoroutinesCommands<String, String>>()
        val first = mockk<KeyScanCursor<String>>()
        val second = mockk<KeyScanCursor<String>>()
        every { first.keys } returns listOf("first")
        every { first.isFinished } returns false
        every { second.keys } returns listOf("second")
        every { second.isFinished } returns true
        coEvery { commands.scan(any<ScanArgs>()) } returns first andThen null
        coEvery { commands.scan(first, any<ScanArgs>()) } returns second

        assertEquals(listOf("first", "second"), service.scanKeys(commands))
        assertEquals(emptyList(), service.scanKeys(commands))
    }

    @Test
    fun `subscriptions are reference counted per target`() = runTest {
        val service = RedisLiveSessions(mockk<RedisConnectionPool>(), Json)

        assertTrue(service.acquireSubscription("target"))
        assertFalse(service.acquireSubscription("target"))
        assertFalse(service.releaseSubscription("target"))
        assertTrue(service.releaseSubscription("target"))
    }
}
