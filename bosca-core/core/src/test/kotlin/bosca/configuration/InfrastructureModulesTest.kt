@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.configuration

import bosca.cache.CacheManager
import bosca.cache.CacheModule
import bosca.cache.nats.NatsCacheManager
import bosca.cache.redis.RedisCacheManager
import bosca.cdn.CdnManager
import bosca.cdn.CdnModule
import bosca.cdn.CloudflareCdnManager
import bosca.cdn.NoOpCdnManager
import bosca.counter.Counter
import bosca.counter.CounterModule
import bosca.counter.nats.NatsCounter
import bosca.counter.redis.RedisCounter
import bosca.di.ProviderRegistry
import bosca.di.ObjectProvider
import bosca.di.provide
import bosca.di.provides
import bosca.http.Client
import bosca.http.HttpModule
import bosca.nats.NatsConnectionPool
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.lock.redis.RedisDistributedLockFactory
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.CorsMiddleware
import bosca.server.middleware.DefaultHeadersMiddleware
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InfrastructureModulesTest {

    private fun app(yaml: String = ""): BoscaApplication =
        BoscaApplication(ApplicationConfig.load(yaml.byteInputStream()))

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `Redis module skips absent config and registers configured pool and shutdown`() = runTest {
        val absent = app()
        absent.install(RedisModule())
        assertTrue(ProviderRegistry.findAll(RedisConnectionPool::class).isEmpty())

        ProviderRegistry.clear()
        val configured = app(
            """
            redis:
              host: 127.0.0.1
              port: 6379
            """.trimIndent(),
        )
        configured.install(RedisModule())
        assertEquals(1, ProviderRegistry.findAll(RedisConnectionPool::class).size)
        assertIs<RedisConnectionPool>(provide<RedisConnectionPool>())
        configured.shutdown()
    }

    @Test
    fun `NATS module skips absent config and supports default and explicit pool sizes`() = runTest {
        val absent = app()
        absent.install(NatsModule())
        assertTrue(ProviderRegistry.findAll(NatsConnectionPool::class).isEmpty())

        for (maxConnections in listOf(null, 3)) {
            ProviderRegistry.clear()
            val max = maxConnections?.let { "  maxConnections: $it\n" }.orEmpty()
            val configured = app("nats:\n  url: nats://127.0.0.1:4222\n  token: token\n$max")
            configured.install(NatsModule())
            assertEquals(1, ProviderRegistry.findAll(NatsConnectionPool::class).size)
            assertIs<NatsConnectionPool>(provide<NatsConnectionPool>())
            configured.shutdown()
        }
    }

    @Test
    fun `NATS module selects account credentials over the legacy token`() = runTest {
        val account = app("nats:\n  url: nats://127.0.0.1:4222\n  token: legacy\n  username: sitea\n  password: secret")
        account.install(NatsModule())
        assertIs<NatsConnectionPool>(provide<NatsConnectionPool>())
        account.shutdown()

        // Incomplete account credentials and a missing token are logged, never fatal to startup.
        for (yaml in listOf(
            "nats:\n  url: nats://127.0.0.1:4222\n  token: legacy\n  username: sitea",
            "nats:\n  url: nats://127.0.0.1:4222\n  password: secret",
            "nats:\n  url: nats://127.0.0.1:4222",
        )) {
            ProviderRegistry.clear()
            val partial = app(yaml)
            partial.install(NatsModule())
            assertIs<NatsConnectionPool>(provide<NatsConnectionPool>())
            partial.shutdown()
        }
    }

    @Test
    fun `Redis module accepts a logical database and channel namespace`() = runTest {
        val account = app("redis:\n  host: 127.0.0.1\n  port: 6379\n  database: 1\n  namespace: sitea")
        account.install(RedisModule())
        assertIs<RedisConnectionPool>(provide<RedisConnectionPool>())
        account.shutdown()

        // A missing namespace or an invalid database is logged, never fatal to startup.
        for (yaml in listOf(
            "redis:\n  host: 127.0.0.1\n  port: 6379\n  database: 1",
            "redis:\n  host: 127.0.0.1\n  port: 6379\n  database: -1\n  namespace: sitea",
            "redis:\n  host: 127.0.0.1\n  port: 6379\n  database: -1",
        )) {
            ProviderRegistry.clear()
            val fallback = app(yaml)
            fallback.install(RedisModule())
            assertIs<RedisConnectionPool>(provide<RedisConnectionPool>())
            fallback.shutdown()
        }
    }

    @Test
    fun `cache module selects default Redis and configured NATS and rejects unknown type`() = runTest {
        provides<RedisConnectionPool> { mockk() }
        val redisApp = app()
        redisApp.install(CacheModule())
        assertIs<RedisCacheManager>(provide<CacheManager>())
        redisApp.shutdown()

        ProviderRegistry.clear()
        provides<NatsConnectionPool> { mockk() }
        val natsApp = app("cache:\n  type: nats")
        natsApp.install(CacheModule())
        assertIs<NatsCacheManager>(provide<CacheManager>())

        ProviderRegistry.clear()
        assertFailsWith<IllegalStateException> {
            app("cache:\n  type: unknown").install(CacheModule())
        }
    }

    @Test
    fun `counter module selects both backends and rejects unknown type`() = runTest {
        provides<RedisConnectionPool> { mockk() }
        val redisApp = app()
        redisApp.install(CounterModule())
        assertIs<RedisCounter>(provide<Counter>())

        ProviderRegistry.clear()
        provides<NatsConnectionPool> { mockk() }
        val natsApp = app("counter:\n  type: nats")
        natsApp.install(CounterModule())
        assertIs<NatsCounter>(provide<Counter>())

        ProviderRegistry.clear()
        assertFailsWith<IllegalStateException> {
            app("counter:\n  type: unsupported").install(CounterModule())
        }
    }

    @Test
    fun `CDN module selects no-op and Cloudflare managers`() = runTest {
        val noOpApp = app()
        noOpApp.install(CdnModule())
        assertIs<NoOpCdnManager>(provide<CdnManager>())

        ProviderRegistry.clear()
        val cloudflareApp = app(
            """
            cdn:
              type: CLOUDFLARE
              cloudflare:
                token: secret
                zoneId: zone
            """.trimIndent(),
        )
        cloudflareApp.install(CdnModule())
        assertIs<CloudflareCdnManager>(provide<CdnManager>())
    }

    @Test
    fun `HTTP module always installs defaults and conditionally installs CORS`() = runTest {
        val defaults = app()
        defaults.install(HttpModule())
        assertIs<Client>(provide<Client>())
        assertEquals(1, defaults.middleware.size)
        assertIs<DefaultHeadersMiddleware>(defaults.middleware.single())

        ProviderRegistry.clear()
        val cors = app(
            """
            bosca:
              server:
                development: true
            cors:
              allowAnyHost: true
              allowCredentials: false
              anyMethod: true
              maxAgeSeconds: 60
              allowedHosts: [https://example.com]
              allowedMethods: [GET]
              allowedHeaders: [X-Test]
              exposedHeaders: [X-Result]
            """.trimIndent(),
        )
        cors.install(HttpModule())
        assertEquals(2, cors.middleware.size)
        assertIs<DefaultHeadersMiddleware>(cors.middleware[0])
        assertIs<CorsMiddleware>(cors.middleware[1])
    }

    @Test
    fun `distributed lock configuration selects NATS and Redis providers`() = runTest {
        val natsPool = mockk<NatsConnectionPool>()
        val redisPool = mockk<RedisConnectionPool>()
        val natsProvider = mockk<ObjectProvider<NatsConnectionPool>>()
        val redisProvider = mockk<ObjectProvider<RedisConnectionPool>>()
        coEvery { natsProvider.get() } returns natsPool
        coEvery { redisProvider.get() } returns redisPool
        val configuration = DistributedLockConfiguration()

        assertIs<NatsDistributedLockFactory>(
            configuration.distributedLockFactory(
                app("distributedLock:\n  type: nats"),
                natsProvider,
                redisProvider,
            ),
        )
        assertIs<RedisDistributedLockFactory>(
            configuration.distributedLockFactory(app(), natsProvider, redisProvider),
        )
        assertIs<RedisDistributedLockFactory>(
            configuration.distributedLockFactory(
                app("distributedLock:\n  type: redis"),
                natsProvider,
                redisProvider,
            ),
        )
    }
}
