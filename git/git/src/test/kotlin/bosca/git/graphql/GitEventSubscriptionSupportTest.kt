package bosca.git.graphql

import bosca.core.annotations.Internal
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.git.model.PullRequestEvent
import bosca.git.model.PullRequestEventAction
import bosca.git.model.Repository
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryService
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(Internal::class, InternalDI::class)
class GitEventSubscriptionSupportTest {
    private val authentication = mockk<AuthenticationContext>()
    private val repositoryService = mockk<RepositoryService>()
    private val evaluator = mockk<RepositoryPermissionEvaluator>()

    @Test
    fun `authorization resolves and verifies the exact requested repository`() = runTest {
        val repositoryId = UUID.random()
        val repository = mockk<Repository>()
        coEvery { repositoryService.findById(repositoryId) } returns repository
        coEvery { evaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW) } returns Unit

        GitEventSubscriptionSupport.verifyView(authentication, repositoryId, repositoryService, evaluator)

        coVerify(exactly = 1) { repositoryService.findById(repositoryId) }
        coVerify(exactly = 1) { evaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW) }
    }

    @Test
    fun `missing repository is rejected before pubsub collection`() = runTest {
        val repositoryId = UUID.random()
        coEvery { repositoryService.findById(repositoryId) } returns null

        assertFailsWith<NoSuchElementException> {
            GitEventSubscriptionSupport.verifyView(authentication, repositoryId, repositoryService, evaluator)
        }
    }

    @Test
    fun `repository filter cannot leak an event from another repository`() = runTest {
        val selected = UUID.random()
        val other = UUID.random()
        val selectedEvent = event(selected, 1)
        val otherEvent = event(other, 2)

        val result = GitEventSubscriptionSupport.repositoryEvents(
            flowOf(Message("channel", otherEvent), Message("channel", selectedEvent)),
            selected,
        ).toList()

        assertEquals(listOf(selectedEvent), result)
    }

    @Test
    fun `pull request subscription authorizes before emitting only repository events`() = runTest {
        ProviderRegistry.clear()
        try {
            val connectionManager = mockk<ConnectionManager>(relaxed = true)
            val connectionPool = mockk<ConnectionPool>()
            every { connectionPool.connection() } returns connectionManager
            provides<ConnectionPool> { connectionPool }

            val repositoryId = UUID.random()
            val otherRepositoryId = UUID.random()
            val repository = mockk<Repository>()
            val selectedEvent = event(repositoryId, 10)
            val otherEvent = event(otherRepositoryId, 11)
            val pubSubService = mockk<PubSubService>()
            coEvery { repositoryService.findById(repositoryId) } returns repository
            coEvery { evaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW) } returns Unit
            every { pubSubService.subscribe<PullRequestEvent>("bosca.git.pull_request", any()) } returns flowOf(
                Message("bosca.git.pull_request", otherEvent),
                Message("bosca.git.pull_request", selectedEvent),
            )

            val events = GitActivitySubscription(repositoryService, evaluator, pubSubService)
                .gitPullRequestEvents(authentication, repositoryId)
                .toList()

            assertEquals(listOf(selectedEvent), events)
            coVerify(exactly = 1) { evaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW) }
            coVerify(exactly = 1) { connectionManager.release() }
        } finally {
            ProviderRegistry.clear()
        }
    }

    private fun event(repositoryId: UUID, number: Int) = PullRequestEvent(
        repositoryId = repositoryId,
        pullRequestId = UUID.random(),
        number = number,
        action = PullRequestEventAction.OPENED,
        title = "PR $number",
        sourceBranch = "feature-$number",
        targetBranch = "main",
        authorId = UUID.random(),
    )
}
