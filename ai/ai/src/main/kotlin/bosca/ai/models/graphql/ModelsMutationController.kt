package bosca.ai.models.graphql

import bosca.ai.models.model.Model
import bosca.ai.models.model.ModelInput
import bosca.ai.models.service.ModelService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object ModelsMutation

@TypeController
class ModelsMutationController(
    private val service: ModelService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<ModelsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, model: ModelInput): Model {
        val canEdit = groupEvaluator.hasGroup(authentication, "model.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canEdit) {
            groupEvaluator.throwUnauthorized()
        }
        return service.add(model)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, model: ModelInput): Model {
        val canEdit = groupEvaluator.hasGroup(authentication, "model.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canEdit) {
            groupEvaluator.throwUnauthorized()
        }
        return service.edit(id, model)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        val canEdit = groupEvaluator.hasGroup(authentication, "model.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canEdit) {
            groupEvaluator.throwUnauthorized()
        }
        service.delete(id)
        return true
    }
}
