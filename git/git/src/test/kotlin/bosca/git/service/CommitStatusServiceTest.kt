@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.service

import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.CommitStatus
import bosca.git.model.CommitStatusEvent
import bosca.git.model.CommitStatusState
import bosca.git.repository.CommitStatusRepository
import bosca.pipelines.PipelineEventDispatcher
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

class CommitStatusServiceTest {
    private val repository = mockk<CommitStatusRepository>(relaxed = true)
    private val dispatcher = mockk<PipelineEventDispatcher>(relaxed = true)
    private val service = CommitStatusServiceImpl(repository)
    private val repositoryId = UUID.random()
    private val sha = "a".repeat(40)
    private val connection = mockk<ConnectionManager>(relaxed = true)

    @BeforeTest fun setup() {
        ProviderRegistry.clear()
        provides<PipelineEventDispatcher> { dispatcher }
    }

    @AfterTest fun cleanup() = ProviderRegistry.clear()

    @Test fun `changed status dispatches its persisted revision`() = runTest {
        val persisted = CommitStatus(repositoryId = repositoryId, commitSha = sha, context = "ci/tests",
            state = CommitStatusState.SUCCESS, description = "Passed", ref = "refs/heads/main")
        coEvery { repository.upsert(any()) } returns persisted
        withContext(connection.asCoroutineContext()) {
            assertEquals(persisted, service.recordStatus(repositoryId, sha, "ci/tests", CommitStatusState.SUCCESS,
                "Passed", ref = "refs/heads/main"))
        }
        coVerify { repository.upsert(match { it.ref == "refs/heads/main" && it.state == CommitStatusState.SUCCESS }) }
        coVerify(exactly = 1) { dispatcher.dispatch("bosca.git.model.CommitStatusEvent",
            CommitStatusEvent(repositoryId, persisted.id, persisted.modified), any()) }
    }

    @Test fun `unchanged report preserves revision without dispatching again`() = runTest {
        val persisted = CommitStatus(repositoryId = repositoryId, commitSha = sha, context = "ci/tests", state = CommitStatusState.PENDING)
        coEvery { repository.upsert(any()) } returns null
        coEvery { repository.findByContext(repositoryId, sha, "ci/tests") } returns persisted
        withContext(connection.asCoroutineContext()) {
            assertEquals(persisted, service.recordStatus(repositoryId, sha, "ci/tests", CommitStatusState.PENDING))
        }
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any<CommitStatusEvent>(), any()) }
    }

    @Test fun `every reported check must pass even without named required contexts`() = runTest {
        for (state in CommitStatusState.entries) {
            coEvery { repository.findByCommitSha(repositoryId, sha) } returns listOf(
                CommitStatus(repositoryId = repositoryId, commitSha = sha, context = "ci/build", state = CommitStatusState.SUCCESS),
                CommitStatus(repositoryId = repositoryId, commitSha = sha, context = "ci/tests", state = state),
            )
            assertEquals(state == CommitStatusState.SUCCESS, service.areRequiredChecksPassing(repositoryId, sha, emptyList()))
            assertEquals(state == CommitStatusState.SUCCESS, service.areRequiredChecksPassing(repositoryId, sha, listOf("ci/build")))
        }
    }

    @Test fun `named required checks must exist`() = runTest {
        coEvery { repository.findByCommitSha(repositoryId, sha) } returns listOf(
            CommitStatus(repositoryId = repositoryId, commitSha = sha, context = "ci/build", state = CommitStatusState.SUCCESS),
        )
        assertTrue(service.areRequiredChecksPassing(repositoryId, sha, listOf("ci/build")))
        assertFalse(service.areRequiredChecksPassing(repositoryId, sha, listOf("ci/build", "ci/tests")))
    }

    @Test fun `commit with no checks passes only when no named checks are required`() = runTest {
        assertTrue(service.areRequiredChecksPassing(repositoryId, sha, emptyList()))
        assertFalse(service.areRequiredChecksPassing(repositoryId, sha, listOf("ci/tests")))
    }
}
