@file:OptIn(InternalDI::class)

package bosca.git.server

import bosca.di.MissingObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provideProvider
import bosca.pipelines.PipelineEventDispatcher
import bosca.scheduler.service.SchedulerService
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse

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
}
