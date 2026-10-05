package bosca.server.graphql.controllers

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class UploadProgressEvent(
    @Contextual
    val metadataId: UUID,
    val bytesUploaded: Long,
    val totalBytes: Long
)

@TypeController
class UploadProgressEventController : GraphQLController<UploadProgressEvent> {

    @Field
    fun metadataId(event: UploadProgressEvent) = event.metadataId

    @Field
    fun bytesUploaded(event: UploadProgressEvent) = event.bytesUploaded

    @Field
    fun totalBytes(event: UploadProgressEvent) = event.totalBytes
}
