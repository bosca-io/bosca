package bosca.git.graphql

import bosca.db.withConnectionManager
import bosca.git.model.PullRequestEvent
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pubsub.PubSubService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Authenticated, repository-isolated pull-request activity subscriptions. */
@TypeController(type = "Subscription")
class GitActivitySubscription(
    private val repositoryService: RepositoryService,
    private val permissionEvaluator: RepositoryPermissionEvaluator,
    private val pubSubService: PubSubService,
) : GraphQLController<Any> {
    @Field
    fun gitPullRequestEvents(
        authentication: AuthenticationContext,
        repositoryId: UUID,
    ): Flow<PullRequestEvent> = flow {
        withConnectionManager {
            GitEventSubscriptionSupport.verifyView(authentication, repositoryId, repositoryService, permissionEvaluator)
        }
        emitAll(
            GitEventSubscriptionSupport.repositoryEvents(
                pubSubService.subscribe(PULL_REQUEST_EVENT_CHANNEL, PullRequestEvent.serializer()),
                repositoryId,
            ),
        )
    }

    companion object {
        private const val PULL_REQUEST_EVENT_CHANNEL = "bosca.git.pull_request"
    }
}
