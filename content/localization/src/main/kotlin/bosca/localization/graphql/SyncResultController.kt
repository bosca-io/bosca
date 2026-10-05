package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.SyncResult

/** Resolves field-level data on [SyncResult]. */
@TypeController
class SyncResultController : GraphQLController<SyncResult> {

    @Field
    fun stringsAdded(syncResult: SyncResult): Int = syncResult.stringsAdded

    @Field
    fun stringsUpdated(syncResult: SyncResult): Int = syncResult.stringsUpdated

    @Field
    fun translationsAdded(syncResult: SyncResult): Int = syncResult.translationsAdded

    @Field
    fun translationsUpdated(syncResult: SyncResult): Int = syncResult.translationsUpdated

    @Field
    fun errors(syncResult: SyncResult): List<String> = syncResult.errors
}
