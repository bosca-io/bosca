@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops

import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.service.CommitStatusService
import bosca.git.service.PipelineRunService
import bosca.git.service.RepositoryWriteService
import bosca.pubsub.PubSubService
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.workops.deploy.DeployConfigService
import bosca.workops.repository.SpecRepository
import bosca.workops.service.ArtifactPublicationService
import bosca.workops.service.EnvironmentService
import bosca.workops.service.EnvironmentTypeService
import bosca.workops.service.NotificationDeliveryService
import bosca.workops.service.ProjectRepositoryService
import bosca.workops.service.ProjectService
import bosca.workops.service.ReleaseService
import bosca.workops.service.VersionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WorkOpsModuleTest {

    private val pubSubService = mockk<PubSubService>()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        every {
            pubSubService.subscribe(any(), any<DeserializationStrategy<Any>>())
        } returns flow { throw CancellationException("test subscription complete") }

        provides<NotificationDeliveryService> { mockk() }
        provides<PubSubService> { pubSubService }
        provides<SpecRepository> { mockk() }
        provides<ConnectionPool> { mockk() }
        provides<EnvironmentService> { mockk() }
        provides<EnvironmentTypeService> { mockk() }
        provides<ProjectRepositoryService> { mockk() }
        provides<ProjectService> { mockk() }
        provides<PipelineRunService> { mockk() }
        provides<ReleaseService> { mockk() }
        provides<VersionService> { mockk() }
        provides<ArtifactPublicationService> { mockk() }
        provides<DeployConfigService> { mockk() }
        provides<RepositoryWriteService> { mockk() }
        provides<CommitStatusService> { mockk() }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `install resolves every dependency and starts all workops subscribers`() = runTest {
        WorkOpsModule().install(mockk<BoscaApplication>(relaxed = true))

        verify(timeout = 5_000) {
            pubSubService.subscribe(
                "bosca.content.metadata.updated",
                any<DeserializationStrategy<Any>>(),
            )
            pubSubService.subscribe(
                "bosca.git.pipeline.environments",
                any<DeserializationStrategy<Any>>(),
            )
            pubSubService.subscribe(
                "bosca.git.pipeline",
                any<DeserializationStrategy<Any>>(),
            )
            pubSubService.subscribe(
                "bosca.git.push",
                any<DeserializationStrategy<Any>>(),
            )
        }
    }

    @Test
    fun `application shutdown waits for every WorkOps subscription to close`() = runTest {
        val started = CountDownLatch(4)
        val closed = AtomicInteger()
        every {
            pubSubService.subscribe(any(), any<DeserializationStrategy<Any>>())
        } returns flow {
            started.countDown()
            try {
                awaitCancellation()
            } finally {
                closed.incrementAndGet()
            }
        }
        val application = BoscaApplication(ApplicationConfig.load("{}".byteInputStream()), ownsServices = false)
        try {
            WorkOpsModule().install(application)
            assertTrue(started.await(5, TimeUnit.SECONDS), "All four subscriptions must start")
        } finally {
            application.shutdown()
        }
        assertEquals(4, closed.get())
    }
}
