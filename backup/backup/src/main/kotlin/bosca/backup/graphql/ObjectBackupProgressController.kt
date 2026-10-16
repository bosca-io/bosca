package bosca.backup.graphql

import bosca.backup.ObjectBackupProgress
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Exposes the latest state of an operation-specific object checkpoint. */
@TypeController("ObjectBackupProgress")
class ObjectBackupProgressController : GraphQLController<ObjectBackupProgress> {
    @Field
    fun key(progress: ObjectBackupProgress) = progress.key

    @Field
    fun sourceId(progress: ObjectBackupProgress) = progress.sourceId

    @Field
    fun destinationId(progress: ObjectBackupProgress) = progress.destinationId

    @Field
    fun scope(progress: ObjectBackupProgress) = progress.scope

    @Field
    fun operation(progress: ObjectBackupProgress) = progress.operation

    @Field
    fun payload(progress: ObjectBackupProgress) = progress.payload

    @Field
    fun status(progress: ObjectBackupProgress) = progress.status

    @Field
    fun attempts(progress: ObjectBackupProgress) = progress.attempts

    @Field
    fun verifiedBytes(progress: ObjectBackupProgress) = progress.verifiedBytes

    @Field
    fun error(progress: ObjectBackupProgress) = progress.error

    @Field
    fun version(progress: ObjectBackupProgress) = progress.version

    @Field
    fun created(progress: ObjectBackupProgress) = progress.created

    @Field
    fun modified(progress: ObjectBackupProgress) = progress.modified
}
