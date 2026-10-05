package bosca.git.service

import bosca.git.model.CommitStatus
import bosca.git.model.CommitStatusState
import bosca.git.repository.CommitStatusRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommitStatusServiceTest {

    private val statusRepository = mockk<CommitStatusRepository>(relaxed = true)
    private lateinit var service: CommitStatusService

    private val repositoryId = UUID.random()
    private val commitSha = "a".repeat(40)

    @BeforeTest
    fun setup() {
        service = CommitStatusServiceImpl(statusRepository)
    }

    @Test
    fun `recordStatus creates new status when none exists for context`() = runTest {
        coEvery { statusRepository.findByContext(repositoryId, commitSha, "ci/build") } returns null
        coEvery { statusRepository.create(any()) } answers {
            firstArg<CommitStatus>().copy(id = UUID.random())
        }

        val status = service.recordStatus(repositoryId, commitSha, "ci/build", CommitStatusState.SUCCESS, "All good")
        assertEquals(CommitStatusState.SUCCESS, status.state)
        assertEquals("ci/build", status.context)
        coVerify { statusRepository.create(any()) }
    }

    @Test
    fun `recordStatus updates existing status for same context`() = runTest {
        val existing = CommitStatus(
            id = UUID.random(),
            repositoryId = repositoryId,
            commitSha = commitSha,
            context = "ci/build",
            state = CommitStatusState.PENDING
        )
        coEvery { statusRepository.findByContext(repositoryId, commitSha, "ci/build") } returns existing
        coEvery { statusRepository.update(any()) } answers { firstArg() }

        val status = service.recordStatus(repositoryId, commitSha, "ci/build", CommitStatusState.SUCCESS)
        assertEquals(CommitStatusState.SUCCESS, status.state)
        coVerify { statusRepository.update(match { it.id == existing.id && it.state == CommitStatusState.SUCCESS }) }
    }

    @Test
    fun `areRequiredChecksPassing returns true when all pass`() = runTest {
        coEvery { statusRepository.findByCommitSha(repositoryId, commitSha) } returns listOf(
            CommitStatus(repositoryId = repositoryId, commitSha = commitSha, context = "ci/build", state = CommitStatusState.SUCCESS),
            CommitStatus(repositoryId = repositoryId, commitSha = commitSha, context = "ci/test", state = CommitStatusState.SUCCESS)
        )

        assertTrue(service.areRequiredChecksPassing(repositoryId, commitSha, listOf("ci/build", "ci/test")))
    }

    @Test
    fun `areRequiredChecksPassing returns false when one fails`() = runTest {
        coEvery { statusRepository.findByCommitSha(repositoryId, commitSha) } returns listOf(
            CommitStatus(repositoryId = repositoryId, commitSha = commitSha, context = "ci/build", state = CommitStatusState.SUCCESS),
            CommitStatus(repositoryId = repositoryId, commitSha = commitSha, context = "ci/test", state = CommitStatusState.FAILURE)
        )

        assertFalse(service.areRequiredChecksPassing(repositoryId, commitSha, listOf("ci/build", "ci/test")))
    }

    @Test
    fun `areRequiredChecksPassing returns false when context missing`() = runTest {
        coEvery { statusRepository.findByCommitSha(repositoryId, commitSha) } returns listOf(
            CommitStatus(repositoryId = repositoryId, commitSha = commitSha, context = "ci/build", state = CommitStatusState.SUCCESS)
        )

        assertFalse(service.areRequiredChecksPassing(repositoryId, commitSha, listOf("ci/build", "ci/test")))
    }

    @Test
    fun `areRequiredChecksPassing returns true when no checks required`() = runTest {
        assertTrue(service.areRequiredChecksPassing(repositoryId, commitSha, emptyList()))
    }
}
