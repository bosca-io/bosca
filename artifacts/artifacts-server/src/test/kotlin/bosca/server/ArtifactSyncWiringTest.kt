@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.server

import bosca.artifacts.docker.routes.DockerPutManifest
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.di.*
import bosca.pipelines.PipelineEventDispatcher
import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pubsub.PubSubService
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.server.config.ApplicationConfig
import bosca.server.configuration.*
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.storage.service.ObjectStorageService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Resolves the standalone composition root without loading pipeline execution or credentials. */
class ArtifactSyncWiringTest {
    @AfterTest fun cleanup() = ProviderRegistry.clear()

    @Test
    fun `standalone server resolves registry route and pipeline event producer without a runner`(): Unit = runBlocking {
        ProviderRegistry.clear()
        BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    development: false\n".byteInputStream()))
        ProviderRegistry.register(ArtifactsProviderRegistrar(),
            ArtifactsDockerProviderRegistrar(), CoreArtifactsProviderRegistrar())
        // Test KSP writes an empty same-prefix registrar; load the production generated bindings.
        ProviderRegistry.register(Configuration::class, ConfigurationProvider(), true)
        ProviderRegistry.register(Configuration::class, ConfigurationProvider(), PipelinesJobQueueNames.jobQueue, true)
        ProviderRegistry.register(PipelineEventDispatcher::class,
            ConfigurationpipelineEventDispatcherProviderpipelineEventDispatcherFunctionProvider(), true)
        ProviderRegistry.register(JobQueue::class,
            ConfigurationpipelinesJobQueueProviderpipelinesJobQueueFunctionProvider(), PipelinesJobQueueNames.jobQueue, true)
        provides<ConnectionPool> { mockk(relaxed = true) }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<PubSubService> { mockk(relaxed = true) }
        provides<ObjectStorageService> { mockk(relaxed = true) }
        provides<SecurityService> { mockk(relaxed = true) }
        provides<GroupEvaluator> { GroupEvaluator(provide()) }
        val factory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>()
        every { factory.create(PipelinesJobQueueNames.queue) } returns queue
        provides<JobQueueFactory> { factory }

        assertNotNull(provide<DockerPutManifest>())
        assertNotNull(provide<PipelineEventDispatcher>())
        assertSame(queue, provide<JobQueue>(PipelinesJobQueueNames.jobQueue))
        assertFalse(provideProvider<JobRunner>(PipelinesJobQueueNames.runner).exists)
    }
}
