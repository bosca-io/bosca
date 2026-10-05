package bosca.mux.client.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for `POST /video/v1/uploads`, the Mux Direct Upload endpoint.
 *
 * A direct upload allocates a one-time signed PUT URL that Bosca streams
 * the source video bytes to. When bytes finish arriving, Mux creates the
 * asset using [newAssetSettings] and transitions the upload to the
 * `asset_created` state.
 */
@Serializable
internal data class CreateUploadRequest(
    @SerialName("cors_origin") val corsOrigin: String,
    @SerialName("new_asset_settings") val newAssetSettings: NewAssetSettings,
    val test: Boolean? = null,
    val timeout: Int? = null,
)

/**
 * Settings applied to the Mux asset that is created once a direct
 * upload finishes. This mirrors the top-level asset-create request
 * body minus the `inputs[].url` field, since the input is the bytes
 * uploaded via PUT.
 */
@Serializable
internal data class NewAssetSettings(
    @SerialName("playback_policies") val playbackPolicies: List<String>,
    @SerialName("video_quality") val videoQuality: String? = null,
    @SerialName("max_resolution_tier") val maxResolutionTier: String? = null,
    @SerialName("mp4_support") val mp4Support: String? = null,
    @SerialName("encoding_tier") val encodingTier: String? = null,
    val passthrough: String? = null,
    val meta: AssetMeta? = null,
    val inputs: List<UploadInputSettings>? = null,
    val test: Boolean? = null,
)

/**
 * Per-input configuration used within [NewAssetSettings.inputs] for a
 * direct upload. The input itself is the uploaded file, so only
 * ancillary settings such as generated subtitles are carried here.
 */
@Serializable
internal data class UploadInputSettings(
    @SerialName("generated_subtitles") val generatedSubtitles: List<GeneratedSubtitle>? = null,
)

/**
 * Customer-provided metadata attached to a Mux asset.
 *
 * Note: this metadata may be publicly visible via the video player,
 * so it must not contain PII or sensitive information.
 */
@Serializable
data class AssetMeta(
    @SerialName("external_id") val externalId: String? = null,
    val title: String? = null,
)

/**
 * Settings for requesting auto-generated subtitles from the Mux
 * transcription engine.
 */
@Serializable
internal data class GeneratedSubtitle(
    @SerialName("language_code") val languageCode: String,
    val name: String,
)

/**
 * Envelope for all Mux API responses that wrap their payload in a
 * top-level `data` field.
 */
@Serializable
internal data class MuxResponse<T>(
    val data: T,
)

/**
 * Represents a Mux Direct Upload resource as returned by
 * `POST /video/v1/uploads` and `GET /video/v1/uploads/{id}`.
 *
 * [status] progresses `waiting` → `asset_created` on success, or
 * to `errored` / `cancelled` / `timed_out` on failure. Once the
 * upload finishes and Mux begins ingesting, [assetId] is populated
 * with the identifier of the created asset.
 */
@Serializable
internal data class UploadData(
    val id: String,
    val url: String? = null,
    val status: String? = null,
    @SerialName("asset_id") val assetId: String? = null,
    val timeout: Int? = null,
    val error: UploadError? = null,
)

/**
 * Error information reported by Mux when a direct upload fails to
 * finalize into an asset.
 */
@Serializable
internal data class UploadError(
    val type: String? = null,
    val message: String? = null,
)

/**
 * Represents the full asset object returned by the Mux Video API,
 * including processing status, playback identifiers, media tracks,
 * and rendition availability.
 */
@Serializable
internal data class AssetData(
    val id: String,
    val status: String? = null,
    val duration: Double? = null,
    @SerialName("resolution_tier") val resolutionTier: String? = null,
    @SerialName("max_resolution_tier") val maxResolutionTier: String? = null,
    @SerialName("max_stored_resolution") val maxStoredResolution: String? = null,
    @SerialName("max_stored_frame_rate") val maxStoredFrameRate: Double? = null,
    @SerialName("aspect_ratio") val aspectRatio: String? = null,
    @SerialName("playback_ids") val playbackIds: List<PlaybackIdData>? = null,
    val tracks: List<TrackData>? = null,
    @SerialName("static_renditions") val staticRenditions: StaticRenditionsData? = null,
    val passthrough: String? = null,
    @SerialName("mp4_support") val mp4Support: String? = null,
    @SerialName("master_access") val masterAccess: String? = null,
    @SerialName("encoding_tier") val encodingTier: String? = null,
    @SerialName("video_quality") val videoQuality: String? = null,
)

/**
 * A playback identifier and its access policy as returned by the
 * Mux API.
 */
@Serializable
internal data class PlaybackIdData(
    val id: String? = null,
    val policy: String? = null,
)

/**
 * A single video, audio, or text track within a Mux asset.
 */
@Serializable
internal data class TrackData(
    val id: String? = null,
    val type: String? = null,
    @SerialName("language_code") val languageCode: String? = null,
    val name: String? = null,
    val status: String? = null,
    @SerialName("max_width") val maxWidth: Int? = null,
    @SerialName("max_height") val maxHeight: Int? = null,
    @SerialName("max_frame_rate") val maxFrameRate: Double? = null,
    val duration: Double? = null,
    @SerialName("max_channels") val maxChannels: Int? = null,
)

/**
 * Container for static MP4 renditions of a Mux asset.
 */
@Serializable
internal data class StaticRenditionsData(
    val status: String? = null,
    val files: List<StaticRenditionFileData>? = null,
)

/**
 * A single static MP4 rendition file within a Mux asset.
 */
@Serializable
internal data class StaticRenditionFileData(
    val name: String? = null,
    val status: String? = null,
)

