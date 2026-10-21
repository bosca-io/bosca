package bosca.backup.graphql

import bosca.backup.PostgresBackupContentInventory
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Exposes the recorded inventory file's identity without implying retained asset coverage. */
@TypeController("PostgresBackupContentInventory")
class PostgresBackupContentInventoryController : GraphQLController<PostgresBackupContentInventory> {
    @Field
    fun count(value: PostgresBackupContentInventory) = value.count

    @Field
    fun length(value: PostgresBackupContentInventory) = value.length

    @Field
    fun sha256(value: PostgresBackupContentInventory) = value.sha256

    @Field
    fun path(value: PostgresBackupContentInventory) = value.path

    @Field
    fun version(value: PostgresBackupContentInventory) = value.version
}
