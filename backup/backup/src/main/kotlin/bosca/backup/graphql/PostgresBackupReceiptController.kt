package bosca.backup.graphql

import bosca.backup.PostgresBackupReceipt
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Exposes recorded native database backup metadata without runtime credentials. */
@TypeController("PostgresBackupReceipt")
class PostgresBackupReceiptController : GraphQLController<PostgresBackupReceipt> {
    @Field
    fun key(value: PostgresBackupReceipt) = value.key

    @Field
    fun storageKey(value: PostgresBackupReceipt) = value.storageKey

    @Field
    fun path(value: PostgresBackupReceipt) = value.path

    @Field
    fun manifest(value: PostgresBackupReceipt) = value.manifest
}
