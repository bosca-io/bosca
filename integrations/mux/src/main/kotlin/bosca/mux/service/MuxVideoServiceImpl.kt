package bosca.mux.service

import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.content.metadata.model.Media
import bosca.content.metadata.model.MediaConstants
import bosca.content.metadata.model.MediaProcessingOptions
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MediaService
import bosca.content.video.service.VideoService
import bosca.di.annotation.ProviderName
import bosca.mux.client.MuxClient
import bosca.mux.configuration.MuxConfiguration
import bosca.mux.jobs.DeleteFromMuxExecutor
import bosca.mux.jobs.DeleteFromMuxJob
import bosca.mux.jobs.MuxJobQueueNames
import bosca.mux.jobs.UploadToMuxExecutor
import bosca.mux.jobs.UploadToMuxJob
import bosca.service.annotation.ServiceImplementation
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.enqueue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Mux-backed implementation of [VideoService] that submits uploaded video
 * content to Mux for adaptive-bitrate transcoding.
 *
 * When [process] is called, the service checks whether a Mux configuration
 * exists in the platform's configuration store. If present, it enqueues a
 * single [UploadToMuxJob] on the dedicated Mux job queue. That job drives
 * the entire lifecycle — streaming bytes into a Mux Direct Upload, polling
 * the upload until Mux assigns an asset id, and then polling the asset
 * until it reaches `ready` — re-scheduling itself via `DelayException`
 * between phases.
 */
@ServiceImplementation
class MuxVideoServiceImpl(
    private val configurationService: ConfigurationService,
    @ProviderName(MuxJobQueueNames.muxJobQueue)
    private val muxJobQueue: JobQueue,
    private val mediaService: MediaService,
    private val muxClient: MuxClient,
    private val json: Json,
) : VideoService {

    override suspend fun process(metadata: Metadata, options: MediaProcessingOptions?) {
        val config = configurationService.getValueAs<MuxConfiguration>("mux", json)
        if (config == null) {
            log.debug("No Mux configuration found, skipping video processing for metadata {}", metadata.id)
            return
        }
        val existing = mediaService.getMedia(metadata.id)
        if (existing != null) {
            log.info("Media record already exists for metadata {}, skipping", metadata.id)
            return
        }
        mediaService.addMedia(
            Media(
                metadataId = metadata.id,
                status = "queued",
                providerAttributes = buildJsonObject {
                    put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                    put(MediaConstants.ATTR_CREATED_AT_MS, System.currentTimeMillis())
                    options?.videoQuality?.let { put(MediaConstants.ATTR_VIDEO_QUALITY, it) }
                    options?.maxResolutionTier?.let { put(MediaConstants.ATTR_MAX_RESOLUTION_TIER, it) }
                    options?.thumbnailTimeOffsetSeconds?.let { put(MediaConstants.ATTR_THUMBNAIL_TIME_OFFSET, it) }
                },
            )
        )
        UploadToMuxJob(metadata.id, metadata.version).enqueue(muxJobQueue, UploadToMuxExecutor::class)
        log.info("Enqueued Mux upload for metadata {}", metadata.id)
    }

    override suspend fun delete(metadata: Metadata) {
        val config = configurationService.getValueAs<MuxConfiguration>("mux", json)
        if (config == null || config.tokenId.isEmpty() || config.tokenSecret.isEmpty()) {
            log.debug("No Mux configuration found, skipping media deletion for metadata {}", metadata.id)
            return
        }
        val media = mediaService.getMedia(metadata.id)
        val assetId = media?.providerAttributes
            ?.jsonObject
            ?.get(MediaConstants.ATTR_ASSET_ID)
            ?.jsonPrimitive
            ?.content
        if (media != null && assetId == null) {
            log.warn("Media record for metadata {} has no Mux asset ID, cleaning up record only", metadata.id)
            mediaService.deleteMedia(metadata.id)
            return
        }
        if (assetId == null) {
            log.debug("No media record for metadata {}, nothing to delete from Mux", metadata.id)
            return
        }
        DeleteFromMuxJob(metadata.id, metadata.version, assetId).enqueue(muxJobQueue, DeleteFromMuxExecutor::class)
        log.info("Enqueued Mux deletion for metadata {} (asset {})", metadata.id, assetId)
    }

    override suspend fun updateMediaSettings(metadata: Metadata, options: MediaProcessingOptions) {
        if (options.videoQuality == null && options.maxResolutionTier == null) return
        val media = mediaService.getMedia(metadata.id)
            ?: error("No media record found for metadata ${metadata.id}")

        val attrs = media.providerAttributes as? kotlinx.serialization.json.JsonObject ?: buildJsonObject { }
        val updatedAttrs = buildJsonObject {
            for ((key, value) in attrs) put(key, value)
            options.videoQuality?.let { put(MediaConstants.ATTR_VIDEO_QUALITY, it) }
            options.maxResolutionTier?.let { put(MediaConstants.ATTR_MAX_RESOLUTION_TIER, it) }
        }
        mediaService.updateMedia(media.copy(providerAttributes = updatedAttrs))
        log.info(
            "Updated media settings for metadata {} (quality={}, resolution={})",
            metadata.id, options.videoQuality, options.maxResolutionTier
        )
    }

    override suspend fun setThumbnailTimeOffset(metadata: Metadata, offsetSeconds: Double) {
        val config = configurationService.getValueAs<MuxConfiguration>("mux", json)
            ?: error("Mux configuration not found")
        val media = mediaService.getMedia(metadata.id)
            ?: error("No media record found for metadata ${metadata.id}")
        require(media.status == "ready") { "Media must be ready to change thumbnail offset" }

        val imageBase = config.imageBaseUrl.trimEnd('/')
        val attrs = media.providerAttributes as? kotlinx.serialization.json.JsonObject ?: buildJsonObject { }
        val playbackId = attrs[MediaConstants.ATTR_PLAYBACK_ID]?.jsonPrimitive?.content
            ?: media.hlsUrl?.substringAfterLast('/')?.substringBefore('.')
            ?: error("No playback ID found for metadata ${metadata.id}")

        val thumbnailUrl = if (offsetSeconds > 0) {
            "$imageBase/$playbackId/thumbnail.jpg?time=$offsetSeconds"
        } else {
            "$imageBase/$playbackId/thumbnail.jpg"
        }

        val updatedAttrs = buildJsonObject {
            for ((key, value) in attrs) put(key, value)
            put(MediaConstants.ATTR_THUMBNAIL_TIME_OFFSET, offsetSeconds)
        }

        mediaService.updateMedia(
            media.copy(
                thumbnailUrl = thumbnailUrl,
                providerAttributes = updatedAttrs,
            )
        )
        log.info("Updated thumbnail offset to {}s for metadata {}", offsetSeconds, metadata.id)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MuxVideoServiceImpl::class.java)
    }
}
