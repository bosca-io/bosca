package bosca.graphql.client.generated

import bosca.graphql.client.GraphQLJson
import bosca.graphql.client.BoscaOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Hand-written fixture mirroring what [bosca.graphql.codegen.KotlinClientGenerator] emits for a query that
 * exercises breadth: an **enum** ([Status]), an **input object** ([SearchFilter], with a required
 * field + nullable-default fields), a **list** (`tags`), and a **custom scalar** (`Long`, mapped to
 * `kotlin.Long`). Compiled by the build (serialization plugin) → proves the shape compiles; exercised by
 * `RuntimeContractTest`. `KotlinClientGeneratorTest` independently asserts the generator produces this shape.
 */
@Serializable
enum class Status {
    ACTIVE,
    ARCHIVED,
}

@Serializable
data class SearchFilter(
    val term: String,
    val status: Status? = null,
    val limit: Int? = null,
)

@Serializable
data class SearchData(
    val search: Search?,
) {
    @Serializable
    data class Search(
        val id: String,
        val status: Status?,
        val tags: List<String>,
        val score: Long?,
    )
}

object Search : BoscaOperation<Search.Variables, SearchData> {
    @Serializable
    data class Variables(
        val filter: SearchFilter,
    )

    override val operationName: String = "Search"

    override val document: String =
        "query Search(\$filter: SearchFilter!) { search(filter: \$filter) { id status tags score } }"

    override fun encodeVariables(variables: Variables): JsonObject =
        GraphQLJson.encodeToJsonElement(Variables.serializer(), variables).jsonObject

    override fun decodeData(data: JsonElement): SearchData =
        GraphQLJson.decodeFromJsonElement(SearchData.serializer(), data)
}
