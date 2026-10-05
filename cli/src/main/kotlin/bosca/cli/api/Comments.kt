package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.CommentStatus
import bosca.graphql.gen.MergeCommentSystemAttributes
import bosca.graphql.gen.SetCommentStatus
import bosca.graphql.gen.SetCommentSystemAttributes
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class Comments(network: NetworkClient) : Api(network) {

    suspend fun setStatus(metadataId: Uuid, metadataVersion: Int, commentId: Long, status: CommentStatus) {
        network.boscaGraphql.execute(
            SetCommentStatus,
            SetCommentStatus.Variables(metadataId, metadataVersion, commentId, status),
        )
    }

    suspend fun setSystemAttributes(metadataId: Uuid, version: Int, id: Long, attributes: JsonElement?) {
        network.boscaGraphql.execute(
            SetCommentSystemAttributes,
            SetCommentSystemAttributes.Variables(metadataId, version, id, attributes ?: JsonObject(emptyMap())),
        )
    }

    suspend fun mergeSystemAttributes(metadataId: Uuid, version: Int, id: Long, attributes: JsonElement?) {
        network.boscaGraphql.execute(
            MergeCommentSystemAttributes,
            MergeCommentSystemAttributes.Variables(metadataId, version, id, attributes ?: JsonObject(emptyMap())),
        )
    }
}
