package bosca.storage.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemModel
import bosca.storage.service.StorageSystemService

@TypeController
class StorageSystemController(
    private val storageSystemService: StorageSystemService
) : GraphQLController<StorageSystem> {

    @Field
    fun id(storageSystem: StorageSystem) = storageSystem.id

    @Field
    fun name(storageSystem: StorageSystem) = storageSystem.name

    @Field
    fun description(storageSystem: StorageSystem) = storageSystem.description

    @Field
    fun type(storageSystem: StorageSystem) = storageSystem.type

    @Field
    fun configuration(storageSystem: StorageSystem) = storageSystem.configuration

    @Field
    suspend fun models(storageSystem: StorageSystem): List<StorageSystemModel> {
        return storageSystemService.getModels(storageSystem.id)
    }
}
