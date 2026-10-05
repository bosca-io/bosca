package bosca.mux.jobs

import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.content.metadata.model.Media
import bosca.content.metadata.model.MediaConstants
import bosca.content.metadata.model.MediaConstants.ATTR_CREATED_AT_MS
import bosca.content.metadata.model.Transcription
import bosca.content.metadata.service.MediaService
import bosca.content.metadata.service.MetadataService
import bosca.mux.client.MuxClient
import bosca.mux.client.models.AssetMeta
import bosca.mux.configuration.MuxConfiguration
import bosca.mux.configuration.toClientConfig
import bosca.queue.annotations.IMetadataJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.job
import bosca.storage.service.ObjectStorageService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Job payload that drives a video metadata item through the full Mux
 * ingestion lifecycle.
 *
 * The same job is enqueued once and then re-schedules itself with
 * [DelayException] as the work progresses through three phases:
 *
 * 1. **Upload phase** — create a Mux Direct Upload and stream the
 *    metadata's primary content bytes into Mux's signed PUT URL.
 *    Persists the resulting `uploadId` on the media record.
 * 2. **Ingestion phase** — poll `GET /video/v1/uploads/{id}` until
 *    Mux reports the upload as `asset_created` and exposes the
 *    downstream `assetId`.
 * 3. **Readiness phase** — poll `GET /video/v1/assets/{id}` until the
 *    asset reaches `ready`, then populate HLS URLs, thumbnails,
 *    static renditions, and transcription tracks on the media record.
 */
@Serializable
data class UploadToMuxJob(
    override val id: UUID,
    override val version: Int? = null,
) : IMetadataJobDefinition

/**
 * Executor that ingests a Bosca media file into Mux for adaptive-bitrate
 * streaming by **pushing** bytes to Mux rather than having Mux pull from
 * Bosca's object store. The push-based flow replaced the legacy pull-based
 * `createAsset(url=...)` path because Mux's fetch retries were insufficient
 * for Bosca's storage reliability profile, leaving assets stuck in
 * `preparing` when a single fetch attempt failed.
 *
 * The executor is idempotent across job retries: on each invocation it
 * inspects the persisted [Media] record and resumes in whichever phase
 * the record indicates. State is carried entirely in the media record's
 * `providerAttributes`:
 *
 * - no `uploadId` and no `assetId` → create upload + stream bytes, then
 *   requeue to poll the upload.
 * - `uploadId` set, no `assetId` → poll the upload until Mux assigns an
 *   asset, then requeue to poll the asset.
 * - `assetId` set → poll asset readiness and, once ready, finalize the
 *   media record with playback URLs, tracks, and transcriptions.
 */
