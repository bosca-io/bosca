package bosca.backup.graphql

import bosca.backup.ObjectBackupProgress
import bosca.backup.ObjectBackupRun
import bosca.backup.ObjectBackupRunService
import bosca.backup.ObjectBackupRunSummary
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Exposes fixed run intent, recorded outcomes and independently queried current checkpoint health. */
@TypeController("ObjectBackupRun")
class ObjectBackupRunController(
    private val service: ObjectBackupRunService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<ObjectBackupRun> {
    @Field
    fun key(run: ObjectBackupRun) = run.key

    @Field
    fun sourceId(run: ObjectBackupRun) = run.sourceId

    @Field
    fun destinationId(run: ObjectBackupRun) = run.destinationId

    @Field
    fun scope(run: ObjectBackupRun) = run.scope

    @Field
    fun operation(run: ObjectBackupRun) = run.operation

    @Field
    fun inventory(run: ObjectBackupRun) = run.inventory

    @Field
    fun path(run: ObjectBackupRun) = run.path

    @Field
    fun status(run: ObjectBackupRun) = run.status

    @Field
    fun attempts(run: ObjectBackupRun) = run.attempts

    @Field
    fun error(run: ObjectBackupRun) = run.error

    @Field
    fun result(run: ObjectBackupRun) = run.result

    @Field
    fun version(run: ObjectBackupRun) = run.version

    @Field
    fun created(run: ObjectBackupRun) = run.created

    @Field
    fun modified(run: ObjectBackupRun) = run.modified

    @Field
    suspend fun summary(authorization: AuthenticationContext, run: ObjectBackupRun): ObjectBackupRunSummary {
        groupEvaluator.verifyHasSaGroup(authorization)
        return service.summary(run.key)
    }

    @Field
    suspend fun items(
        authorization: AuthenticationContext,
        run: ObjectBackupRun,
        offset: Int,
        limit: Int,
    ): List<ObjectBackupProgress> {
        groupEvaluator.verifyHasSaGroup(authorization)
        val pageSize = objectBackupPageSize(offset, limit)
        return service.items(run.key, offset.toLong(), pageSize)
    }
}
