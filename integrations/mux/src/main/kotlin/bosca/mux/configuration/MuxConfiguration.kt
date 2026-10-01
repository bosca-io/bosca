package bosca.mux.configuration

import kotlinx.serialization.Serializable

/**
 * Configuration for the Mux video integration, stored in the platform's
 * configuration service under the `"mux"` key.
 *
 * Holds the credentials required to authenticate with the Mux Data and
 * Video APIs, as well as optional policy settings that control how
 * assets are created and served.
 */
@Serializable
data class MuxConfiguration(
    /** Mux API access-token ID used for authenticating requests. */
    val tokenId: String = "",
    /** Mux API secret key paired with [tokenId]. */
    val tokenSecret: String = "",
    /**
     * The Mux playback policy applied to newly created assets.
     * Defaults to `"public"`. Use `"signed"` for token-gated playback.
     */
    val playbackPolicy: String = "public",
    /**
     * Maximum number of seconds to wait for Mux to finish processing
     * an asset before the check-ready job gives up.
     */
    val maxWaitSeconds: Long = 3600,
    /**
     * BCP 47 language code for auto-generated subtitles (e.g. `"en"`).
     * Set to `null` to disable automatic transcription.
     */
    val defaultSubtitleLanguage: String? = "en",
    /**
     * Whether to request static MP4 renditions from Mux, enabling
     * direct file download in addition to HLS streaming.
     * Note: this uses the deprecated `mp4_support` API field.
     */
    val mp4Support: Boolean = true,
    /** Base URL for the Mux streaming CDN (HLS, downloads, subtitles). */
    val streamBaseUrl: String = "https://stream.mux.com",
    /** Base URL for the Mux image CDN (thumbnails, animated previews). */
    val imageBaseUrl: String = "https://image.mux.com",
    /**
     * Video quality tier for transcoding. Controls cost, quality, and
     * available platform features. Values: `"basic"`, `"plus"`, `"premium"`.
     * Replaces the deprecated `encoding_tier` field.
     */
    val videoQuality: String? = null,
    /**
     * Deprecated encoding tier. Use [videoQuality] instead.
     * Values: `"smart"`, `"baseline"`, `"premium"`.
     * Sent only when [videoQuality] is not set.
     */
    val encodingTier: String? = null,
    /**
     * Maximum resolution tier for encoding and streaming.
     * Values: `"1080p"`, `"1440p"`, `"2160p"`.
     * Defaults to `"1080p"` on the Mux side when not set.
     */
    val maxResolutionTier: String? = null,
    /**
     * When `true`, assets are created in test mode and are watermarked,
     * limited to 10 seconds, and deleted after 24 hours.
     */
    val test: Boolean = false,
)
