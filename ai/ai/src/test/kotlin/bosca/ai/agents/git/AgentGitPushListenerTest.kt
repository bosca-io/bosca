@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.git

import bosca.git.model.PushEvent
import bosca.pubsub.PubSubService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class AgentGitPushListenerTest {

    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val agentGitSyncService = mockk<AgentGitSyncService>(relaxed = true)

    private fun TestScope.newListener(): AgentGitPushListener {
        // Keep the subscription open and let the test scope cancel it during teardown.
        coEvery {
            pubSubService.subscribe("bosca.git.push", PushEvent.serializer())
        } returns flow { awaitCancellation() }
        return AgentGitPushListener(pubSubService, agentGitSyncService, backgroundScope)
    }

    @Test
    fun `idle subscription stays open without resubscribing`() = runTest {
        newListener()
        runCurrent()
        runCurrent()

        coVerify(exactly = 1) { pubSubService.subscribe("bosca.git.push", PushEvent.serializer()) }
    }

    @Test
    fun `owning scope cancels the subscription at teardown`() {
        var cancelled = false
        runTest {
            coEvery {
                pubSubService.subscribe("bosca.git.push", PushEvent.serializer())
            } returns flow {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            AgentGitPushListener(pubSubService, agentGitSyncService, backgroundScope)
            runCurrent()
        }
        assertTrue(cancelled)
    }

    @Test
    fun `handle forwards repositoryId beforeSha and afterSha to sync service`() = runTest {
        val listener = newListener()
        val repoId = Uuid.random()
        val event = PushEvent(
            repositoryId = repoId,
            ref = "refs/heads/main",
            beforeSha = "before123",
            afterSha = "after456"
        )

        listener.handle(event)

        coVerify { agentGitSyncService.onPushEvent(repoId, "before123", "after456") }
    }

    @Test
    fun `handle swallows sync exceptions so one bad event cannot kill the listener`() = runTest {
        val listener = newListener()
        val event = PushEvent(
            repositoryId = Uuid.random(),
            ref = "refs/heads/main",
            beforeSha = "x",
            afterSha = "y"
        )
        coEvery { agentGitSyncService.onPushEvent(any(), any(), any()) } throws RuntimeException("boom")

        // Should not throw — listener catches and logs.
        listener.handle(event)

        coVerify { agentGitSyncService.onPushEvent(any(), any(), any()) }
    }
}