@JobDefinition(UploadToMuxJob::class, MuxJobQueueNames.muxJobQueue, "mux-upload")
class UploadToMuxExecutor(
    private val metadataService: MetadataService,
    private val configurationService: ConfigurationService,
    private val storageService: ObjectStorageService,
    private val muxClient: MuxClient,
    private val mediaService: MediaService,
    private val json: Json,
) : AbstractJobExecutor<UploadToMuxJob>(UploadToMuxJob.serializer()) {

    override suspend fun getLockId() = "metadata.mux.upload.${getJobDefinition().id}"

    override val skipExecutionIfLocked: Boolean = true

    override suspend fun execute() {
        val job = getJobDefinition()
        val metadata = metadataService.getById(job.id, job.version)
            ?: throw FailException("Metadata ${job.id} not found")

        val contentType = metadata.contentType.lowercase()
        if (!contentType.startsWith("video/")) {
            log.info("Skipping non-video content type {} for metadata {}", contentType, metadata.id)
            return
        }

        val config = configurationService.getValueAs<MuxConfiguration>("mux", json)
            ?: throw FailException("Mux configuration not found")

        val media = mediaService.getMedia(metadata.id)
            ?: throw FailException("No media record found for metadata ${metadata.id}")

        val attrs = media.providerAttributes as? JsonObject ?: buildJsonObject { }
        val existingAssetId = attrs[MediaConstants.ATTR_ASSET_ID]?.jsonPrimitive?.content
        val existingUploadId = attrs[MediaConstants.ATTR_UPLOAD_ID]?.jsonPrimitive?.content

        when {
            existingAssetId != null -> pollAssetReadiness(metadata.id, media, attrs, existingAssetId, config)
            existingUploadId != null -> pollUpload(media, attrs, existingUploadId, config)
            else -> startUpload(metadata, media, attrs, config)
        }
    }

    private suspend fun startUpload(
        metadata: bosca.content.metadata.model.Metadata,
        media: Media,
        attrs: JsonObject,
        config: MuxConfiguration,
    ) {
        val totalLength = metadata.contentLength
            ?: throw FailException("Metadata ${metadata.id} has no contentLength; cannot drive resumable Mux upload")

        // Transport-layer resume state — tus URL and Mux upload id — lives in the
        // job's persistent context rather than the media record, because the media
        // record's `providerAttributes.uploadId` is load-bearing for the state
        // machine: setting it there signals "bytes done, ingestion phase" to the
        // next execute() entry. Until streaming finishes, the upload id lives only
        // in the job context so retries know to re-enter startUpload (which handles
        // resume via `CTX_TUS_URL`) instead of pollUpload.
        val initialContext = (job().getContext() as? JsonObject) ?: buildJsonObject { }
        val contextTusUrl = initialContext[CTX_TUS_URL]?.jsonPrimitive?.content
        val contextUploadId = initialContext[CTX_UPLOAD_ID]?.jsonPrimitive?.content

        val tusUrl: String
        val uploadId: String
        val createdAtMs: Long
        if (contextTusUrl != null && contextUploadId != null) {
            tusUrl = contextTusUrl
            uploadId = contextUploadId
            createdAtMs = attrs[ATTR_CREATED_AT_MS]?.jsonPrimitive?.longOrNull
                ?: System.currentTimeMillis()
            log.info("Resuming Mux direct upload {} for metadata {}", uploadId, metadata.id)
        } else {
            val itemVideoQuality = attrs[MediaConstants.ATTR_VIDEO_QUALITY]?.jsonPrimitive?.content
            val itemMaxResolutionTier = attrs[MediaConstants.ATTR_MAX_RESOLUTION_TIER]?.jsonPrimitive?.content
            val direct = muxClient.createDirectUpload(
                config = config.toClientConfig(),
                passthrough = metadata.id.toString(),
                meta = AssetMeta(
                    externalId = metadata.id.toString(),
                    title = metadata.name,
                ),
                videoQualityOverride = itemVideoQuality,
                maxResolutionTierOverride = itemMaxResolutionTier,
            )
            log.info("Created Mux direct upload {} for metadata {}", direct.id, metadata.id)
            tusUrl = direct.url
            uploadId = direct.id
            createdAtMs = attrs[ATTR_CREATED_AT_MS]?.jsonPrimitive?.longOrNull
                ?: System.currentTimeMillis()

            // Seed the resume context BEFORE any network side effects so that a
            // crash between createDirectUpload and the first PATCH leaves a
            // recoverable state: next retry finds `tusUrl` + `uploadId` in context
            // and resumes without creating a second Mux upload.
            setContext(
                buildJsonObject {
                    put(CTX_TUS_URL, tusUrl)
                    put(CTX_UPLOAD_ID, uploadId)
                    put(CTX_UPLOAD_OFFSET, JsonPrimitive(0L))
                }
            )

            // Flip the media record to "uploading" for user-visible status, but
            // deliberately do NOT persist uploadId here — persisting it on the
            // record is the signal that streaming is complete.
            mediaService.updateMedia(
                media.copy(
                    status = "uploading",
                    providerAttributes = buildJsonObject {
                        put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                        put(ATTR_CREATED_AT_MS, createdAtMs)
                    },
                )
            )
        }

        val path = storageService.getPath(metadata)

        // Trust the server's committed offset over any locally-persisted value — a
        // PUT may have succeeded on GCS while the job context write failed. The
        // status query is cheap (empty PUT body).
        var offset = muxClient.getResumableUploadOffset(tusUrl, totalLength)
        log.info(
            "Resuming Mux upload {} for metadata {} at offset {}/{}",
            uploadId, metadata.id, offset, totalLength
        )

        while (offset < totalLength) {
            val chunkEnd = minOf(offset + CHUNK_SIZE_BYTES, totalLength)
            val chunkLength = chunkEnd - offset
            val chunkStart = offset
            storageService.getInputStreamRange(path, chunkStart until chunkEnd).use { stream ->
                offset = muxClient.putResumableUploadChunk(
                    uploadUrl = tusUrl,
                    offset = chunkStart,
                    chunkLength = chunkLength,
                    totalLength = totalLength,
                    inputStream = stream,
                )
            }
            setContext(
                buildJsonObject {
                    put(CTX_TUS_URL, tusUrl)
                    put(CTX_UPLOAD_ID, uploadId)
                    put(CTX_UPLOAD_OFFSET, JsonPrimitive(offset))
                }
            )
        }
        log.info("Finished streaming {} bytes to Mux for metadata {}", totalLength, metadata.id)

        // Streaming done: persist `uploadId` on the media record (this is the
        // "ingestion phase" signal for subsequent entries) and drop the transport
        // state from job context.
        mediaService.updateMedia(
            media.copy(
                status = "preparing",
                providerAttributes = buildJsonObject {
                    put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                    put(MediaConstants.ATTR_UPLOAD_ID, uploadId)
                    put(ATTR_CREATED_AT_MS, createdAtMs)
                },
            )
        )
        setContext(buildJsonObject { })

        throw DelayException(POLL_INTERVAL)
    }

    private suspend fun pollUpload(
        media: Media,
        attrs: JsonObject,
        uploadId: String,
        config: MuxConfiguration,
    ) {
        val upload = muxClient.getUpload(config.toClientConfig(), uploadId)

        when (upload.status) {
            "asset_created" -> {
                val assetId = upload.assetId
                    ?: throw FailException("Mux upload $uploadId reached asset_created but reported no asset id")
                log.info("Mux upload {} produced asset {} for metadata {}", uploadId, assetId, media.metadataId)

                mediaService.updateMedia(
                    media.copy(
                        providerAttributes = buildJsonObject {
                            put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                            put(MediaConstants.ATTR_UPLOAD_ID, uploadId)
                            put(MediaConstants.ATTR_ASSET_ID, assetId)
                            attrs[ATTR_CREATED_AT_MS]?.jsonPrimitive?.longOrNull?.let {
                                put(ATTR_CREATED_AT_MS, it)
                            }
                        },
                    )
                )

                // Requeue to begin polling asset readiness.
                throw DelayException(POLL_INTERVAL)
            }

            "errored", "cancelled", "timed_out" -> {
                mediaService.updateMedia(media.copy(status = "errored"))
                throw FailException(
                    "Mux upload $uploadId for metadata ${media.metadataId} ended in ${upload.status}" +
                        (upload.errorMessage?.let { ": $it" } ?: "")
                )
            }

            else -> {
                checkTimeout(media, attrs, config, "upload $uploadId")
                log.debug("Mux upload {} still {}, retrying later", uploadId, upload.status)
                throw DelayException(POLL_INTERVAL)
            }
        }
    }

    private suspend fun pollAssetReadiness(
        metadataId: UUID,
        media: Media,
        attrs: JsonObject,
        assetId: String,
        config: MuxConfiguration,
    ) {
        val assetStatus = muxClient.getAssetStatus(config.toClientConfig(), assetId)

        when (assetStatus.status) {
            "ready" -> {
                val playbackId = assetStatus.playbackIds.firstOrNull()?.id

                val streamBase = config.streamBaseUrl.trimEnd('/')
                val imageBase = config.imageBaseUrl.trimEnd('/')

                val transcriptions = assetStatus.tracks
                    .filter { it.type == "text" }
                    .map { track ->
                        Transcription(
                            id = track.id,
                            languageCode = track.languageCode ?: "unknown",
                            name = track.name ?: "${track.languageCode ?: "unknown"} (auto)",
                            status = track.status,
                            textUrl = if (track.status == "ready" && playbackId != null)
                                "$streamBase/$playbackId/text/${track.id}.txt" else null,
                            vttUrl = if (track.status == "ready" && playbackId != null)
                                "$streamBase/$playbackId/text/${track.id}.vtt" else null,
                        )
                    }

                val downloadUrl = assetStatus.staticRenditions
                    .firstOrNull { it.status == "ready" }
                    ?.let { playbackId?.let { pid -> "$streamBase/$pid/${it.name}" } }

                val updatedAttrs = buildJsonObject {
                    for ((key, value) in attrs) put(key, value)
                    playbackId?.let { put(MediaConstants.ATTR_PLAYBACK_ID, it) }
                }

                val updatedMedia = media.copy(
                    status = "ready",
                    hlsUrl = playbackId?.let { "$streamBase/$it.m3u8" },
                    hlsAudioOnlyUrl = playbackId?.let { "$streamBase/$it.m3u8?audio_only=true" },
                    downloadUrl = downloadUrl,
                    thumbnailUrl = playbackId?.let { pid ->
                        val timeOffset = attrs[MediaConstants.ATTR_THUMBNAIL_TIME_OFFSET]?.jsonPrimitive?.doubleOrNull
                        if (timeOffset != null) "$imageBase/$pid/thumbnail.jpg?time=$timeOffset"
                        else "$imageBase/$pid/thumbnail.jpg"
                    },
                    animatedPreviewUrl = playbackId?.let { "$imageBase/$it/animated.gif" },
                    durationSeconds = assetStatus.duration,
                    maxResolution = assetStatus.resolutionTier ?: assetStatus.maxStoredResolution,
                    aspectRatio = assetStatus.aspectRatio,
                    actualVideoQuality = assetStatus.videoQuality,
                    transcriptions = json.encodeToJsonElement(transcriptions),
                    providerAttributes = updatedAttrs,
                )

                mediaService.updateMedia(updatedMedia)
                log.info(
                    "Mux asset {} is ready for metadata {}, HLS URL: {}",
                    assetId, metadataId, updatedMedia.hlsUrl
                )

                if (transcriptions.any { it.status == "preparing" }) {
                    log.info("Transcription tracks still preparing for metadata {}, will re-check", metadataId)
                    throw DelayException(POLL_INTERVAL)
                }
            }

            "errored" -> {
                mediaService.updateMedia(media.copy(status = "errored"))
                throw FailException("Mux asset $assetId errored for metadata $metadataId")
            }

            else -> {
                checkTimeout(media, attrs, config, "asset $assetId")
                log.debug("Mux asset {} still {}, retrying later", assetId, assetStatus.status)
                throw DelayException(POLL_INTERVAL)
            }
        }
    }

    private suspend fun checkTimeout(
        media: Media,
        attrs: JsonObject,
        config: MuxConfiguration,
        target: String,
    ) {
        val createdAtMs = attrs[ATTR_CREATED_AT_MS]?.jsonPrimitive?.longOrNull ?: return
        val elapsedSeconds = (System.currentTimeMillis() - createdAtMs) / 1000
        if (elapsedSeconds > config.maxWaitSeconds) {
            mediaService.updateMedia(media.copy(status = "errored"))
            throw FailException(
                "Mux $target for metadata ${media.metadataId} " +
                    "timed out after ${elapsedSeconds}s (max: ${config.maxWaitSeconds}s)"
            )
        }
    }

    companion object {
        private val POLL_INTERVAL = 30.seconds

        /** Job-context key holding the active Mux tus upload URL. */
        private const val CTX_TUS_URL = "tusUrl"

        /** Job-context key holding the Mux direct-upload id while bytes are in flight (migrates to the media record once streaming finishes). */
        private const val CTX_UPLOAD_ID = "uploadId"

        /** Job-context key holding the last client-observed upload offset (advisory only; Mux is authoritative). */
        private const val CTX_UPLOAD_OFFSET = "uploadOffset"

        /**
         * Size of each tus PATCH chunk. Chosen to balance HTTP overhead against
         * the blast radius of a mid-chunk failure: 8 MiB is well above Mux's
         * efficiency floor (~256 KB) and small enough that a retry after a
         * transient network drop loses at most a few seconds of re-transfer.
         */
        private const val CHUNK_SIZE_BYTES: Long = 8L * 1024 * 1024

        private val log = LoggerFactory.getLogger(UploadToMuxExecutor::class.java)
    }
}
