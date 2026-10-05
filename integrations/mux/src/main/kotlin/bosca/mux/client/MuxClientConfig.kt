package bosca.mux.client

/**
 * Credentials and settings required by [MuxClient] to authenticate
 * with and submit requests to the Mux Video API.
 *
 * Integration modules that maintain their own richer configuration
 * (e.g. with queue settings or timeout policies) should map to this
 * minimal transport-level config before calling [MuxClient] methods.
 */
data class MuxClientConfig(
    /** Mux API access-token ID used for HTTP Basic authentication. */
    val tokenId: String,
    /** Mux API secret key paired with [tokenId]. */
    val tokenSecret: String,
    /** Playback policy applied to newly created assets (`"public"`, `"signed"`, or `"drm"`). */
    val playbackPolicy: String = "public",
    /** BCP 47 language code for auto-generated subtitles, or null to disable transcription. */
    val defaultSubtitleLanguage: String? = "en",
    /** Whether to request static MP4 renditions from Mux (deprecated `mp4_support` field). */
    val mp4Support: Boolean = true,
    /** Video quality tier (`"basic"`, `"plus"`, `"premium"`). Replaces deprecated [encodingTier]. */
    val videoQuality: String? = null,
    /** Deprecated encoding tier (`"smart"`, `"baseline"`, `"premium"`). Use [videoQuality] instead. */
    val encodingTier: String? = null,
    /** Maximum resolution tier (`"1080p"`, `"1440p"`, `"2160p"`). */
    val maxResolutionTier: String? = null,
    /** When `true`, assets are created in test mode and are watermarked. */
    val test: Boolean = false,
)
