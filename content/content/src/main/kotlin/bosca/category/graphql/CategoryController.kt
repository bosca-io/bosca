package bosca.category.graphql

import bosca.category.model.Category
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class CategoryController : GraphQLController<Category> {

    @Field
    fun id(category: Category) = category.id

    @Field
    fun name(category: Category) = category.name
}
