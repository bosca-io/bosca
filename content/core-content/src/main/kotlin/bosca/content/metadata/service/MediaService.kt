package bosca.content.metadata.service

import bosca.content.metadata.model.Media
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing processed media records associated with metadata entries.
 * Each metadata item can have at most one media record representing its
 * transcoded streaming, download, and transcription data.
 */
interface MediaService : Service {

    /**
     * Retrieves the media record for the given metadata identifier.
     *
     * @param metadataId the parent metadata identifier
     * @return the media record, or null if no media has been processed for this metadata
     */
    suspend fun getMedia(metadataId: UUID): Media?

    /**
     * Creates a new media record for a metadata entry.
     *
     * @param media the media record to persist
     * @return the persisted media record
     */
    suspend fun addMedia(media: Media): Media

    /**
     * Updates an existing media record with new transcoding results
     * or status changes.
     *
     * @param media the updated media record
     * @return the persisted media record
     */
    suspend fun updateMedia(media: Media): Media

    /**
     * Removes the media record for a metadata entry, typically when
     * the upstream asset is deleted from the transcoding provider.
     *
     * @param metadataId the parent metadata identifier
     */
    suspend fun deleteMedia(metadataId: UUID)

    /**
     * Registers a batch loader for efficiently fetching media records
     * by metadata cache key in a single database round-trip.
     *
     * @param batch the batch accumulator to populate with media records
     */
    suspend fun addToBatch(batch: Batch<MetadataCacheKeyId, Media>)
}
