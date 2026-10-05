package bosca.mux.client

/**
 * Represents the processing status of a Mux asset as reported by the
 * Mux Video API.  The [status] field mirrors the API value (`preparing`,
 * `ready`, `errored`), while [playbackIds] contains the playback
 * identifiers assigned once the asset reaches the `ready` state.
 */
data class MuxAssetStatus(
    /** The current lifecycle status of the asset (`preparing`, `ready`, `errored`). */
    val status: String,
    /** Playback identifiers assigned to the asset, empty when still preparing. */
    val playbackIds: List<MuxPlaybackId>,
    /** The Mux-assigned asset identifier. */
    val assetId: String,
    /** Total duration of the asset in seconds, null while still processing. */
    val duration: Double? = null,
    /** Resolution tier the asset was ingested at (e.g. `"1080p"`, `"1440p"`, `"2160p"`). */
    val resolutionTier: String? = null,
    /** Deprecated maximum stored resolution label (e.g. `"HD"`, `"FHD"`, `"UHD"`). */
    val maxStoredResolution: String? = null,
    /** Display aspect ratio as a colon-separated string (e.g. `"16:9"`). */
    val aspectRatio: String? = null,
    /** Text, audio, and video tracks associated with the asset. */
    val tracks: List<MuxTrackInfo> = emptyList(),
    /** Static MP4 renditions available for download. */
    val staticRenditions: List<MuxStaticRendition> = emptyList(),
    /** Encoding tier used for this asset (e.g. `"smart"`, `"baseline"`). */
    val encodingTier: String? = null,
    /** Perceptual video quality level (e.g. `"plus"`, `"premium"`). */
    val videoQuality: String? = null,
    /** Maximum stored frame rate of the asset. */
    val maxStoredFrameRate: Double? = null,
)

/**
 * A single playback identifier associated with a Mux asset, combining
 * the [id] used to construct playback URLs with its [policy] (e.g. `public`
 * or `signed`).
 */
data class MuxPlaybackId(
    /** The playback identifier string used in HLS/DASH manifest URLs. */
    val id: String,
    /** The playback access policy (`public` or `signed`). */
    val policy: String,
)

/**
 * Information about a single track (video, audio, or text) within a
 * Mux asset, used to identify subtitle and caption tracks for
 * transcript retrieval.
 */
data class MuxTrackInfo(
    /** The Mux-assigned track identifier. */
    val id: String,
    /** Track type: `"video"`, `"audio"`, or `"text"`. */
    val type: String,
    /** BCP 47 language code for text tracks, null for video/audio. */
    val languageCode: String?,
    /** Human-readable display name for the track. */
    val name: String?,
    /** Current processing status of the track (`preparing`, `ready`, `errored`). */
    val status: String,
    /** Maximum pixel width of the video track, null for audio/text. */
    val maxWidth: Int? = null,
    /** Maximum pixel height of the video track, null for audio/text. */
    val maxHeight: Int? = null,
    /** Maximum frame rate of the video track, null for audio/text. */
    val maxFrameRate: Double? = null,
    /** Duration of the track in seconds. */
    val duration: Double? = null,
    /** Maximum audio channel count, null for video/text. */
    val maxChannels: Int? = null,
)

/**
 * A static MP4 rendition of a Mux asset available for direct download.
 */
data class MuxStaticRendition(
    /** The rendition file name (e.g. `"high.mp4"`, `"medium.mp4"`). */
    val name: String,
    /** Current processing status of the rendition (`preparing`, `ready`, `errored`). */
    val status: String,
)

/**
 * Result of creating a Mux Direct Upload. The [url] is a one-time
 * signed PUT endpoint that the caller streams the source media bytes
 * to, while [id] identifies the upload for subsequent status polls.
 */
data class MuxDirectUpload(
    /** The Mux-assigned direct upload identifier. */
    val id: String,
    /** The one-time signed PUT URL to which the source bytes must be streamed. */
    val url: String,
)

/**
 * Snapshot of a Mux Direct Upload's lifecycle state as returned by
 * `GET /video/v1/uploads/{id}`.
 *
 * [status] follows Mux's state machine: `waiting` while the PUT has not
 * yet completed, `asset_created` once Mux has accepted the bytes and
 * created the downstream asset, and `errored` / `cancelled` / `timed_out`
 * on failure. [assetId] is populated only after the upload reaches
 * `asset_created`.
 */
data class MuxUploadStatus(
    /** Current lifecycle status (`waiting`, `asset_created`, `errored`, `cancelled`, `timed_out`). */
    val status: String,
    /** Identifier of the asset created from this upload, null until `asset_created`. */
    val assetId: String?,
    /** Optional human-readable error message reported when the upload fails. */
    val errorMessage: String?,
)
