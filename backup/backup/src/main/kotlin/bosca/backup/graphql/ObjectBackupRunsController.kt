package bosca.backup.graphql

import bosca.backup.ObjectBackupOperation
import bosca.backup.ObjectBackupRun
import bosca.backup.ObjectBackupRunService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Reads existing run state through the core service under backup administration authorization. */
@TypeController("ObjectBackupRuns")
class ObjectBackupRunsController(
    private val service: ObjectBackupRunService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<ObjectBackupRuns> {
    @Field
    suspend fun list(
        authorization: AuthenticationContext,
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Int,
        limit: Int,
    ): List<ObjectBackupRun> {
        groupEvaluator.verifyHasSaGroup(authorization)
        val pageSize = objectBackupPageSize(offset, limit)
        return service.list(operation, sourceId, destinationId, scope, offset.toLong(), pageSize)
    }

    @Field
    suspend fun run(authorization: AuthenticationContext, key: String): ObjectBackupRun? {
        groupEvaluator.verifyHasSaGroup(authorization)
        return service.get(key)
    }

    @Field
    suspend fun lastSuccessful(
        authorization: AuthenticationContext,
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
    ): ObjectBackupRun? {
        groupEvaluator.verifyHasSaGroup(authorization)
        return service.lastSuccessful(operation, sourceId, destinationId, scope)
    }
}
