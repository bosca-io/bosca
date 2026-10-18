package bosca.backup.graphql

import bosca.backup.ObjectBackupDispatch
import bosca.backup.ObjectBackupDispatchService
import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupRun
import bosca.backup.ObjectBackupRunService
import bosca.backup.ObjectBackupRunStorage
import bosca.backup.ObjectBackupStorage
import bosca.backup.ObjectBackupStorageService
import bosca.backup.ObjectBackupStoreMapping
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Configures and dispatches declared inventory runs under existing backup authorization. */
@TypeController("ObjectBackupRunsMutation")
class ObjectBackupRunsMutationController(
    private val service: ObjectBackupRunService,
    private val groupEvaluator: GroupEvaluator,
    private val storageService: ObjectBackupStorageService,
    private val dispatchService: ObjectBackupDispatchService,
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

    @Field
    suspend fun bindStorage(authorization: AuthenticationContext, keys: List<String>): List<ObjectBackupStorage> {
        groupEvaluator.verifyHasSaGroup(authorization)
        return storageService.bindConfigured(keys.toSet())
    }

    @Field
    suspend fun configureStorage(
        authorization: AuthenticationContext,
        key: String,
        backupKey: String,
        stores: List<ObjectBackupStoreMapping>,
    ): ObjectBackupRunStorage {
        groupEvaluator.verifyHasSaGroup(authorization)
        val mapped = stores.associate { it.store to it.storageKey }
        require(mapped.size == stores.size) { "Duplicate logical object store mapping" }
        return service.configureStorage(key, backupKey, mapped)
    }

    @Field
    suspend fun dispatch(
        authorization: AuthenticationContext,
        key: String,
        expectedVersion: Long,
    ): ObjectBackupDispatch {
        groupEvaluator.verifyHasSaGroup(authorization)
        return dispatchService.request(key, expectedVersion)
    }

    @Field
    suspend fun retry(
        authorization: AuthenticationContext,
        key: String,
        expectedVersion: Long,
    ): ObjectBackupRun {
        groupEvaluator.verifyHasSaGroup(authorization)
        return service.retry(key, expectedVersion)
    }

    @Field
    suspend fun cancelPending(
        authorization: AuthenticationContext,
        key: String,
        expectedVersion: Long,
        reason: String,
    ): ObjectBackupRun {
        groupEvaluator.verifyHasSaGroup(authorization)
        return service.cancelPending(key, expectedVersion, reason)
    }
}
