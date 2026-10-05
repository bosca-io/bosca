package bosca.ai.agents.git.graphql

import bosca.ai.agents.git.AgentRepoBackfillResult
import bosca.ai.agents.git.AgentRepoValidationError
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Field resolvers for [AgentRepoBackfillResult]. */
@TypeController
class AgentRepoBackfillResultController : GraphQLController<AgentRepoBackfillResult> {

    @Field
    fun ok(backfillResult: AgentRepoBackfillResult): Boolean = backfillResult.ok

    @Field
    fun commitSha(backfillResult: AgentRepoBackfillResult): String? = backfillResult.commitSha

    @Field
    fun validationErrors(backfillResult: AgentRepoBackfillResult): List<AgentRepoValidationError>? = backfillResult.validationErrors

    @Field
    fun errorMessage(backfillResult: AgentRepoBackfillResult): String? = backfillResult.errorMessage
}

/** Field resolvers for [AgentRepoValidationError]. */
@TypeController
class AgentRepoValidationErrorController : GraphQLController<AgentRepoValidationError> {

    @Field
    fun path(validationError: AgentRepoValidationError): String = validationError.path

    @Field
    fun message(validationError: AgentRepoValidationError): String = validationError.message
}
