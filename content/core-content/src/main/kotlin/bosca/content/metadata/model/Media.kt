package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.OffsetDateTime

/**
 * Provider-agnostic representation of processed media associated with
 * a metadata item. Stores adaptive-bitrate streaming endpoints, download
 * links, thumbnails, media specifications, and auto-generated transcription
 * data produced by an upstream transcoding service.
 *
 * Persisted in the `metadata_media` table with one row per metadata entry.
 */
@Serializable
data class Media(
    /** Identifier of the parent metadata entry this media belongs to. */
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID = UUID.NIL,
    /** Processing status of the media: `preparing`, `ready`, or `errored`. */
    val status: String,
    /** Adaptive-bitrate HLS manifest URL for video playback. */
    @ColumnName("hls_url")
    val hlsUrl: String? = null,
    /** HLS manifest URL filtered to audio-only renditions. */
    @ColumnName("hls_audio_only_url")
    val hlsAudioOnlyUrl: String? = null,
    /** Direct file download URL (e.g. an MP4 static rendition). */
    @ColumnName("download_url")
    val downloadUrl: String? = null,
    /** Static thumbnail image URL. */
    @ColumnName("thumbnail_url")
    val thumbnailUrl: String? = null,
    /** Short animated preview URL (e.g. an animated GIF). */
    @ColumnName("animated_preview_url")
    val animatedPreviewUrl: String? = null,
    /** Total duration of the media in seconds. */
    @ColumnName("duration_seconds")
    val durationSeconds: Double? = null,
    /** Maximum resolution tier (e.g. `"1080p"`, `"720p"`). */
    @ColumnName("max_resolution")
    val maxResolution: String? = null,
    /** Display aspect ratio as a colon-separated string (e.g. `"16:9"`). */
    @ColumnName("aspect_ratio")
    val aspectRatio: String? = null,
    /** Video quality tier reported by the transcoding provider for the current asset (e.g. `"basic"`, `"plus"`, `"premium"`). */
    @ColumnName("actual_video_quality")
    val actualVideoQuality: String? = null,
    /** Subtitle and caption transcription tracks stored as a JSON array. */
    @Contextual
    val transcriptions: JsonElement = kotlinx.serialization.json.JsonArray(emptyList()),
    /** Provider-specific attributes (e.g. provider name, asset ID). */
    @ColumnName("provider_attributes")
    @Contextual
    val providerAttributes: JsonElement = kotlinx.serialization.json.JsonObject(emptyMap()),
    /** Timestamp when the media record was created. */
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    /** Timestamp when the media record was last modified. */
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * HLS (HTTP Live Streaming) endpoints for adaptive-bitrate playback,
 * including the primary video manifest and an audio-only variant.
 */
@Serializable
data class MediaHls(
    /** Adaptive-bitrate HLS manifest URL for video playback. */
    val url: String,
    /** HLS manifest URL filtered to audio-only renditions. */
    val audioOnlyUrl: String? = null,
)

/**
 * A single transcription track produced for a piece of media,
 * representing subtitles or closed-captions in a specific language.
 *
 * Each track exposes plain-text and WebVTT download URLs once the
 * upstream transcription service has finished processing it.
 */
@Serializable
data class Transcription(
    /** Provider-assigned track identifier. */
    val id: String,
    /** BCP 47 language code for the track (e.g. `"en"`, `"es"`). */
    val languageCode: String,
    /** Human-readable display name (e.g. `"English (auto)"`). */
    val name: String,
    /** Processing status of the track: `preparing`, `ready`, or `errored`. */
    val status: String,
    /** Plain-text transcript download URL, available once the track is ready. */
    val textUrl: String? = null,
    /** WebVTT subtitle file URL, available once the track is ready. */
    val vttUrl: String? = null,
)
