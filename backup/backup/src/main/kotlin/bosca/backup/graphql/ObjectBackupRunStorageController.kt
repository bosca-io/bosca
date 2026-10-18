package bosca.backup.graphql

import bosca.backup.ObjectBackupRunStorage
import bosca.backup.ObjectBackupStoreMapping
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Resolves the fixed storage mapping in a deterministic logical-store order. */
@TypeController("ObjectBackupRunStorage")
class ObjectBackupRunStorageController : GraphQLController<ObjectBackupRunStorage> {
    @Field
    fun key(storage: ObjectBackupRunStorage) = storage.key

    @Field
    fun backupKey(storage: ObjectBackupRunStorage) = storage.backupKey

    @Field
    fun created(storage: ObjectBackupRunStorage) = storage.created

    @Field
    fun stores(storage: ObjectBackupRunStorage): List<ObjectBackupStoreMapping> =
        storage.stores.toSortedMap().map { (store, key) -> ObjectBackupStoreMapping(store, key) }
}
