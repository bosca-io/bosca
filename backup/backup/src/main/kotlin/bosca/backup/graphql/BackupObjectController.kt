package bosca.backup.graphql

import bosca.backup.BackupObject
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Resolves the declared source identity and payload metadata of one object revision. */
@TypeController("BackupObject")
class BackupObjectController : GraphQLController<BackupObject> {
    @Field
    fun store(payload: BackupObject) = payload.store

    @Field
    fun key(payload: BackupObject) = payload.key

    @Field
    fun revision(payload: BackupObject) = payload.revision

    @Field
    fun length(payload: BackupObject) = payload.length

    @Field
    fun sha256(payload: BackupObject) = payload.sha256

    @Field
    fun payloadPath(payload: BackupObject) = payload.payloadPath
}
