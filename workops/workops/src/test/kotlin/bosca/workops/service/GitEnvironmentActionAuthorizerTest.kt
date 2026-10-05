package bosca.workops.service

import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.environment.Environment
import bosca.workops.model.project.Project
import bosca.workops.model.project.ProjectRepository as ProjectRepositoryLink
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitEnvironmentActionAuthorizerTest {

    private val projectRepositories = mockk<ProjectRepositoryService>()
    private val projectService = mockk<ProjectService>()
    private val environmentService = mockk<EnvironmentService>()
    private val evaluator = mockk<EnvironmentPermissionEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>()

    private val authorizer = GitEnvironmentActionAuthorizer(
        projectRepositories, projectService, environmentService, evaluator,
    )

    private val repositoryId = UUID.random()
    private val projectId = UUID.random()
    private val programId = UUID.random()

    private fun linkRepository() {
        coEvery { projectRepositories.listByRepository(repositoryId) } returns listOf(
            ProjectRepositoryLink(id = UUID.random(), projectId = projectId, repositoryId = repositoryId),
        )
        coEvery { projectService.getById(projectId) } returns
            Project(id = projectId, programId = programId, key = "P", name = "P", ownerProfileId = UUID.random())
    }

    @Test
    fun `delegates to the environment permission evaluator for the linked program's environment`() = runTest {
        linkRepository()
        val environment = Environment(id = UUID.random(), programId = programId, key = "production", name = "Production")
        coEvery { environmentService.getByProgramAndKey(programId, "production") } returns environment

        authorizer.verifyAllowed(authentication, repositoryId, "production", PermissionAction.EXECUTE)

        coVerify { evaluator.verifyAllowed(authentication, environment, PermissionAction.EXECUTE) }
    }

    @Test
    fun `fails closed when no program links the repository`() = runTest {
        coEvery { projectRepositories.listByRepository(repositoryId) } returns emptyList()

        val e = assertFailsWith<IllegalStateException> {
            authorizer.verifyAllowed(authentication, repositoryId, "production", PermissionAction.EXECUTE)
        }
        assertTrue("not linked" in (e.message ?: ""), e.message)
    }

    @Test
    fun `fails closed when the key does not exist in any linked program`() = runTest {
        linkRepository()
        coEvery { environmentService.getByProgramAndKey(programId, "ghost") } returns null

        val e = assertFailsWith<IllegalStateException> {
            authorizer.verifyAllowed(authentication, repositoryId, "ghost", PermissionAction.EXECUTE)
        }
        assertTrue("ghost" in (e.message ?: ""), e.message)
    }
}
