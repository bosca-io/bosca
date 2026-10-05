package bosca.category.graphql

import bosca.category.model.Category
import bosca.category.model.CategoryInput
import bosca.category.service.CategoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object CategoryMutation

@TypeController
class CategoryMutationController(
    private val service: CategoryService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<CategoryMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, category: CategoryInput): Category {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.add(category)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, category: CategoryInput): Category {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.edit(id, category)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        service.delete(id)
        return true
    }
}
