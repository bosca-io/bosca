package bosca.content.metadata.model

import kotlinx.serialization.Serializable

/**
 * Per-item overrides for media processing settings that take precedence
 * over the global provider configuration. Stored in the media record's
 * `providerAttributes` so the upload job can apply them at transcoding time.
 */
@Serializable
data class MediaProcessingOptions(
    /** Video quality tier override: `"basic"`, `"plus"`, or `"premium"`. */
    val videoQuality: String? = null,
    /** Maximum resolution tier override: `"1080p"`, `"1440p"`, or `"2160p"`. */
    val maxResolutionTier: String? = null,
    /** Time offset in seconds for the thumbnail image extracted from the video. */
    val thumbnailTimeOffsetSeconds: Double? = null,
) {
    init {
        videoQuality?.let {
            require(it in VALID_VIDEO_QUALITIES) { "videoQuality must be one of $VALID_VIDEO_QUALITIES, got: $it" }
        }
        maxResolutionTier?.let {
            require(it in VALID_RESOLUTION_TIERS) { "maxResolutionTier must be one of $VALID_RESOLUTION_TIERS, got: $it" }
        }
    }

    companion object {
        private val VALID_VIDEO_QUALITIES = setOf("basic", "plus", "premium")
        private val VALID_RESOLUTION_TIERS = setOf("1080p", "1440p", "2160p")
    }
}
