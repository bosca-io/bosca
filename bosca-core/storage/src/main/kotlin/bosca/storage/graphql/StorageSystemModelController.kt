package bosca.storage.graphql

import bosca.ai.models.model.Model
import bosca.ai.models.service.ModelService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.storage.model.StorageSystemModel

@TypeController
class StorageSystemModelController(
    private val modelService: ModelService
) : GraphQLController<StorageSystemModel> {

    @Field
    suspend fun model(storageSystemModel: StorageSystemModel): Model? {
        return modelService.get(storageSystemModel.modelId)
    }
}
