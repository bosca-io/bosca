package bosca.content.metadata.graphql

import bosca.content.metadata.model.MediaHls
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * GraphQL field resolver for the [MediaHls] type, exposing HLS
 * adaptive-bitrate streaming endpoints including the primary video
 * manifest and an audio-only variant.
 */
@TypeController
class MediaHlsController : GraphQLController<MediaHls> {

    @Field
    fun url(data: MediaHls) = data.url

    @Field
    fun audioOnlyUrl(data: MediaHls) = data.audioOnlyUrl
}
