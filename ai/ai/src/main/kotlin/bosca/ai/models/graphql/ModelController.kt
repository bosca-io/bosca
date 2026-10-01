package bosca.ai.models.graphql

import bosca.ai.models.model.Model
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import kotlinx.serialization.json.JsonElement

@TypeController
class ModelController(
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Model> {

    @Field
    fun id(model: Model) = model.id

    @Field
    fun key(model: Model) = model.key

    @Field
    fun type(model: Model) = model.type

    @Field
    fun name(model: Model) = model.name

    @Field
    fun description(model: Model) = model.description

    @Field
    fun configuration(authentication: AuthenticationContext?, model: Model): JsonElement? {
        val canView = groupEvaluator.hasGroup(authentication, "model.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            return null
        }
        return model.configuration
    }
}
