package bosca.pipelines.git

import bosca.git.model.PushEvent
import bosca.pubsub.PubSubService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.uuid.Uuid

class PipelineGitPushListenerTest {

    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val pipelineGitSyncService = mockk<PipelineGitSyncService>(relaxed = true)

    private fun newListener(): PipelineGitPushListener {
        // PubSub subscribe is wired by the init block; emit nothing so the loop sits idle
        // while the test drives `handle` directly.
        coEvery {
            pubSubService.subscribe("bosca.git.push", PushEvent.serializer())
        } returns emptyFlow()
        return PipelineGitPushListener(pubSubService, pipelineGitSyncService)
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

        coVerify { pipelineGitSyncService.onPushEvent(repoId, "before123", "after456") }
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
        coEvery { pipelineGitSyncService.onPushEvent(any(), any(), any()) } throws RuntimeException("boom")

        // Should not throw — listener catches and logs.
        listener.handle(event)

        coVerify { pipelineGitSyncService.onPushEvent(any(), any(), any()) }
    }
}
