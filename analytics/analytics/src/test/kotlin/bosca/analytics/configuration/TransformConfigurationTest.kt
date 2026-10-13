@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.configuration

import bosca.analytics.service.AnalyticsScriptBindingService
import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.transform.AnalyticsScriptTransform
import bosca.analytics.transform.ErrorFingerprintTransform
import bosca.analytics.transform.SessionHeartbeatTransform
import bosca.analytics.transform.fums.FumsReporter
import bosca.analytics.transform.fums.FumsAnalyticsTransform
import bosca.analytics.transform.geo.CloudflareGeoTransform
import bosca.counter.Counter
import bosca.di.ObjectProvider
import bosca.di.AnalyticsProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.provide
import bosca.di.provides
import bosca.lock.DistributedLockFactory
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import kotlinx.coroutines.runBlocking
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.test.assertIs

class TransformConfigurationTest {

    @BeforeTest
    fun setup() = ProviderRegistry.clear()

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private val config = TransformConfiguration()

    private fun buildTransforms(
        fums: Boolean = true,
        queue: ObjectProvider<JobQueue> = mockk {
            every { exists } returns true
            coEvery { get() } returns mockk()
        },
    ): EventPipelineTransforms = runBlocking { config.transforms(
        config.cloudflare(),
        mockk<ObjectProvider<AnalyticsScriptBindingService>>(relaxed = true),
        mockk<ObjectProvider<ScriptService>>(relaxed = true),
        mockk<ObjectProvider<ScriptExecutionService>>(relaxed = true),
        mockk<ObjectProvider<SecurityService>>(relaxed = true),
        Json,
        EventProcessingConfiguration(fumsEnabled = fums),
        mockk<Counter>(relaxed = true),
        mockk<ObjectProvider<LiveSessionsService>>(relaxed = true),
        queue,
    ) }

    @Test
    fun `cloudflare creates a CloudflareGeoTransform`() {
        assertTrue(config.cloudflare() is CloudflareGeoTransform)
    }

    @Test
    fun `transforms pipeline starts with geo transform`() {
        val pipeline = buildTransforms()
        assertTrue(pipeline.transforms.isNotEmpty())
        assertTrue(pipeline.transforms[0] is CloudflareGeoTransform)
    }

    @Test
    fun `transforms pipeline has correct number of transforms`() {
        val pipeline = buildTransforms()
        val expected = 7
        assertEquals(expected, pipeline.transforms.size)
        assertTrue(pipeline.transforms[1] is SessionHeartbeatTransform)
        assertTrue(pipeline.transforms[2] is ErrorFingerprintTransform)
        assertTrue(pipeline.transforms[3] is FumsAnalyticsTransform)
        assertTrue(pipeline.transforms[4] is AnalyticsScriptTransform)
    }

    @Test
    fun `server configuration selects whether the list contains FUMS`() {
        assertTrue(buildTransforms().transforms.any { it is FumsAnalyticsTransform })
        val queue = mockk<ObjectProvider<JobQueue>>()
        val disabled = buildTransforms(fums = false, queue = queue)
        coVerify(exactly = 0) { queue.get() }
        assertEquals(6, disabled.transforms.size)
        assertTrue(disabled.transforms.none { it is FumsAnalyticsTransform })
    }

    @Test
    fun `EventPipelineTransforms wraps transform list`() {
        val pipeline = EventPipelineTransforms(emptyList())
        assertTrue(pipeline.transforms.isEmpty())
    }

    @Test
    fun `servers without a FUMS queue omit the transform`() {
        val queue = mockk<ObjectProvider<JobQueue>> { every { exists } returns false }
        assertTrue(buildTransforms(queue = queue).transforms.none { it is FumsAnalyticsTransform })
        coVerify(exactly = 0) { queue.get() }
    }

    @Test
    fun `generated wiring provides a single reporter without credentials`() = runBlocking {
        ProviderRegistry.clear()
        try {
            AnalyticsProviderRegistrar().register()
            assertSame(provide<FumsReporter>(), provide<FumsReporter>())
        } finally {
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `generated FUMS delivery and worker wiring use an isolated queue`() = runBlocking {
        ProviderRegistry.clear()
        try {
            val queue = mockk<JobQueue>()
            val factory = mockk<JobQueueFactory> { every { create("fums") } returns queue }
            AnalyticsProviderRegistrar().register()
            provides<JobQueueFactory> { factory }
            provides<DistributedLockFactory> { mockk() }
            assertSame(queue, provide<JobQueue>("fums"))
            assertSame(queue, provide<JobQueue>("fums"))
            val enqueuer = provide<JobConfigurationEnqueuer>("fums-delivery")
            assertEquals("fums", enqueuer.queueName)
            assertSame(queue, enqueuer.queue())
            assertIs<JobRunner>(provide<JobRunner>("fumsQueueRunner"))
            verify(exactly = 1) { factory.create("fums") }
            verify(exactly = 0) { factory.create("content"); factory.create("analytics") }
        } finally {
            ProviderRegistry.clear()
        }
    }
}
