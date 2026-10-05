@file:OptIn(io.lettuce.core.ExperimentalLettuceCoroutinesApi::class)

package bosca.analytics.livesessions.redis

import bosca.analytics.model.LiveSession
import bosca.redis.RedisConnectionPool
import bosca.test.resources.SharedValkeyContainer
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end tests for [RedisLiveSessions] against a real Redis server (TestContainers). Each test uses
 * a unique appId so the per-app sorted set and channel don't interfere.
 */
class RedisLiveSessionsEndToEndTest {

    private lateinit var redisContainer: SharedValkeyContainer
    private lateinit var redisPool: RedisConnectionPool
    private lateinit var liveSessions: RedisLiveSessions
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        // Force Lettuce onto NIO — the native kqueue/epoll Netty transports aren't on the test
        // classpath (the platform pins/excludes Netty natives), so native detection would NoClassDef.
        System.setProperty("io.lettuce.core.kqueue", "false")
        System.setProperty("io.lettuce.core.epoll", "false")

        redisContainer = SharedValkeyContainer()
        redisContainer.start()

        redisPool = redisContainer.newConnectionPool()
        liveSessions = RedisLiveSessions(redisPool, json)
    }

    @AfterTest
    fun teardown() {
        if (::redisContainer.isInitialized) redisContainer.stop()
    }

    private fun session(id: String, version: String? = null) =
        LiveSession(sessionId = id, latitude = 40.7, longitude = -74.0, appVersion = version)

    private suspend fun addRawSnapshot(appId: String, member: String) {
        val connection = redisPool.connection()
        try {
            connection.coroutines().zadd(RedisLiveSessions.key(appId), System.currentTimeMillis().toDouble(), member)
        } finally {
            redisPool.release(connection)
        }
    }

    private suspend fun publishRawSequence(appId: String, members: List<String>) {
        val connection = redisPool.newPubSubConnection()
        try {
            val commands = connection.coroutines()
            members.forEach { commands.publish(RedisLiveSessions.channel(appId), it) }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `publish then subscribe returns the retained session as a snapshot`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "app-${System.nanoTime()}"
            liveSessions.publish(appId, session("s1", "1.0.0"))

            val received = liveSessions.subscribe(appId).first()

            assertEquals("s1", received.sessionId)
            assertEquals("1.0.0", received.appVersion)
        }
    }

    @Test
    fun `subscribe then publish delivers the live heartbeat on the tail`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "app-${System.nanoTime()}"

            val received = async(Dispatchers.Default) { liveSessions.subscribe(appId).first() }
            delay(1_000)
            liveSessions.publish(appId, session("s-live", "2.0.0"))

            assertEquals("s-live", received.await().sessionId)
        }
    }

    @Test
    fun `concurrent subscribers share and release one app subscription`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "shared-${System.nanoTime()}"
            val first = async(Dispatchers.Default) { liveSessions.subscribe(appId).first() }
            val second = async(Dispatchers.Default) { liveSessions.subscribe(appId).first() }
            delay(1_000)
            liveSessions.publish(appId, session("shared"))
            assertEquals("shared", first.await().sessionId)
            assertEquals("shared", second.await().sessionId)
        }
    }

    @Test
    fun `snapshot skips malformed members before emitting a valid session`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "invalid-snapshot-${System.nanoTime()}"
            addRawSnapshot(appId, "not-json")
            liveSessions.publish(appId, session("valid"))
            assertEquals("valid", liveSessions.subscribe(appId).first().sessionId)
        }
    }

    @Test
    fun `tail skips malformed and mismatched versions before emitting a match`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "filtered-tail-${System.nanoTime()}"
            val ready = CompletableDeferred<Unit>()
            val received = async(Dispatchers.Default) {
                liveSessions.subscribe(appId, "2.0.0")
                    .onEach { if (it.sessionId == "probe") ready.complete(Unit) }
                    .take(2)
                    .toList()
            }
            delay(1_000)
            liveSessions.publish(appId, session("probe", "2.0.0"))
            ready.await()
            publishRawSequence(
                appId,
                listOf(
                    "not-json",
                    json.encodeToString(LiveSession.serializer(), session("wrong", "1.0.0")),
                    json.encodeToString(LiveSession.serializer(), session("right", "2.0.0")),
                ),
            )
            assertEquals(listOf("probe", "right"), received.await().map { it.sessionId })
        }
    }

    @Test
    fun `a shared tail does not unsubscribe while another holder remains`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "held-${System.nanoTime()}"
            val firstReady = CompletableDeferred<Unit>()
            val secondReady = CompletableDeferred<Unit>()
            val firstStillSubscribed = CompletableDeferred<Unit>()
            val first = launch(Dispatchers.Default) {
                liveSessions.subscribe(appId).onEach {
                    if (it.sessionId == "first-ready") firstReady.complete(Unit)
                    if (it.sessionId == "still-subscribed") firstStillSubscribed.complete(Unit)
                }.collect()
            }
            while (!firstReady.isCompleted) {
                liveSessions.publish(appId, session("first-ready"))
                delay(25)
            }

            val second = launch(Dispatchers.Default) {
                liveSessions.subscribe(appId).onEach {
                    if (it.sessionId == "second-ready") secondReady.complete(Unit)
                }.collect()
            }
            while (!secondReady.isCompleted) {
                liveSessions.publish(appId, session("second-ready"))
                delay(25)
            }
            second.cancelAndJoin()

            while (!firstStillSubscribed.isCompleted) {
                liveSessions.publish(appId, session("still-subscribed"))
                delay(25)
            }
            first.cancelAndJoin()
        }
    }

    @Test
    fun `cancelling an all-application tail releases its pattern subscription`(): Unit = runBlocking {
        withTimeout(30_000) {
            val collector = launch(Dispatchers.Default) { liveSessions.subscribe().collect() }

            delay(1_000)
            collector.cancelAndJoin()

            assertEquals(true, liveSessions.acquireSubscription(RedisLiveSessions.CHANNEL_PATTERN))
            assertEquals(true, liveSessions.releaseSubscription(RedisLiveSessions.CHANNEL_PATTERN))
        }
    }

    @Test
    fun `version filter restricts the snapshot to one version`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "app-${System.nanoTime()}"
            liveSessions.publish(appId, session("s1", "1.0.0"))
            liveSessions.publish(appId, session("s2", "2.0.0"))

            val v2 = liveSessions.subscribe(appId, "2.0.0").first()
            val all = liveSessions.subscribe(appId).take(2).toList()

            assertEquals("s2", v2.sessionId)
            assertEquals(setOf("s1", "s2"), all.map { it.sessionId }.toSet())
        }
    }

    @Test
    fun `all-applications subscribe unions every app's snapshot and stamps appIds`(): Unit = runBlocking {
        withTimeout(30_000) {
            // The Redis container is reused, so scope assertions to this test's sessions — an
            // unfiltered subscribe legitimately sees other tests' retained heartbeats.
            val prefix = "all-${System.nanoTime()}"
            liveSessions.publish("$prefix-a", session("$prefix-s1", "1.0.0"))
            liveSessions.publish("$prefix-b", session("$prefix-s2", "2.0.0"))

            val received = liveSessions.subscribe()
                .filter { it.sessionId.startsWith(prefix) }
                .take(2).toList()

            assertEquals(
                mapOf("$prefix-s1" to "$prefix-a", "$prefix-s2" to "$prefix-b"),
                received.associate { it.sessionId to it.appId },
            )
        }
    }

    @Test
    fun `all-applications subscribe delivers live heartbeats from any app on the pattern tail`(): Unit = runBlocking {
        withTimeout(30_000) {
            val prefix = "allt-${System.nanoTime()}"

            val received = async(Dispatchers.Default) {
                liveSessions.subscribe().filter { it.sessionId.startsWith(prefix) }.first()
            }
            delay(1_000)
            liveSessions.publish("$prefix-a", session("$prefix-live", "2.0.0"))

            val session = received.await()
            assertEquals("$prefix-live", session.sessionId)
            assertEquals("$prefix-a", session.appId)
        }
    }

    @Test
    fun `sessions past the TTL age out of the snapshot`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "app-${System.nanoTime()}"
            val shortTtl = RedisLiveSessions(redisPool, json, ttl = 1.seconds)

            shortTtl.publish(appId, session("s-old", "1.0.0"))
            delay(1_500) // s-old is now older than the 1s TTL
            shortTtl.publish(appId, session("s-new", "1.0.0"))

            val snapshot = shortTtl.subscribe(appId).take(1).toList()

            assertEquals(listOf("s-new"), snapshot.map { it.sessionId })
        }
    }
}
