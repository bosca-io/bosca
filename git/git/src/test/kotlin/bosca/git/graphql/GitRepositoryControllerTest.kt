package bosca.git.graphql

import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.slug.service.SlugService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [GitRepositoryController]: field pass-throughs, the clone-URL
 * composition (slug + configured base URL, with both missing-input fallbacks),
 * and the MANAGE-gated permissions resolver.
 */
class GitRepositoryControllerTest {

    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)
    private val slugService = mockk<SlugService>(relaxed = true)
    private val application = mockk<BoscaApplication>(relaxed = true)
    private val controller = GitRepositoryController(repositoryService, permissionEvaluator, slugService, application)

    private val auth = mockk<AuthenticationContext>(relaxed = true)
    private val repo = Repository(
        id = UUID.random(), slug = "repo", name = "R", description = "d",
        ownerId = UUID.random(), visibility = Visibility.PRIVATE,
    )

    @Test
    fun `simple fields pass through the source`() {
        assertEquals(repo.id, controller.id(repo))
        assertEquals("repo", controller.slug(repo))
        assertEquals("R", controller.name(repo))
        assertEquals("d", controller.description(repo))
        assertEquals(repo.ownerId, controller.ownerId(repo))
        assertEquals(Visibility.PRIVATE, controller.visibility(repo))
        assertEquals("main", controller.defaultBranch(repo))
        assertEquals(false, controller.archived(repo))
        assertEquals(false, controller.deleted(repo))
        assertEquals(null, controller.forkedFromId(repo))
        assertEquals(null, controller.contentType(repo))
        assertEquals(0L, controller.diskSizeBytes(repo))
        assertEquals(repo.configuration, controller.configuration(repo))
        assertEquals(repo.created, controller.created(repo))
        assertEquals(repo.updated, controller.updated(repo))
    }

    private fun stubGitUrl(url: String?) {
        val yaml = if (url != null) "git:\n  url: $url\n" else "other: x\n"
        val config = bosca.server.config.ApplicationConfig.load(yaml.byteInputStream())
        every { application.config } returns config
    }

    @Test
    fun `cloneUrl combines the configured base url with owner and repo slugs`() = runTest {
        coEvery { slugService.getProfileSlug(repo.ownerId) } returns "acme"
        stubGitUrl("https://git.example.com/")
        assertEquals("https://git.example.com/acme/repo.git", controller.cloneUrl(repo))
    }

    @Test
    fun `cloneUrl is empty when the owner slug or base url is missing`() = runTest {
        coEvery { slugService.getProfileSlug(repo.ownerId) } returns null
        assertEquals("", controller.cloneUrl(repo))

        coEvery { slugService.getProfileSlug(repo.ownerId) } returns "acme"
        stubGitUrl(null)
        assertEquals("", controller.cloneUrl(repo))
    }

    @Test
    fun `permissions are empty without MANAGE and mapped with it`() = runTest {
        coEvery { permissionEvaluator.isAllowed(auth, repo, PermissionAction.MANAGE) } returns false
        assertTrue(controller.permissions(auth, repo).isEmpty())

        coEvery { permissionEvaluator.isAllowed(auth, repo, PermissionAction.MANAGE) } returns true
        val groupId = UUID.random()
        coEvery { repositoryService.getPermissions(repo) } returns listOf(
            bosca.git.model.RepositoryPermission(repositoryId = repo.id, groupId = groupId, action = PermissionAction.EDIT)
        )
        val perms = controller.permissions(auth, repo)
        assertEquals(1, perms.size)
        assertEquals(groupId, perms[0].groupId)
        assertEquals(PermissionAction.EDIT, perms[0].action)
    }

    @Test
    fun `canExecute reports the current execution grant`() = runTest {
        coEvery { permissionEvaluator.isAllowed(auth, repo, PermissionAction.EXECUTE) } returns false
        assertEquals(false, controller.canExecute(auth, repo))
        coEvery { permissionEvaluator.isAllowed(auth, repo, PermissionAction.EXECUTE) } returns true
        assertEquals(true, controller.canExecute(auth, repo))
        coEvery { permissionEvaluator.isAllowed(null, repo, PermissionAction.EXECUTE) } returns false
        assertEquals(false, controller.canExecute(null, repo))
    }
}
