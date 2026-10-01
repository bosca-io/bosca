package bosca.cache.nats

import bosca.cache.serializers.StringKeySerializer
import bosca.nats.NatsConnectionPool
import bosca.test.resources.SharedNatsContainer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.testcontainers.containers.wait.strategy.Wait
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NatsCacheEndToEndTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var natsPool: NatsConnectionPool

    @BeforeTest
    fun setup() {
        natsContainer = SharedNatsContainer()
            .withExposedPorts(4222)
            .withCommand("-js")
            .withReuse(true)
            .waitingFor(Wait.forListeningPort())
        natsContainer.start()
        natsPool = natsContainer.newConnectionPool(1)
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (this@NatsCacheEndToEndTest::natsPool.isInitialized) natsPool.close()
        if (this@NatsCacheEndToEndTest::natsContainer.isInitialized) natsContainer.stop()
    }

    @Test
    fun `pool uses virtual threads for NATS executors and owns their lifecycle`(): Unit = runBlocking {
        val connection = natsPool.systemConnection()
        val executor = connection.options.executor
        val scheduledExecutor = connection.options.scheduledExecutor

        assertTrue(executor.submit<Boolean> { Thread.currentThread().isVirtual }.get(5, TimeUnit.SECONDS))
        assertTrue(
            scheduledExecutor.schedule<Boolean>(
                { Thread.currentThread().isVirtual },
                0,
                TimeUnit.MILLISECONDS,
            ).get(5, TimeUnit.SECONDS),
        )

        natsPool.close()

        assertTrue(executor.isShutdown)
        assertTrue(scheduledExecutor.isShutdown)
    }

    @Test
    fun `get and touch extends the NATS expiration deadline`(): Unit = runBlocking {
        withTimeout(30.seconds) {
            val cacheName = "ttl-touch-${System.nanoTime()}"
            val cache = NatsCache.newCache(natsPool, cacheName, 12.seconds, StringKeySerializer)
            val touchedKey = StringKeySerializer.toLocalKey(cacheName, "touched")
            val untouchedKey = StringKeySerializer.toLocalKey(cacheName, "untouched")

            suspend fun awaitExpired(key: bosca.cache.CacheKey<String>, timeout: kotlin.time.Duration) {
                withTimeout(timeout) {
                    while (cache.get(key).exists) delay(100)
                }
            }

            cache.put(touchedKey, "value")
            cache.put(untouchedKey, "control")

            // Renew far enough from both deadlines that scheduler and container jitter cannot
            // turn the proof into a race at either boundary.
            delay(5.seconds)
            val touched = cache.getAndTouch(touchedKey)
            assertTrue(touched.exists)
            assertEquals("value", touched.value)

            awaitExpired(untouchedKey, 9.seconds)
            assertFalse(cache.get(untouchedKey).exists, "an untouched control entry must expire")
            assertTrue(cache.get(touchedKey).exists, "the touched entry must survive its original deadline")

            awaitExpired(touchedKey, 7.seconds)
            assertFalse(cache.get(touchedKey).exists, "the touched entry must expire from its renewed deadline")
        }
    }

    @Test
    fun `ellipsis and underscore slugs remain distinct in NATS`(): Unit = runBlocking {
        withTimeout(30.seconds) {
            val cacheName = "ellipsis-${System.nanoTime()}"
            val cache = NatsCache.newCache(natsPool, cacheName, 30.seconds, StringKeySerializer)
            val ellipsis = StringKeySerializer.toLocalKey(cacheName, "when-i-feel...")
            val underscore = StringKeySerializer.toLocalKey(cacheName, "when-i-feel_")

            cache.put(ellipsis, "dots")
            cache.put(underscore, "underscore")

            assertTrue(cache.get(ellipsis).exists)
            assertEquals("dots", cache.get(ellipsis).value)
            assertEquals("underscore", cache.get(underscore).value)
        }
    }

    @Test
    fun `concurrent same-key touches all succeed against NATS`(): Unit = runBlocking {
        withTimeout(30.seconds) {
            val cacheName = "concurrent-touch-${System.nanoTime()}"
            val cache = NatsCache.newCache(natsPool, cacheName, 30.seconds, StringKeySerializer)
            val key = StringKeySerializer.toLocalKey(cacheName, "shared")
            cache.put(key, "value")

            val touched = coroutineScope {
                List(5) { async { cache.getAndTouch(key) } }.awaitAll()
            }

            assertTrue(touched.all { it.exists && it.value == "value" })
            assertTrue(cache.get(key).exists)
        }
    }
}
