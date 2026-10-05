package bosca.content.video.service

import bosca.content.metadata.model.MediaProcessingOptions
import bosca.content.metadata.model.Metadata
import bosca.service.Service

/**
 * Service for processing video content associated with metadata entries.
 * Implementations submit video files to external transcoding services
 * (e.g., Mux) for adaptive-bitrate streaming when the required
 * configuration is present.
 */
interface VideoService : Service {

    /**
     * Initiates video processing for the given metadata entry by submitting
     * it to a configured transcoding service. If no transcoding service is
     * configured or the content has already been submitted, the call is a
     * no-op.
     *
     * @param metadata the metadata entry whose video content should be processed
     * @param options optional per-item overrides for quality and resolution settings
     */
    suspend fun process(metadata: Metadata, options: MediaProcessingOptions? = null)

    /**
     * Removes the processed media asset from the external transcoding
     * provider and deletes the local media record. If no media record
     * exists or no transcoding service is configured, the call is a no-op.
     *
     * @param metadata the metadata entry whose processed media should be deleted
     */
    suspend fun delete(metadata: Metadata)

    /**
     * Updates the thumbnail time offset for a processed media asset,
     * rebuilding the thumbnail URL without re-processing the asset.
     *
     * @param metadata the metadata entry whose thumbnail offset should be changed
     * @param offsetSeconds the time offset in seconds into the video for the thumbnail
     */
    suspend fun setThumbnailTimeOffset(metadata: Metadata, offsetSeconds: Double)

    /**
     * Updates the video quality and/or max resolution tier on a processed
     * media asset, triggering re-transcoding on the provider side.
     *
     * @param metadata the metadata entry whose media settings should be changed
     * @param options the new quality settings to apply
     */
    suspend fun updateMediaSettings(metadata: Metadata, options: MediaProcessingOptions)
}
