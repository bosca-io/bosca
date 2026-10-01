@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.LocalizationSyncState
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/** Resolves field-level data on [LocalizationSyncState]. */
@TypeController
class LocalizationSyncStateController : GraphQLController<LocalizationSyncState> {

    @Field
    fun id(syncState: LocalizationSyncState): UUID = syncState.id

    @Field
    fun projectId(syncState: LocalizationSyncState): UUID = syncState.projectId

    @Field
    fun provider(syncState: LocalizationSyncState): String = syncState.provider

    @Field
    fun externalId(syncState: LocalizationSyncState): String? = syncState.externalId

    @Field
    fun lastSynced(syncState: LocalizationSyncState): OffsetDateTime? = syncState.lastSynced

    @Field
    fun syncConfig(syncState: LocalizationSyncState): JsonElement? = syncState.syncConfig

    @Field
    fun attributes(syncState: LocalizationSyncState): JsonElement? = syncState.attributes
}
