package bosca.server.configuration

import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.livesessions.nats.NatsLiveSessions
import bosca.analytics.livesessions.redis.RedisLiveSessions
import bosca.di.AnalyticsProcessorProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.asProvider
import bosca.di.annotation.InternalDI
import bosca.di.annotation.Provider
import bosca.nats.NatsConnectionPool
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class ConfigurationTest {

    private val configuration = Configuration()
    private val nats = mockk<NatsConnectionPool>().asProvider()
    private val redis = mockk<RedisConnectionPool>().asProvider()

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `live sessions use their explicit backend`() = runTest {
        val natsApplication = application(
            """
            pubsub:
              type: redis
            liveSessions:
              type: nats
            """,
        )
        val redisApplication = application(
            """
            pubsub:
              type: nats
            liveSessions:
              type: redis
            """,
        )

        assertIs<NatsLiveSessions>(configuration.liveSessions(natsApplication, nats, redis, Json))
        assertIs<RedisLiveSessions>(configuration.liveSessions(redisApplication, nats, redis, Json))
    }

    @Test
    fun `live sessions fall back to pubsub and then redis`() = runTest {
        val natsApplication = application(
            """
            pubsub:
              type: nats
            """,
        )
        val defaultApplication = application("bosca: {}")

        assertIs<NatsLiveSessions>(configuration.liveSessions(natsApplication, nats, redis, Json))
        assertIs<RedisLiveSessions>(configuration.liveSessions(defaultApplication, nats, redis, Json))
    }

    @Test
    fun `generated provider declares and honors singleton scope`() = runTest {
        application(
            """
            pubsub:
              type: nats
            """,
        )
        ProviderRegistry.register(Configuration::class, configuration.asProvider(), true)
        ProviderRegistry.register(NatsConnectionPool::class, nats, true)
        ProviderRegistry.register(RedisConnectionPool::class, redis, true)
        val generated = ConfigurationliveSessionsProviderliveSessionsFunctionProvider()
        val scope = generated.javaClass.getAnnotation(Provider::class.java)
        assertTrue(scope.singleton)
        ProviderRegistry.register(LiveSessionsService::class, generated, scope.singleton)

        val provider = ProviderRegistry.get(LiveSessionsService::class)
        assertTrue(provider.exists)
        assertSame(provider.get(), provider.get())
    }

    @Test
    fun `production registrar includes live sessions as a singleton`() = runTest {
        application(
            """
            pubsub:
              type: nats
            """,
        )
        AnalyticsProcessorProviderRegistrar().register()
        ProviderRegistry.register(NatsConnectionPool::class, nats, true)
        ProviderRegistry.register(RedisConnectionPool::class, redis, true)

        val provider = ProviderRegistry.get(LiveSessionsService::class)

        assertTrue(provider.exists)
        assertSame(provider.get(), provider.get())
    }

    private fun application(yaml: String) =
        BoscaApplication(ApplicationConfig.load(yaml.trimIndent().byteInputStream()))
}
