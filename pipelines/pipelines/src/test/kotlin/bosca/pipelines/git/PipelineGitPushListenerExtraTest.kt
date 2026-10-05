@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.pipelines.git

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.PushEvent
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.uuid.Uuid

/**
 * Drives the long-running subscription loop in [PipelineGitPushListener]'s `init` block that the
 * happy-path [PipelineGitPushListenerTest] (which only exercises `handle` directly) leaves uncovered:
 * the collect lambda that routes each subscribed push into `handle` (line 34) and the subscribe
 * failure catch + retry-delay (lines 39-40). The loop runs on Dispatchers.Default, so we wait for it
 * with mockk verify timeouts rather than the test scheduler.
 */
class PipelineGitPushListenerExtraTest {

    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val pipelineGitSyncService = mockk<PipelineGitSyncService>(relaxed = true)

    // The subscription loop now wraps each event in withRequestCache + withConnectionManager
    // (mirroring JobRunner), so the loop-driving tests need these resolvable as relaxed no-ops.
    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { mockk(relaxed = true) }
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        provides<RequestCacheSerializer>(singleton = true) { mockk(relaxed = true) }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun pushEvent() = PushEvent(
        repositoryId = Uuid.random(),
        ref = "refs/heads/main",
        beforeSha = "before",
        afterSha = "after",
    )

    @Test
    fun `the subscription loop routes a received push event into the sync service`() = runTest {
        // subscribe emits ONE message then parks (awaitCancellation) so the loop collects exactly once
        // and the collect lambda (line 34) calls handle -> onPushEvent. No re-subscribe storm.
        val event = pushEvent()
        val live: Flow<Message<PushEvent>> = flow {
            emit(Message("bosca.git.push", event))
            awaitCancellation()
        }
        coEvery { pubSubService.subscribe("bosca.git.push", PushEvent.serializer()) } returns live

        // Constructing the listener starts the init loop on a background dispatcher.
        PipelineGitPushListener(pubSubService, pipelineGitSyncService)

        coVerify(timeout = 5_000) {
            pipelineGitSyncService.onPushEvent(event.repositoryId, "before", "after")
        }
    }

    @Test
    fun `a failing subscribe is caught and the loop retries`() = runTest {
        // First subscribe throws -> the catch logs (line 39) and delays (line 40); the loop then
        // re-subscribes. The second subscribe parks so the loop settles instead of spinning.
        val parked: Flow<Message<PushEvent>> = flow { awaitCancellation() }
        coEvery {
            pubSubService.subscribe("bosca.git.push", PushEvent.serializer())
        } throws RuntimeException("subscribe down") andThen parked

        PipelineGitPushListener(pubSubService, pipelineGitSyncService)

        // The loop must survive the failure and attempt to subscribe at least twice (initial + retry).
        coVerify(atLeast = 2, timeout = 10_000) {
            pubSubService.subscribe("bosca.git.push", PushEvent.serializer())
        }
        // Defensive: no event was routed since nothing was ever emitted.
        coVerify(exactly = 0) { pipelineGitSyncService.onPushEvent(any(), any(), any()) }
    }

    @Test
    fun `handle still routes directly when the loop sits idle`() = runTest {
        // Keep parity with the sibling test's direct-handle path so this file is self-contained.
        coEvery { pubSubService.subscribe("bosca.git.push", PushEvent.serializer()) } returns emptyFlow()
        val listener = PipelineGitPushListener(pubSubService, pipelineGitSyncService)
        val event = pushEvent()

        listener.handle(event)

        coVerify { pipelineGitSyncService.onPushEvent(event.repositoryId, "before", "after") }
    }
}
