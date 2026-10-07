package bosca.git.graphql

import bosca.git.model.GitHubUser
import bosca.git.model.GitHubRefResolution
import bosca.git.model.GitHubRefResolutionInput
import bosca.git.model.GitHubRefState
import bosca.git.model.GitHubRepositoryPair
import bosca.git.model.GitHubRepositoryPairInput
import bosca.git.service.GitHubSyncService
import bosca.git.service.RepositoryService
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.security.service.SecurityService
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GitHubSyncAuthorizationTest {
    private val service = mockk<GitHubSyncService>()
    private val groups = GroupEvaluator(mockk())
    private val query = GitHubSyncQuery(service, groups)
    private val repositories = mockk<RepositoryService>(relaxed = true)
    private val permissions = RepositoryPermissionEvaluator(repositories, mockk<SecurityService>(), groups)
    private val mutation = GitHubSyncMutation(service, groups, repositories, permissions)
    private val repositoryId = UUID.random()
    private val principalId = UUID.random()
    private val input = GitHubRepositoryPairInput(repositoryId, 1, "owner", "repo", "webhook", "token")
    private val resolution = GitHubRefResolutionInput(repositoryId, "refs/heads/main", GitHubRefResolution.GITHUB)

    private fun auth(vararg names: String) = mockk<AuthenticationContext>().also { context ->
        every { context.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), names.map { Group(id = UUID.random(), name = it, description = "", type = GroupType.SYSTEM) })
    }

    @Test fun `repository editors and managers cannot configure user attribution or read private intake`() = runTest {
        for (authentication in listOf(auth(), auth("editors"), auth("managers"), auth("sa"))) {
            assertFails { query.pair(authentication, repositoryId) }
            assertFails { query.users(authentication, null, null) }
            assertFails { query.deliveries(authentication, repositoryId, null, null) }
            assertFails { query.refStates(authentication, repositoryId, null, null) }
            assertFails { query.pullRequestStates(authentication, repositoryId, null, null) }
            assertFails { mutation.savePair(authentication, input) }
            assertFails { mutation.mapUser(authentication, 7, principalId) }
            assertFails { mutation.unmapUser(authentication, 7) }
            assertFails { mutation.reconcileRefs(authentication, repositoryId) }
            assertFails { mutation.reconcilePullRequests(authentication, repositoryId) }
            assertFails { mutation.pullRefs(authentication, repositoryId) }
            assertFails { mutation.pushRefs(authentication, repositoryId) }
            assertFails { mutation.resolveRef(authentication, resolution) }
        }
        coVerify(exactly = 0) { service.findPair(any()) }
        coVerify(exactly = 0) { service.mapUser(any(), any()) }
        coVerify(exactly = 0) { service.savePair(any()) }
        coVerify(exactly = 0) { service.pullRefs(any(), any()) }
        coVerify(exactly = 0) { service.pushRefs(any(), any()) }
        coVerify(exactly = 0) { service.resolveRef(any(), any()) }
    }

    @Test fun `administrators manage pairing and user mappings through the owning service`() = runTest {
        val authentication = auth("administrators")
        coEvery { repositories.findById(repositoryId) } returns bosca.git.model.Repository(
            id = repositoryId, slug = "source", name = "Source", ownerId = UUID.random(),
        )
        val pair = GitHubRepositoryPair(repositoryId, 1, "owner", "repo", "webhook", "token")
        val user = GitHubUser(7, principalId)
        coEvery { service.findPair(repositoryId) } returns pair
        coEvery { service.savePair(input) } returns pair
        coEvery { service.mapUser(7, principalId) } returns user
        coEvery { service.unmapUser(7) } returns Unit
        coEvery { service.findUsers(any(), any()) } returns listOf(user)
        coEvery { service.findDeliveries(any(), any(), any()) } returns emptyList()
        coEvery { service.findRefStates(any(), any(), any()) } returns emptyList()
        coEvery { service.reconcileRefs(repositoryId, principalId) } returns emptyList()
        coEvery { service.findPullRequestStates(any(), any(), any()) } returns emptyList()
        coEvery { service.reconcilePullRequests(any()) } returns emptyList()
        coEvery { service.pullRefs(repositoryId, principalId) } returns emptyList()
        coEvery { service.pushRefs(repositoryId, principalId) } returns emptyList()
        val resolved = GitHubRefState(repositoryId, resolution.ref, null, synchronized = true)
        coEvery { service.resolveRef(resolution, principalId) } returns resolved
        assertEquals(pair, query.pair(authentication, repositoryId))
        assertEquals(pair, mutation.savePair(authentication, input))
        assertEquals(user, mutation.mapUser(authentication, 7, principalId))
        assertTrue(mutation.unmapUser(authentication, 7))
        assertTrue(mutation.reconcileRefs(authentication, repositoryId).isEmpty())
        coVerify(exactly = 1) { service.reconcileRefs(repositoryId, principalId) }
        coVerify(exactly = 0) { service.reconcileRefs(any()) }
        assertEquals(listOf(user), query.users(authentication, null, null))
        query.users(authentication, 10, 1000)
        query.deliveries(authentication, repositoryId, null, null)
        query.deliveries(authentication, repositoryId, 5, 0)
        query.refStates(authentication, repositoryId, null, null)
        query.refStates(authentication, repositoryId, 5, 1000)
        query.pullRequestStates(authentication, repositoryId, null, null)
        query.pullRequestStates(authentication, repositoryId, 5, 1000)
        assertTrue(mutation.reconcilePullRequests(authentication, repositoryId).isEmpty())
        assertTrue(mutation.pullRefs(authentication, repositoryId).isEmpty())
        assertTrue(mutation.pushRefs(authentication, repositoryId).isEmpty())
        assertEquals(resolved, mutation.resolveRef(authentication, resolution))
        coVerify(exactly = 1) { service.resolveRef(resolution, principalId) }
        coVerify(exactly = 1) { service.pullRefs(repositoryId, principalId) }
        coVerify(exactly = 1) { service.pushRefs(repositoryId, principalId) }
        coVerify { service.findUsers(0, 25) }
        coVerify { service.findUsers(10, 100) }
        coVerify { service.findDeliveries(repositoryId, 0, 25) }
        coVerify { service.findDeliveries(repositoryId, 5, 1) }
        coVerify { service.findRefStates(repositoryId, 0, 25) }
        coVerify { service.findRefStates(repositoryId, 5, 100) }
        coVerify { service.findPullRequestStates(repositoryId, 0, 25) }
        coVerify { service.findPullRequestStates(repositoryId, 5, 100) }
    }

    @Test fun `manual transfers require repository edit scope even for an administrator`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        every { authentication.principal() } returns ScopedAuthenticatedPrincipal(
            Principal(id = principalId), listOf(Group(name = "administrators", description = "", type = GroupType.SYSTEM)),
            listOf("git:read"), null, 1,
        )
        val hosted = bosca.git.model.Repository(id = repositoryId, slug = "source", name = "Source", ownerId = UUID.random())
        coEvery { repositories.findById(repositoryId) } returns hosted
        coEvery { repositories.isParentAllowed(any(), hosted, any()) } returns false
        assertFails { mutation.pullRefs(authentication, repositoryId) }
        assertFails { mutation.pushRefs(authentication, repositoryId) }
        assertFails { mutation.resolveRef(authentication, resolution) }
        assertFails { mutation.reconcileRefs(authentication, repositoryId) }
        coVerify(exactly = 0) { service.pullRefs(any(), any()) }
        coVerify(exactly = 0) { service.pushRefs(any(), any()) }
        coEvery { repositories.findById(repositoryId) } returns null
        coVerify(exactly = 0) { service.resolveRef(any(), any()) }
        coVerify(exactly = 0) { service.reconcileRefs(any(), any()) }
        assertFailsWith<NoSuchElementException> { mutation.resolveRef(auth("administrators"), resolution) }
        assertFailsWith<NoSuchElementException> { mutation.pullRefs(auth("administrators"), repositoryId) }
    }
}
