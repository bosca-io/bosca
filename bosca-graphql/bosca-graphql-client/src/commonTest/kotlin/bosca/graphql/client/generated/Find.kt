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
data class FindData(
    @Serializable(with = FindDataResultSerializer::class) val result: Result?,
) {
    @Serializable(with = FindDataResultSerializer::class)
    sealed interface Result {
        @Serializable
        data class User(
            val id: String,
            val name: String,
        ) : Result

        @Serializable
        data class Post(
            val id: String,
            val title: String,
        ) : Result

        @Serializable
        object Other : Result
    }
}

object FindDataResultSerializer : KSerializer<FindData.Result> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FindData.Result")

    override fun deserialize(decoder: Decoder): FindData.Result {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return when (element.jsonObject["__typename"]?.jsonPrimitive?.content) {
            "User" -> GraphQLJson.decodeFromJsonElement(FindData.Result.User.serializer(), element)
            "Post" -> GraphQLJson.decodeFromJsonElement(FindData.Result.Post.serializer(), element)
            else -> GraphQLJson.decodeFromJsonElement(FindData.Result.Other.serializer(), element)
        }
    }

    override fun serialize(encoder: Encoder, value: FindData.Result): Unit =
        error("GraphQL response type 'FindData.Result' is not serializable")
}

object Find : BoscaOperation<Unit, FindData> {
    override val operationName: String = "Find"

    override val document: String =
        "query Find { result { __typename ... on User { id name } ... on Post { id title } } }"

    override fun encodeVariables(variables: Unit): JsonObject = JsonObject(emptyMap())

    override fun decodeData(data: JsonElement): FindData =
        GraphQLJson.decodeFromJsonElement(FindData.serializer(), data)
}
