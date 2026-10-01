package bosca.storage.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.storage.model.StorageSystem
import bosca.storage.service.StorageSystemService

object StorageSystems

@TypeController
class StorageSystemsController(
    private val storageSystemService: StorageSystemService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<StorageSystems> {

    @Field
    suspend fun all(authenticationContext: AuthenticationContext): List<StorageSystem> {
        groupEvaluator.verifyHasAdminGroup(authenticationContext)
        return storageSystemService.getAll()
    }

    @Field
    suspend fun storageSystem(authenticationContext: AuthenticationContext, id: UUID): StorageSystem? {
        groupEvaluator.verifyHasAdminGroup(authenticationContext)
        return storageSystemService.get(id)
    }
}