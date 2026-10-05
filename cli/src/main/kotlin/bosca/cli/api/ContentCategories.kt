package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.AddCategory
import bosca.graphql.gen.CategoryInput
import bosca.graphql.gen.EditCategory
import bosca.graphql.gen.GetCategories
import bosca.graphql.gen.ICategory
import kotlin.uuid.Uuid

class ContentCategories(network: NetworkClient) : Api(network) {

    suspend fun getAll(): List<ICategory> =
        network.boscaGraphql.execute(GetCategories, Unit).content.categories.all

    suspend fun add(category: CategoryInput): ICategory =
        network.boscaGraphql.execute(AddCategory, AddCategory.Variables(category)).content.category.add

    suspend fun edit(id: Uuid, category: CategoryInput): ICategory =
        network.boscaGraphql.execute(EditCategory, EditCategory.Variables(id, category)).content.category.edit

    suspend fun delete(id: Uuid) {
        TODO()
    }
}
