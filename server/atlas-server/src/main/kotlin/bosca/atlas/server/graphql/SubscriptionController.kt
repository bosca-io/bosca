package bosca.atlas.server.graphql

import bosca.analytics.livesessions.LiveSessionsService
import bosca.db.withConnectionManager
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.model.PipelineRunUpdate
import bosca.pipelines.service.PipelineRunService
import bosca.pubsub.PubSubService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

@TypeController
class SubscriptionController(
    private val groups: GroupEvaluator,
    private val pubSub: PubSubService,
    private val liveSessionStream: LiveSessionsService,
) : GraphQLController<Subscription> {
    /** Uses Analytics's retained/live stream and the platform's authenticated session-map gate. */
    @Field
    fun liveSessions(authenticationContext: AuthenticationContext, appId: String?, appVersion: String?) = flow {
        withConnectionManager {
            authenticationContext.principal() ?: throw SecurityException("Authentication required")
        }
        emitAll(liveSessionStream.subscribe(appId, appVersion))
    }

    /** Uses the same authorization and event stream as the platform pipeline subscription. */
    @Field
    fun pipelineRun(authenticationContext: AuthenticationContext, runId: UUID) = flow {
        withConnectionManager { groups.verifyHasAdminGroup(authenticationContext) }
        emitAll(
            pubSub
                .subscribe(
                    PipelineRunService.runEventChannel(runId),
                    PipelineRunUpdate.serializer(),
                )
                .map { it.message }
        )
    }
}
