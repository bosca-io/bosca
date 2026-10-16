package bosca.backup.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.QueryRoot
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Mirrors the server's existing backups query namespace accessor. */
@TypeController("Query")
class ObjectBackupQueryTestController : GraphQLController<QueryRoot> {
    @Field
    fun backups(): Backups = Backups
}
