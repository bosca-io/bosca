package bosca.graphql.client.generated

import bosca.graphql.client.BoscaOperation
import bosca.graphql.client.GraphQLJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class GetSpecData(
    val spec: Spec?,
) {
    @Serializable
    data class Spec(
        override val id: String,
        override val key: String,
        override val status: Status,
        override val project: Project?,
    ) : ISpecFields {
        @Serializable
        data class Status(
            override val name: String,
            override val category: String,
        ) : ISpecFields.Status

        @Serializable
        data class Project(
            override val key: String,
            override val name: String,
        ) : ISpecFields.Project
    }
}

object GetSpec : BoscaOperation<Unit, GetSpecData> {
    override val operationName: String = "GetSpec"

    override val document: String =
        "query GetSpec { spec { __typename ...SpecFields } } fragment SpecFields on Spec { id key status { name category } project { key name } }"

    override fun encodeVariables(variables: Unit): JsonObject = JsonObject(emptyMap())

    override fun decodeData(data: JsonElement): GetSpecData =
        GraphQLJson.decodeFromJsonElement(GetSpecData.serializer(), data)
}
