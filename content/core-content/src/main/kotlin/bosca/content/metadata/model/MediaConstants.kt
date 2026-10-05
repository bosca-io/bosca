package bosca.content.metadata.model

/**
 * Well-known constants for media provider attributes stored on
 * metadata media entries. These values are shared between the content
 * module (which reads the data) and integration modules (which populate it).
 */
object MediaConstants {
    /** Provider attribute key for the provider name (e.g. `"mux"`). */
    const val ATTR_PROVIDER = "provider"
    /** Provider attribute key for the external asset identifier. */
    const val ATTR_ASSET_ID = "assetId"
    /** Provider attribute key for the external direct-upload identifier, present while bytes are in flight to the provider and before an asset id is assigned. */
    const val ATTR_UPLOAD_ID = "uploadId"
    /** Provider attribute key for the epoch millis when processing started. */
    const val ATTR_CREATED_AT_MS = "createdAtMs"
    /** Provider name used for Mux-hosted media assets. */
    const val PROVIDER_MUX = "mux"
    /** Provider attribute key for per-item video quality tier override. */
    const val ATTR_VIDEO_QUALITY = "videoQuality"
    /** Provider attribute key for per-item max resolution tier override. */
    const val ATTR_MAX_RESOLUTION_TIER = "maxResolutionTier"
    /** Provider attribute key for thumbnail time offset in seconds. */
    const val ATTR_THUMBNAIL_TIME_OFFSET = "thumbnailTimeOffset"
    /** Provider attribute key for the playback identifier used in stream and image URLs. */
    const val ATTR_PLAYBACK_ID = "playbackId"
}
