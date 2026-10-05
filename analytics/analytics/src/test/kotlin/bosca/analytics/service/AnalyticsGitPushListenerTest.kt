@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.PushEvent
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class AnalyticsGitPushListenerTest {
    private val pubSub = mockk<PubSubService>()
    private val queue = mockk<JobQueue>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json> { Json }
        provides<JobQueue>(name = "analytics") { queue }
        coEvery { queue.enqueue(any()) } returns UUID.random()
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun event() = PushEvent(
        repositoryId = UUID.random(),
        ref = "refs/heads/main",
        beforeSha = "before",
        afterSha = "after",
    )

    @Test
    fun `subscription loop enqueues received pushes`() = runTest {
        val event = event()
        val live: Flow<Message<PushEvent>> = flow {
            emit(Message("bosca.git.push", event))
            awaitCancellation()
        }
        coEvery { pubSub.subscribe("bosca.git.push", PushEvent.serializer()) } returns live

        val listener = AnalyticsGitPushListener(pubSub)

        coVerify(timeout = 5_000, exactly = 1) { queue.enqueue(any()) }
        listener.shutdown()
    }

    @Test
    fun `subscription failure is retried`() = runTest {
        val parked: Flow<Message<PushEvent>> = flow { awaitCancellation() }
        coEvery { pubSub.subscribe("bosca.git.push", PushEvent.serializer()) } throws
            IllegalStateException("down") andThen parked

        val listener = AnalyticsGitPushListener(pubSub)

        coVerify(timeout = 10_000, atLeast = 2) {
            pubSub.subscribe("bosca.git.push", PushEvent.serializer())
        }
        listener.shutdown()
    }

    @Test
    fun `direct handler maps every push field into a sync job`() = runTest {
        val parked: Flow<Message<PushEvent>> = flow { awaitCancellation() }
        coEvery { pubSub.subscribe("bosca.git.push", PushEvent.serializer()) } returns parked
        val listener = AnalyticsGitPushListener(pubSub)

        listener.handle(event())

        coVerify(exactly = 1) { queue.enqueue(any()) }
        listener.shutdown()
    }
}
