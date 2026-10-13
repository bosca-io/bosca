@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.server.configuration

import bosca.di.AnalyticsCollectorProviderRegistrar
import bosca.di.AnalyticsProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.asProvider
import bosca.di.provide
import bosca.di.provides
import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ConfigurationTest {
    @BeforeTest
    fun setup() = ProviderRegistry.clear()

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `packaged configuration chooses FUMS transform placement`() {
        assertEquals("true", ApplicationConfig.loadFromClasspath().property("eventProcessing.fumsEnabled").getString())
    }

    @Test
    fun `collector provides either configured job transport`() = runTest {
        val configuration = Configuration()
        val nats = mockk<NatsConnectionPool>().asProvider()
        val redis = mockk<RedisConnectionPool>().asProvider()
        val locks = mockk<DistributedLockFactory>()
        val natsApplication = BoscaApplication(ApplicationConfig.load("jobQueue:\n  factory: nats".byteInputStream()))
        assertIs<NatsJobQueueFactory>(configuration.jobQueueFactory(natsApplication, nats, redis, Json, locks))
        val redisApplication = BoscaApplication(ApplicationConfig.load("bosca: {}".byteInputStream()))
        assertIs<RedisJobQueueFactory>(configuration.jobQueueFactory(redisApplication, nats, redis, Json, locks))
    }

    @Test
    fun `generated collector wiring provides the dedicated FUMS queue`() = runTest {
        val queue = mockk<JobQueue>()
        val factory = mockk<JobQueueFactory> { every { create("fums") } returns queue }
        AnalyticsCollectorProviderRegistrar().register()
        AnalyticsProviderRegistrar().register()
        provides<JobQueueFactory>(overrideExisting = true) { factory }
        assertSame(queue, provide<JobQueue>("fums"))
        assertSame(queue, provide<JobQueue>("fums"))
        verify(exactly = 1) { factory.create("fums") }
    }
}
