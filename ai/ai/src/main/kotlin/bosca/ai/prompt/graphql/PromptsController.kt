package bosca.ai.prompt.graphql


import bosca.ai.prompts.model.Prompt
import bosca.ai.prompts.service.PromptService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object Prompts

@TypeController
class PromptsController(
    private val service: PromptService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Prompts> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Prompt> {
        val canView = groupEvaluator.hasGroup(authentication, "prompt.viewer") || groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            groupEvaluator.throwUnauthorized()
        }
        return service.getAll()
    }

    @Field
    suspend fun prompt(authentication: AuthenticationContext, id: UUID): Prompt? {
        val canView = groupEvaluator.hasGroup(authentication, "prompt.viewer") || groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            groupEvaluator.throwUnauthorized()
        }
        return service.get(id)
    }
}
