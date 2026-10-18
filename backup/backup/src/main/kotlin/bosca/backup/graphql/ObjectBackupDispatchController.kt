package bosca.backup.graphql

import bosca.backup.ObjectBackupDispatch
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Resolves the immutable queue identity for one requested run version. */
@TypeController("ObjectBackupDispatch")
class ObjectBackupDispatchController : GraphQLController<ObjectBackupDispatch> {
    @Field
    fun key(dispatch: ObjectBackupDispatch) = dispatch.key

    @Field
    fun runKey(dispatch: ObjectBackupDispatch) = dispatch.runKey

    @Field
    fun version(dispatch: ObjectBackupDispatch) = dispatch.version

    @Field
    fun created(dispatch: ObjectBackupDispatch) = dispatch.created
}
