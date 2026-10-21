package bosca.backup.graphql

import bosca.backup.PostgresBackupRun
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Exposes recorded native database backup metadata without runtime credentials. */
@TypeController("PostgresBackupRun")
class PostgresBackupRunController : GraphQLController<PostgresBackupRun> {
    @Field
    fun key(value: PostgresBackupRun) = value.key

    @Field
    fun sourceId(value: PostgresBackupRun) = value.sourceId

    @Field
    fun storageKey(value: PostgresBackupRun) = value.storageKey

    @Field
    fun prefix(value: PostgresBackupRun) = value.prefix

    @Field
    fun includeContentReferences(value: PostgresBackupRun) = value.includeContentReferences

    @Field
    fun status(value: PostgresBackupRun) = value.status

    @Field
    fun attempts(value: PostgresBackupRun) = value.attempts

    @Field
    fun writerToken(value: PostgresBackupRun) = value.writerToken

    @Field
    fun error(value: PostgresBackupRun) = value.error

    @Field
    fun result(value: PostgresBackupRun) = value.result

    @Field
    fun version(value: PostgresBackupRun) = value.version

    @Field
    fun created(value: PostgresBackupRun) = value.created

    @Field
    fun modified(value: PostgresBackupRun) = value.modified
}
