@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.git.graphql

import bosca.ai.agents.git.AgentEntityType
import bosca.ai.agents.git.AgentGitSyncService
import bosca.ai.agents.git.AgentRepoBackfillEntry
import bosca.ai.agents.git.BackfillEntry
import bosca.ai.agents.git.RepoValidationError
import bosca.ai.agents.git.SyncResult
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AgentRepoMutationControllerTest {

    private val service = mockk<AgentGitSyncService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val controller = AgentRepoMutationController(service, groupEvaluator)

    private val adminAuth = mockk<AuthenticationContext>()
    private val managerAuth = mockk<AuthenticationContext>()
    private val unauthorizedAuth = mockk<AuthenticationContext>()

    private val repoId = Uuid.random()
    private val entityId = Uuid.random()
    private val entries = listOf(
        AgentRepoBackfillEntry(AgentEntityType.AGENT, entityId, "agents/foo.md")
    )

    @Test
    fun `backfill returns ok=true with commit sha on success`() = runTest {
        every { groupEvaluator.hasGroup(adminAuth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(adminAuth) } returns true
        coEvery {
            service.backfill(repoId, listOf(BackfillEntry(AgentEntityType.AGENT, entityId, "agents/foo.md")), "T", "t@e.com")
        } returns SyncResult.Ok(commitSha = "abc123")

        val result = controller.backfill(adminAuth, repoId, entries, "T", "t@e.com")

        assertEquals(true, result.ok)
        assertEquals("abc123", result.commitSha)
        assertEquals(null, result.validationErrors)
        assertEquals(null, result.errorMessage)
    }

    @Test
    fun `backfill returns ok=false with validation errors on ValidationFailed`() = runTest {
        every { groupEvaluator.hasGroup(managerAuth, "agent.manager") } returns true
        every { groupEvaluator.hasAdminGroup(managerAuth) } returns false
        coEvery { service.backfill(any(), any(), any(), any()) } returns SyncResult.ValidationFailed(
            listOf(RepoValidationError("agents/foo.md", "unknown model 'x'"))
        )

        val result = controller.backfill(managerAuth, repoId, entries, "T", "t@e.com")

        assertEquals(false, result.ok)
        assertEquals(null, result.commitSha)
        assertTrue(result.validationErrors != null)
        assertEquals(1, result.validationErrors!!.size)
        assertEquals("agents/foo.md", result.validationErrors!![0].path)
        assertEquals("unknown model 'x'", result.validationErrors!![0].message)
        assertEquals(null, result.errorMessage)
    }

    @Test
    fun `backfill returns ok=false with error message on Failure`() = runTest {
        every { groupEvaluator.hasGroup(adminAuth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(adminAuth) } returns true
        coEvery { service.backfill(any(), any(), any(), any()) } returns SyncResult.Failure("repo offline")

        val result = controller.backfill(adminAuth, repoId, entries, "T", "t@e.com")

        assertEquals(false, result.ok)
        assertEquals(null, result.commitSha)
        assertEquals(null, result.validationErrors)
        assertEquals("repo offline", result.errorMessage)
    }

    @Test
    fun `backfill throws SecurityException when caller lacks both groups`() = runTest {
        every { groupEvaluator.hasGroup(unauthorizedAuth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(unauthorizedAuth) } returns false
        every { groupEvaluator.throwUnauthorized() } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.backfill(unauthorizedAuth, repoId, entries, "T", "t@e.com")
        }
    }
}
