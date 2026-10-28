package bosca.backup.graphql

import bosca.backup.PostgresBackupManifest
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Exposes recorded native database backup metadata without runtime credentials. */
@TypeController("PostgresBackupManifest")
class PostgresBackupManifestController : GraphQLController<PostgresBackupManifest> {
    @Field
    fun id(value: PostgresBackupManifest) = value.id

    @Field
    fun sourceId(value: PostgresBackupManifest) = value.sourceId

    @Field
    fun database(value: PostgresBackupManifest) = value.database

    @Field
    fun created(value: PostgresBackupManifest) = value.created

    @Field
    fun completed(value: PostgresBackupManifest) = value.completed

    @Field
    fun snapshotId(value: PostgresBackupManifest) = value.snapshotId

    @Field
    fun snapshot(value: PostgresBackupManifest) = value.snapshot

    @Field
    fun serverVersion(value: PostgresBackupManifest) = value.serverVersion

    @Field
    fun serverVersionNumber(value: PostgresBackupManifest) = value.serverVersionNumber

    @Field
    fun encoding(value: PostgresBackupManifest) = value.encoding

    @Field
    fun length(value: PostgresBackupManifest) = value.length

    @Field
    fun sha256(value: PostgresBackupManifest) = value.sha256

    @Field
    fun payloadPath(value: PostgresBackupManifest) = value.payloadPath

    @Field
    fun version(value: PostgresBackupManifest) = value.version

    @Field
    fun format(value: PostgresBackupManifest) = value.format

    @Field
    fun extensions(value: PostgresBackupManifest) = JsonObject(value.extensions.mapValues { JsonPrimitive(it.value) })

    @Field
    fun requiredKeys(value: PostgresBackupManifest) = JsonObject(value.requiredKeys.mapValues { (_, tables) ->
        JsonArray(tables.map(::JsonPrimitive))
    })
}
