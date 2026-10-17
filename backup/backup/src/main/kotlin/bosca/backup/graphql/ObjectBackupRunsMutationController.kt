package bosca.backup.graphql

import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupRun
import bosca.backup.ObjectBackupRunService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Prepares declared inventory runs through the owning service and existing backup authorization. */
@TypeController("ObjectBackupRunsMutation")
class ObjectBackupRunsMutationController(
    private val service: ObjectBackupRunService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<ObjectBackupRunsMutation> {
    @Field
    suspend fun prepare(
        authorization: AuthenticationContext,
        inventory: ObjectBackupManifest,
        destinationId: String,
    ): ObjectBackupRun {
        groupEvaluator.verifyHasSaGroup(authorization)
        return service.prepare(inventory, destinationId)
    }

    @Field
    suspend fun prepareRestore(
        authorization: AuthenticationContext,
        inventory: ObjectBackupManifest,
        destinationId: String,
        path: String,
    ): ObjectBackupRun {
        groupEvaluator.verifyHasSaGroup(authorization)
        return service.prepareRestore(inventory, destinationId, path)
    }
}
