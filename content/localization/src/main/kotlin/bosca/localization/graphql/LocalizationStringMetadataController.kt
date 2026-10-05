@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.LocalizationStringMetadata
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Resolves field-level data on [LocalizationStringMetadata]. */
@TypeController
class LocalizationStringMetadataController : GraphQLController<LocalizationStringMetadata> {

    @Field
    fun stringId(entry: LocalizationStringMetadata): UUID = entry.stringId

    @Field
    fun metadataId(entry: LocalizationStringMetadata): UUID = entry.metadataId

    @Field
    fun created(entry: LocalizationStringMetadata): OffsetDateTime? = entry.created
}
