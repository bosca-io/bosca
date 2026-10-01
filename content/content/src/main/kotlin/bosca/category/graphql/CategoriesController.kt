package bosca.category.graphql

import bosca.category.model.Category
import bosca.category.service.CategoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

object Categories

@TypeController
class CategoriesController(
    private val service: CategoryService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Categories> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Category> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return service.getAll()
    }
}
