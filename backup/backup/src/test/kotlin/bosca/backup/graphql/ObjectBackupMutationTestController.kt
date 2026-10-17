package bosca.backup.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.MutationRoot
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Mirrors the server's existing backups mutation namespace accessor. */
@TypeController("Mutation")
class ObjectBackupMutationTestController : GraphQLController<MutationRoot> {
    @Field
    fun backups(): BackupsMutation = BackupsMutation
}
