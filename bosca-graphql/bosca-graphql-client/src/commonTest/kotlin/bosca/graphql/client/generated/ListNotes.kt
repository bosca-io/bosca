package bosca.graphql.client.generated

import bosca.graphql.client.GraphQLJson
import bosca.graphql.client.BoscaOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class ListNotesData(
    val notes: List<Notes>,
) {
    @Serializable
    data class Notes(
        val id: String,
        val title: String,
        val visibility: Visibility?,
    )
}

object ListNotes : BoscaOperation<ListNotes.Variables, ListNotesData> {
    @Serializable
    data class Variables(
        val filter: NoteFilter,
    )

    override val operationName: String = "ListNotes"

    override val document: String =
        "query ListNotes(\$filter: NoteFilter!) { notes(filter: \$filter) { id title visibility } }"

    override fun encodeVariables(variables: Variables): JsonObject =
        GraphQLJson.encodeToJsonElement(Variables.serializer(), variables).jsonObject

    override fun decodeData(data: JsonElement): ListNotesData =
        GraphQLJson.decodeFromJsonElement(ListNotesData.serializer(), data)
}
