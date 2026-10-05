package bosca.graphql.client.generated

import bosca.graphql.client.GraphQLJson
import bosca.graphql.client.BoscaOperation
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class GetNodeData(
    @Serializable(with = GetNodeDataNodeSerializer::class) val node: Node?,
) {
    @Serializable(with = GetNodeDataNodeSerializer::class)
    sealed interface Node {
        val id: String

        @Serializable
        data class User(
            override val id: String,
            val name: String,
        ) : Node

        @Serializable
        data class Post(
            override val id: String,
            val title: String,
        ) : Node

        @Serializable
        data class Other(
            override val id: String,
        ) : Node
    }
}

object GetNodeDataNodeSerializer : KSerializer<GetNodeData.Node> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("GetNodeData.Node")

    override fun deserialize(decoder: Decoder): GetNodeData.Node {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return when (element.jsonObject["__typename"]?.jsonPrimitive?.content) {
            "User" -> GraphQLJson.decodeFromJsonElement(GetNodeData.Node.User.serializer(), element)
            "Post" -> GraphQLJson.decodeFromJsonElement(GetNodeData.Node.Post.serializer(), element)
            else -> GraphQLJson.decodeFromJsonElement(GetNodeData.Node.Other.serializer(), element)
        }
    }

    override fun serialize(encoder: Encoder, value: GetNodeData.Node): Unit =
        error("GraphQL response type 'GetNodeData.Node' is not serializable")
}

object GetNode : BoscaOperation<Unit, GetNodeData> {
    override val operationName: String = "GetNode"

    override val document: String =
        "query GetNode { node { __typename id ... on User { name } ... on Post { title } } }"

    override fun encodeVariables(variables: Unit): JsonObject = JsonObject(emptyMap())

    override fun decodeData(data: JsonElement): GetNodeData =
        GraphQLJson.decodeFromJsonElement(GetNodeData.serializer(), data)
}
