package bosca.graphql.client.generated

import bosca.graphql.client.GraphQLJson
import bosca.graphql.client.BoscaOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class GetNoteData(
    val note: Note?,
) {
    @Serializable
    data class Note(
        val id: String,
        val visibility: Visibility?,
    )
}

object GetNote : BoscaOperation<GetNote.Variables, GetNoteData> {
    @Serializable
    data class Variables(
        val id: String,
    )

    override val operationName: String = "GetNote"

    override val document: String =
        "query GetNote(\$id: ID!) { note(id: \$id) { id visibility } }"

    override fun encodeVariables(variables: Variables): JsonObject =
        GraphQLJson.encodeToJsonElement(Variables.serializer(), variables).jsonObject

    override fun decodeData(data: JsonElement): GetNoteData =
        GraphQLJson.decodeFromJsonElement(GetNoteData.serializer(), data)
}
