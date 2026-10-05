package bosca.content.metadata.graphql

import bosca.content.metadata.model.Transcription
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * GraphQL field resolver for the [Transcription] type, exposing
 * subtitle and caption track properties including language,
 * processing status, and download URLs in plain-text and WebVTT
 * formats.
 */
@TypeController
class TranscriptionController : GraphQLController<Transcription> {

    @Field
    fun id(data: Transcription) = data.id

    @Field
    fun languageCode(data: Transcription) = data.languageCode

    @Field
    fun name(data: Transcription) = data.name

    @Field
    fun status(data: Transcription) = data.status

    @Field
    fun textUrl(data: Transcription) = data.textUrl

    @Field
    fun vttUrl(data: Transcription) = data.vttUrl
}
