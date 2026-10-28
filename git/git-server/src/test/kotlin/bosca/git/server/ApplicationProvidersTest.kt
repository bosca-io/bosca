@file:OptIn(InternalDI::class)

package bosca.git.server

import bosca.di.MissingObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provideProvider
import bosca.di.provide
import bosca.di.provides
import bosca.git.service.ProducedArtifactVerifier
import bosca.git.service.RequiredArtifactVerifier
import bosca.pubsub.PubSubService
import bosca.storage.service.ObjectStorageService
import bosca.pipelines.PipelineEventDispatcher
import bosca.scheduler.service.SchedulerService
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationProvidersTest {

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `git server registers the scheduler service required by git ci`() {
        ProviderRegistry.clear()
        gitServerProviderRegistrars().forEach { it.register() }

        assertFalse(provideProvider<SchedulerService>() is MissingObjectProvider<*>)
    }

    @Test
    fun `git server temporarily registers the event pipeline dispatcher`() {
        ProviderRegistry.clear()
        gitServerProviderRegistrars().forEach { it.register() }

        assertFalse(provideProvider<PipelineEventDispatcher>() is MissingObjectProvider<*>)
    }

    @Test
    fun `CI artifact verifiers resolve through the Git server provider graph`() = runTest {
        ProviderRegistry.clear()
        gitServerProviderRegistrars().forEach { it.register() }
        provides<ObjectStorageService>(singleton = true, overrideExisting = true) { mockk(relaxed = true) }
        provides<PubSubService>(singleton = true, overrideExisting = true) { mockk(relaxed = true) }

        assertTrue(provide<ProducedArtifactVerifier>().missing(emptyList()).isEmpty())
        assertTrue(provide<RequiredArtifactVerifier>().unsatisfied(emptyList()).isEmpty())
    }
}
