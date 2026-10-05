package bosca.ai.prompt.graphql

import bosca.ai.prompts.model.Prompt
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.GroupEvaluator
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID


@TypeController
class PromptController(
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Prompt> {

    @Field
    fun id(prompt: Prompt) = prompt.id

    @Field
    fun key(prompt: Prompt) = prompt.key

    @Field
    fun name(prompt: Prompt) = prompt.name

    @Field
    fun description(prompt: Prompt) = prompt.description

    @Field
    fun systemPrompt(authentication: AuthenticationContext?, prompt: Prompt): String? {
        val canView = groupEvaluator.hasGroup(authentication, "model.manager") ||
                groupEvaluator.hasGroup(authentication, "model.viewer") ||
                groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            return null
        }
        return prompt.systemPrompt
    }

    @Field
    fun userPrompt(authentication: AuthenticationContext?, prompt: Prompt): String? {
        val canView = groupEvaluator.hasGroup(authentication, "model.manager") ||
                groupEvaluator.hasGroup(authentication, "model.viewer") ||
                groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            return null
        }
        return prompt.userPrompt
    }

    @Field
    fun inputType(prompt: Prompt) = prompt.inputType

    @Field
    fun outputType(prompt: Prompt) = prompt.outputType

    @Field
    fun schema(authentication: AuthenticationContext?, prompt: Prompt): Any? {
        val canView = groupEvaluator.hasGroup(authentication, "model.manager") ||
                groupEvaluator.hasGroup(authentication, "model.viewer") ||
                groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            return null
        }
        return prompt.schema
    }

    @Field
    fun gitRepositoryId(prompt: Prompt): UUID? = prompt.gitRepositoryId

    @Field
    fun gitPath(prompt: Prompt): String? = prompt.gitPath

    @Field
    fun lastSyncError(prompt: Prompt): String? = prompt.lastSyncError
}
