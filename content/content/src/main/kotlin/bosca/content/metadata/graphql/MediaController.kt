package bosca.content.metadata.graphql

import bosca.content.metadata.model.Media
import bosca.content.metadata.model.MediaConstants
import bosca.content.metadata.model.MediaHls
import bosca.content.metadata.model.Transcription
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * GraphQL field resolver for the [Media] type, exposing all processed
 * media properties such as streaming endpoints, thumbnails, media
 * specifications, and transcription tracks.
 */
@TypeController
class MediaController(
    private val json: Json,
) : GraphQLController<Media> {

    @Field
    fun status(data: Media) = data.status

    @Field
    fun hls(data: Media): MediaHls? = data.hlsUrl?.let {
        MediaHls(url = it, audioOnlyUrl = data.hlsAudioOnlyUrl)
    }

    @Field
    fun downloadUrl(data: Media) = data.downloadUrl

    @Field
    fun thumbnailUrl(data: Media) = data.thumbnailUrl

    @Field
    fun animatedPreviewUrl(data: Media) = data.animatedPreviewUrl

    @Field
    fun durationSeconds(data: Media) = data.durationSeconds

    @Field
    fun maxResolution(data: Media) = data.maxResolution

    @Field
    fun aspectRatio(data: Media) = data.aspectRatio

    @Field
    fun actualVideoQuality(data: Media): String? = data.actualVideoQuality

    @Field
    fun videoQuality(data: Media): String? =
        (data.providerAttributes as? JsonObject)?.get(MediaConstants.ATTR_VIDEO_QUALITY)?.jsonPrimitive?.content

    @Field
    fun maxResolutionTier(data: Media): String? =
        (data.providerAttributes as? JsonObject)?.get(MediaConstants.ATTR_MAX_RESOLUTION_TIER)?.jsonPrimitive?.content

    @Field
    fun transcriptions(data: Media): List<Transcription> {
        return try {
            json.decodeFromJsonElement<List<Transcription>>(data.transcriptions)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
