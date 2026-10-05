@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.PushEvent
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

class AnalyticsModuleTest {
    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun application(enabled: Boolean) = BoscaApplication(
        ApplicationConfig.load("git:\n  sync:\n    listeners: $enabled\n".byteInputStream()),
    )

    @Test
    fun `module honors listener configuration and shuts down enabled listener`() = runTest {
        val pubSub = mockk<PubSubService>()
        val parked: Flow<Message<PushEvent>> = flow { awaitCancellation() }
        coEvery { pubSub.subscribe("bosca.git.push", PushEvent.serializer()) } returns parked
        provides<PubSubService> { pubSub }

        AnalyticsModule().install(application(false))
        coVerify(exactly = 0) { pubSub.subscribe("bosca.git.push", PushEvent.serializer()) }

        val enabled = application(true)
        AnalyticsModule().install(enabled)
        coVerify(timeout = 5_000, exactly = 1) { pubSub.subscribe("bosca.git.push", PushEvent.serializer()) }
        enabled.shutdown()
    }
}
