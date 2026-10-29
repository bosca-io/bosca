@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.configuration

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.ci.service.KubernetesCiDispatcher
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlin.test.AfterTest
import kotlin.test.Test

class GitCiModuleLifecycleTest {
    @AfterTest
    fun clearProviders() = ProviderRegistry.clear()

    @Test
    fun `host shutdown cancels both CI requirement subscriptions`() = runBlocking {
        ProviderRegistry.clear()
        val subscribed = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val subscriptions = AtomicInteger()
        val cancellations = AtomicInteger()
        val pubSub = mockk<PubSubService>()
        val events: Flow<Message<Any>> = flow {
            if (subscriptions.incrementAndGet() == 2) subscribed.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                if (cancellations.incrementAndGet() == 2) stopped.complete(Unit)
            }
        }
        coEvery { pubSub.subscribe(any(), any<KSerializer<Any>>()) } returns events
        val dispatcher = mockk<KubernetesCiDispatcher>()
        every { dispatcher.enabled } returns false
        provides<PubSubService> { pubSub }
        provides<KubernetesCiDispatchConfiguration> { KubernetesCiDispatchConfiguration.disabled }
        provides<KubernetesCiDispatcher> { dispatcher }
        val application = BoscaApplication(ApplicationConfig.load("{}".byteInputStream()), ownsServices = false)
        try {
            GitCiModule().install(application)
            withTimeout(5_000) { subscribed.await() }
        } finally {
            application.shutdown()
        }
        withTimeout(5_000) { stopped.await() }
    }
}
