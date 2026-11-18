package bosca.atlas.server.graphql

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
) : GraphQLController<Subscription> {
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
