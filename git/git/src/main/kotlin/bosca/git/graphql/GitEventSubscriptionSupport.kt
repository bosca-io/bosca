package bosca.git.graphql

import bosca.git.model.GitEvent
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryService
import bosca.pubsub.Message
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Shared authorization and hard repository boundary for Git event subscriptions. */
object GitEventSubscriptionSupport {
    suspend fun verifyView(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        repositoryService: RepositoryService,
        permissionEvaluator: RepositoryPermissionEvaluator,
    ) {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
    }

    fun <T : GitEvent> repositoryEvents(events: Flow<Message<T>>, repositoryId: UUID): Flow<T> =
        events.map { it.message }.filter { it.repositoryId == repositoryId }
}
