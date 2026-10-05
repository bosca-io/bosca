@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.localization.model.LocalizationStringMetadata
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data access for `localization.string_metadata`, which tracks metadata items
 * attached to localization strings for translator context.
 */
@Repository
interface LocalizationStringMetadataRepository {

    @Query("select * from localization.string_metadata where string_id = :stringId order by created")
    suspend fun getByStringId(stringId: UUID): List<LocalizationStringMetadata>

    @Query("insert into localization.string_metadata (string_id, metadata_id) values (:stringId, :metadataId) on conflict do nothing")
    suspend fun add(stringId: UUID, metadataId: UUID)

    @Query("delete from localization.string_metadata where string_id = :stringId and metadata_id = :metadataId")
    suspend fun delete(stringId: UUID, metadataId: UUID)
}
