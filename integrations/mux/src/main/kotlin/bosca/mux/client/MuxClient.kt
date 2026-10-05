package bosca.mux.client

import bosca.mux.client.models.AssetData
import bosca.mux.client.models.AssetMeta
import bosca.mux.client.models.CreateUploadRequest
import bosca.mux.client.models.GeneratedSubtitle
import bosca.mux.client.models.MuxResponse
import bosca.mux.client.models.NewAssetSettings

import bosca.mux.client.models.UploadData
import bosca.mux.client.models.UploadInputSettings
import bosca.server.http.await
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * Client for the Mux Video REST API backed by OkHttp.
 *
 * All calls are suspend-friendly — the non-blocking [okhttp3.Call.await]
 * extension is used instead of blocking [okhttp3.Call.execute].
 * Authentication is handled via HTTP Basic auth using the token-id /
 * token-secret pair from [MuxClientConfig], except for the PUT to
 * Mux's signed upload endpoint, which is pre-authenticated via the
 * URL itself.
 *
 * @param httpClient optional [OkHttpClient] instance; tests can inject a
 *   client configured for [mockwebserver3.MockWebServer]
 * @param baseUrl optional override for the Mux API base URL; useful for
 *   tests that point at a [mockwebserver3.MockWebServer]
 */
class MuxClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json,
) {

    /**
     * Creates a Mux Direct Upload and returns the one-time signed PUT
     * URL that the caller must stream source media bytes to.
     *
     * Bosca uses this flow instead of the URL-based asset-create flow
     * because Mux's ability to pull media from Bosca's object store has
     * proven unreliable; pushing bytes keeps the data path under Bosca's
     * control and lets the retry policy handle transient failures.
     *
     * @param config      credentials and policy settings for the Mux account
     * @param passthrough opaque identifier echoed back on webhook events
     * @param meta        public metadata attached to the resulting asset
     * @return the upload id and signed PUT URL
     */
    suspend fun createDirectUpload(
        config: MuxClientConfig,
        passthrough: String? = null,
        meta: AssetMeta? = null,
        videoQualityOverride: String? = null,
        maxResolutionTierOverride: String? = null,
    ): MuxDirectUpload {
        val subtitles = config.defaultSubtitleLanguage?.let { lang ->
            listOf(GeneratedSubtitle(languageCode = lang, name = "$lang (auto)"))
        }

        val effectiveVideoQuality = videoQualityOverride ?: config.videoQuality
        val effectiveMaxResolutionTier = maxResolutionTierOverride ?: config.maxResolutionTier

        val newAssetSettings = NewAssetSettings(
            playbackPolicies = listOf(config.playbackPolicy),
            videoQuality = effectiveVideoQuality,
            encodingTier = if (effectiveVideoQuality == null) config.encodingTier else null,
            maxResolutionTier = effectiveMaxResolutionTier,
            mp4Support = if (config.mp4Support) MP4_SUPPORT_VALUE else null,
            passthrough = passthrough,
            meta = meta,
            inputs = subtitles?.let { listOf(UploadInputSettings(generatedSubtitles = it)) },
            test = if (config.test) true else null,
        )

        val request = CreateUploadRequest(
            corsOrigin = "*",
            newAssetSettings = newAssetSettings,
            test = if (config.test) true else null,
        )

        val body = json.encodeToString(CreateUploadRequest.serializer(), request)

        val httpRequest = Request.Builder()
            .url("$baseUrl/video/v1/uploads")
            .addHeader("Authorization", Credentials.basic(config.tokenId, config.tokenSecret))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(httpRequest).await()
        response.use { resp ->
            val responseBody = resp.body.string()
            if (!resp.isSuccessful) {
                throw MuxApiException(resp.code, "Failed to create Mux direct upload: HTTP ${resp.code} - $responseBody")
            }
            val muxResponse = json.decodeFromString<MuxResponse<UploadData>>(responseBody)
            val data = muxResponse.data
            val url = data.url
                ?: throw MuxApiException(resp.code, "Mux direct upload response missing url: $responseBody")
            return MuxDirectUpload(id = data.id, url = url)
        }
    }

    /**
     * Retrieves the current state of a Mux Direct Upload, allowing the
     * caller to observe the transition from `waiting` to `asset_created`
     * (or to a failure state) and to discover the id of the asset that
     * was created once ingestion begins.
     *
     * @param config   credentials for the Mux account
     * @param uploadId the Mux direct upload identifier to query
     */
    suspend fun getUpload(config: MuxClientConfig, uploadId: String): MuxUploadStatus {
        requireSafePathSegment(uploadId, "upload ID")
        val httpRequest = Request.Builder()
            .url("$baseUrl/video/v1/uploads/$uploadId")
            .addHeader("Authorization", Credentials.basic(config.tokenId, config.tokenSecret))
            .get()
            .build()

        val response = httpClient.newCall(httpRequest).await()
        response.use { resp ->
            val responseBody = resp.body.string()
            if (!resp.isSuccessful) {
                throw MuxApiException(resp.code, "Failed to get Mux upload status: HTTP ${resp.code} - $responseBody")
            }
            val muxResponse = json.decodeFromString<MuxResponse<UploadData>>(responseBody)
            val data = muxResponse.data
            return MuxUploadStatus(
                status = data.status ?: "unknown",
                assetId = data.assetId,
                errorMessage = data.error?.message,
            )
        }
    }

    /**
     * Queries the GCS resumable upload session at [uploadUrl] for the
     * number of bytes already received, returning the next byte offset
     * Bosca should resume from.
     *
     * Mux's [createDirectUpload] returns a Google Cloud Storage signed
     * resumable session URL — *not* a tus endpoint — so resume queries
     * use the GCS protocol: a `PUT` with an empty body and a
     * `Content-Range: bytes * /<total>` header. GCS replies with one of:
     *
     * - `200` / `201` — upload already complete; returns [totalLength].
     * - `308 Resume Incomplete` — partial; the `Range: bytes=0-<lastByte>`
     *   header (if present) indicates how many bytes are committed.
     *   Absence of `Range` means zero bytes received.
     *
     * @param uploadUrl   the signed upload URL returned by [createDirectUpload]
     * @param totalLength total content length of the source media in bytes
     * @return the next byte offset to upload; equals [totalLength] when the
     *         server already has the entire payload
     */
    suspend fun getResumableUploadOffset(uploadUrl: String, totalLength: Long): Long {
        val body = ByteArray(0).toRequestBody(null, 0, 0)
        val httpRequest = Request.Builder()
            .url(uploadUrl)
            .put(body)
            .addHeader(CONTENT_RANGE_HEADER, "bytes */$totalLength")
            .build()

        val response = httpClient.newCall(httpRequest).await()
        response.use { resp ->
            if (resp.code == 200 || resp.code == 201) {
                return totalLength
            }
            if (resp.code != 308) {
                val responseBody = resp.body.string()
                throw MuxApiException(
                    resp.code,
                    "Failed to query GCS resumable upload status: HTTP ${resp.code} - $responseBody"
                )
            }
            val rangeHeader = resp.headers[RANGE_HEADER] ?: return 0L
            // GCS Range header format: "bytes=0-<lastByte>"
            val lastByte = rangeHeader.removePrefix("bytes=").substringAfter('-').toLongOrNull()
                ?: throw MuxApiException(
                    resp.code,
                    "GCS resumable status returned malformed Range header: $rangeHeader"
                )
            return lastByte + 1
        }
    }

    /**
     * Sends a single chunk of bytes to a GCS resumable upload session via
     * the GCS resumable upload protocol. Bytes are streamed from
     * [inputStream] via Okio without being buffered in memory.
     *
     * Per GCS rules, every chunk except the final one must be a multiple
     * of 256 KiB; the caller is responsible for sizing chunks accordingly.
     *
     * @param uploadUrl     the signed upload URL returned by [createDirectUpload]
     * @param offset        the byte offset at which this chunk begins; must match
     *                      the server's currently-committed length, or GCS will
     *                      reject the PUT
     * @param chunkLength   number of bytes being sent in this PUT; the stream
     *                      must deliver exactly this many bytes
     * @param totalLength   total content length of the source media in bytes
     * @param inputStream   source of the chunk's bytes; caller retains ownership
     *                      and is responsible for closing it
     * @return the new server-side committed offset after the PUT succeeds, or
     *         [totalLength] when the chunk completes the upload
     */
    suspend fun putResumableUploadChunk(
        uploadUrl: String,
        offset: Long,
        chunkLength: Long,
        totalLength: Long,
        inputStream: InputStream,
    ): Long {
        val body = object : RequestBody() {
            override fun contentType() = OCTET_STREAM_MEDIA_TYPE
            override fun contentLength() = chunkLength
            override fun writeTo(sink: BufferedSink) {
                inputStream.source().use { source ->
                    sink.writeAll(source)
                }
            }
        }

        val end = offset + chunkLength - 1
        val httpRequest = Request.Builder()
            .url(uploadUrl)
            .put(body)
            .addHeader(CONTENT_RANGE_HEADER, "bytes $offset-$end/$totalLength")
            .build()

        val response = httpClient.newCall(httpRequest).await()
        response.use { resp ->
            if (resp.code == 200 || resp.code == 201) {
                // Final chunk acknowledged.
                return totalLength
            }
            if (resp.code != 308) {
                val responseBody = resp.body.string()
                throw MuxApiException(
                    resp.code,
                    "Failed to PUT GCS resumable chunk at offset $offset: HTTP ${resp.code} - $responseBody"
                )
            }
            val rangeHeader = resp.headers[RANGE_HEADER] ?: return 0L
            val lastByte = rangeHeader.removePrefix("bytes=").substringAfter('-').toLongOrNull()
                ?: throw MuxApiException(
                    resp.code,
                    "GCS resumable PUT returned malformed Range header: $rangeHeader"
                )
            return lastByte + 1
        }
    }

    /**
     * Retrieves the current processing status and playback information
     * for an existing Mux asset, including duration, resolution, tracks,
     * and static rendition availability.
     *
     * @param config  credentials for the Mux account
     * @param assetId the Mux asset identifier to query
     * @return a [MuxAssetStatus] snapshot of the asset's current state
     */
    suspend fun getAssetStatus(config: MuxClientConfig, assetId: String): MuxAssetStatus {
        requireSafePathSegment(assetId, "asset ID")
        val httpRequest = Request.Builder()
            .url("$baseUrl/video/v1/assets/$assetId")
            .addHeader("Authorization", Credentials.basic(config.tokenId, config.tokenSecret))
            .get()
            .build()

        val response = httpClient.newCall(httpRequest).await()
        response.use { resp ->
            val responseBody = resp.body.string()
            if (!resp.isSuccessful) {
                throw MuxApiException(resp.code, "Failed to get Mux asset status: HTTP ${resp.code} - $responseBody")
            }
            val muxResponse = json.decodeFromString<MuxResponse<AssetData>>(responseBody)
            return muxResponse.data.toAssetStatus(assetId)
        }
    }

    /**
     * Permanently deletes a Mux asset and all its associated renditions.
     *
     * @param config  credentials for the Mux account
     * @param assetId the Mux asset identifier to delete
     */
    suspend fun deleteAsset(config: MuxClientConfig, assetId: String) {
        requireSafePathSegment(assetId, "asset ID")
        val httpRequest = Request.Builder()
            .url("$baseUrl/video/v1/assets/$assetId")
            .addHeader("Authorization", Credentials.basic(config.tokenId, config.tokenSecret))
            .delete()
            .build()

        val response = httpClient.newCall(httpRequest).await()
        response.use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 404) {
                    log.warn("Mux asset {} was already deleted or not found", assetId)
                } else {
                    val responseBody = resp.body.string()
                    throw MuxApiException(resp.code, "Failed to delete Mux asset: HTTP ${resp.code} - $responseBody")
                }
            }
        }
    }

    private fun AssetData.toAssetStatus(assetId: String) = MuxAssetStatus(
        status = status ?: "unknown",
        playbackIds = playbackIds?.map { pid ->
            MuxPlaybackId(id = pid.id ?: "", policy = pid.policy ?: "")
        } ?: emptyList(),
        assetId = assetId,
        duration = duration,
        resolutionTier = resolutionTier,
        maxStoredResolution = maxStoredResolution,
        aspectRatio = aspectRatio,
        tracks = tracks?.map { track ->
            MuxTrackInfo(
                id = track.id ?: "",
                type = track.type ?: "unknown",
                languageCode = track.languageCode,
                name = track.name,
                status = track.status ?: "unknown",
                maxWidth = track.maxWidth,
                maxHeight = track.maxHeight,
                maxFrameRate = track.maxFrameRate,
                duration = track.duration,
                maxChannels = track.maxChannels,
            )
        } ?: emptyList(),
        staticRenditions = staticRenditions?.files?.map { file ->
            MuxStaticRendition(
                name = file.name ?: "unknown",
                status = file.status ?: "unknown",
            )
        } ?: emptyList(),
        encodingTier = encodingTier,
        videoQuality = videoQuality,
        maxStoredFrameRate = maxStoredFrameRate,
    )

    private fun requireSafePathSegment(value: String, name: String) {
        require(value.none { it == '/' || it == '?' || it == '#' || it == '\\' }) {
            "$name contains invalid URL path characters: $value"
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.mux.com"
        private const val MP4_SUPPORT_VALUE = "capped-1080p"
        private const val CONTENT_RANGE_HEADER = "Content-Range"
        private const val RANGE_HEADER = "Range"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val log = LoggerFactory.getLogger(MuxClient::class.java)
    }
}
