package bosca.content.metadata.repository

import bosca.content.metadata.model.Media
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Repository for persisting and retrieving processed media records
 * associated with metadata entries. Each metadata item has at most
 * one media row, keyed by [Media.metadataId].
 */
@Repository
interface MediaRepository {

    @Query("""
        insert into metadata_media (metadata_id, status, hls_url, hls_audio_only_url, download_url,
            thumbnail_url, animated_preview_url, duration_seconds, max_resolution, aspect_ratio,
            transcriptions, provider_attributes, created, modified)
        values (:metadataId, :status, :hlsUrl, :hlsAudioOnlyUrl, :downloadUrl,
            :thumbnailUrl, :animatedPreviewUrl, :durationSeconds, :maxResolution, :aspectRatio,
            :transcriptions, :providerAttributes, :created, :modified)
        returning *
    """)
    suspend fun add(media: Media): Media

    @Query("""
        update metadata_media
        set status = :status, hls_url = :hlsUrl, hls_audio_only_url = :hlsAudioOnlyUrl,
            download_url = :downloadUrl, thumbnail_url = :thumbnailUrl,
            animated_preview_url = :animatedPreviewUrl, duration_seconds = :durationSeconds,
            max_resolution = :maxResolution, aspect_ratio = :aspectRatio,
            transcriptions = :transcriptions, provider_attributes = :providerAttributes,
            modified = now()
        where metadata_id = :metadataId
        returning *
    """)
    suspend fun update(media: Media): Media

    @Query("select * from metadata_media where metadata_id = :metadataId")
    suspend fun getByMetadataId(metadataId: UUID): Media?

    @Query("select * from metadata_media where metadata_id = any(:metadataIds)")
    suspend fun getByMetadataIds(metadataIds: List<UUID>): List<Media>

    @Query("delete from metadata_media where metadata_id = :metadataId")
    suspend fun deleteByMetadataId(metadataId: UUID)
}
