package bosca.profile.bookmark.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.bookmark.model.ProfileBookmark
import bosca.serialization.UUID

@Repository
interface ProfileBookmarkRepository {

    @Query("INSERT INTO profile_bookmarks (profile_id, collection_id, metadata_id, metadata_version, attributes) VALUES (:profileId, :collectionId, :metadataId, :metadataVersion, :attributes) returning *")
    suspend fun add(bookmark: ProfileBookmark): ProfileBookmark

    @Query("SELECT * FROM profile_bookmarks WHERE profile_id = :profileId ORDER BY created DESC LIMIT :limit OFFSET :offset")
    suspend fun findByProfileId(profileId: UUID, limit: Int, offset: Int): List<ProfileBookmark>

    @Query("SELECT COUNT(*) FROM profile_bookmarks WHERE profile_id = :profileId")
    suspend fun countByProfileId(profileId: UUID): Long

    @Query("SELECT * FROM profile_bookmarks WHERE profile_id = :profileId AND metadata_id = :metadataId AND metadata_version = :metadataVersion LIMIT 1")
    suspend fun findByProfileAndMetadata(profileId: UUID, metadataId: UUID, metadataVersion: Int): ProfileBookmark?

    @Query("SELECT * FROM profile_bookmarks WHERE profile_id = :profileId AND collection_id = :collectionId LIMIT 1")
    suspend fun findByProfileAndCollection(profileId: UUID, collectionId: UUID): ProfileBookmark?

    @Query("delete from profile_bookmarks where id = :id")
    suspend fun deleteById(id: Long)
}
