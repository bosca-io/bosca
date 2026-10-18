package bosca.backup.graphql

import bosca.backup.ObjectBackupStorage
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Resolves saved address fields without reading runtime credentials or opening storage. */
@TypeController("ObjectBackupStorage")
class ObjectBackupStorageController : GraphQLController<ObjectBackupStorage> {
    @Field
    fun key(storage: ObjectBackupStorage) = storage.key

    @Field
    fun type(storage: ObjectBackupStorage) = storage.type

    @Field
    fun endpoint(storage: ObjectBackupStorage) = storage.endpoint

    @Field
    fun bucket(storage: ObjectBackupStorage) = storage.bucket

    @Field
    fun region(storage: ObjectBackupStorage) = storage.region

    @Field
    fun basePath(storage: ObjectBackupStorage) = storage.basePath

    @Field
    fun created(storage: ObjectBackupStorage) = storage.created
}
