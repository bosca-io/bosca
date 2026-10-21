package bosca.backup.graphql

import bosca.backup.PostgresBackupContentSource
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Exposes fixed logical/configured source identities without runtime credentials. */
@TypeController("PostgresBackupContentSource")
class PostgresBackupContentSourceController : GraphQLController<PostgresBackupContentSource> {
    @Field
    fun store(value: PostgresBackupContentSource) = value.store

    @Field
    fun storageKey(value: PostgresBackupContentSource) = value.storageKey

    @Field
    fun scope(value: PostgresBackupContentSource) = value.scope
}
