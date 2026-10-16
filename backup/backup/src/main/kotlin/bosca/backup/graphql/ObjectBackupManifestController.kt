package bosca.backup.graphql

import bosca.backup.BackupObject
import bosca.backup.ObjectBackupManifest
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Reads inventory metadata and a bounded selection of its declared objects. */
@TypeController("ObjectBackupManifest")
class ObjectBackupManifestController : GraphQLController<ObjectBackupManifest> {
    @Field
    fun id(inventory: ObjectBackupManifest) = inventory.id

    @Field
    fun sourceId(inventory: ObjectBackupManifest) = inventory.sourceId

    @Field
    fun scope(inventory: ObjectBackupManifest) = inventory.scope

    @Field
    fun created(inventory: ObjectBackupManifest) = inventory.created

    @Field
    fun version(inventory: ObjectBackupManifest) = inventory.version

    @Field
    fun format(inventory: ObjectBackupManifest) = inventory.format

    @Field
    fun objects(inventory: ObjectBackupManifest, offset: Int, limit: Int): List<BackupObject> {
        val pageSize = objectBackupPageSize(offset, limit)
        val start = offset.coerceAtMost(inventory.objects.size)
        val count = pageSize.coerceAtMost(inventory.objects.size - start)
        return inventory.objects.subList(start, start + count)
    }
}
