package bosca.backup.graphql

import bosca.backup.BackupRecord
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID
import java.time.OffsetDateTime

/**
 * Resolves fields on the [Backup][BackupRecord] GraphQL type by delegating
 * to the underlying [BackupRecord] data class properties.
 */
@TypeController("Backup")
class BackupController : GraphQLController<BackupRecord> {

    @Field
    fun id(backup: BackupRecord) = backup.id

    @Field
    fun status(backup: BackupRecord) = backup.status

    @Field
    fun path(backup: BackupRecord) = backup.path

    @Field
    fun error(backup: BackupRecord) = backup.error

    @Field
    fun includeFiles(backup: BackupRecord) = backup.includeFiles

    @Field
    fun metadataId(backup: BackupRecord): UUID? = backup.metadataId

    @Field
    fun created(backup: BackupRecord): OffsetDateTime = backup.created

    @Field
    fun modified(backup: BackupRecord): OffsetDateTime = backup.modified
}
