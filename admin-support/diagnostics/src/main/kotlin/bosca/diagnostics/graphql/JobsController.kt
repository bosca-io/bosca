package bosca.diagnostics.graphql

import bosca.diagnostics.model.JobQueueSnapshot
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

object Jobs

@TypeController
class JobsController(
    private val groups: GroupEvaluator,
) : GraphQLController<Jobs> {

    @Field
    suspend fun queues(
        authentication: AuthenticationContext,
        status: List<String>?,
        queue: List<String>?
    ): List<JobQueueSnapshot> {
        groups.verifyHasAdminGroup(authentication)

        TODO()
    }
}
