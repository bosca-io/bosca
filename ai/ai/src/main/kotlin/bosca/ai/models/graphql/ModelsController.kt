package bosca.ai.models.graphql

import bosca.ai.models.model.Model
import bosca.ai.models.service.ModelService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object Models

@TypeController
class ModelsController(
    private val service: ModelService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Models> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Model> {
        verifyCanView(authentication)
        return service.getAll()
    }

    @Field
    suspend fun model(authentication: AuthenticationContext, id: UUID): Model? {
        verifyCanView(authentication)
        return service.get(id)
    }

    private suspend fun verifyCanView(authentication: AuthenticationContext) {
        val canView = groupEvaluator.hasGroup(authentication, "model.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            groupEvaluator.throwUnauthorized()
        }
    }
}
