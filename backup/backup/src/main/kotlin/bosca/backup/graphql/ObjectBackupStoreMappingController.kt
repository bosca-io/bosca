package bosca.backup.graphql

import bosca.backup.ObjectBackupStoreMapping
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Resolves one logical store's bound storage identity. */
@TypeController("ObjectBackupStoreMapping")
class ObjectBackupStoreMappingController : GraphQLController<ObjectBackupStoreMapping> {
    @Field
    fun store(mapping: ObjectBackupStoreMapping) = mapping.store

    @Field
    fun storageKey(mapping: ObjectBackupStoreMapping) = mapping.storageKey
}
