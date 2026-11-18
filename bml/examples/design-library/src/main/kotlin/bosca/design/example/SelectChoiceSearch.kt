package bosca.design.example

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.BmlContractDispatcher
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

/** Isolated caller-owned choice service over BML's normal same-origin contract route. */
class SelectChoiceSearch : BmlContractDispatcher {
    override val name: String = "SelectChoices"
    override val methods: List<String> = listOf("search")

    override suspend fun dispatch(gql: GraphQLClient?, method: String, argsJson: String): String {
        require(method == "search")
        val args = Json.decodeFromString(ListSerializer(JsonPrimitive.serializer()), argsJson)
        require(args.size == 2)
        val query = args[0].content
        val offset = args[1].long
        require(offset in 0..100)
        if (query == "slow" || query == "latefail") delay(700)
        check(query != "fail" && query != "latefail") { "Fixture choice search rejected" }
        val count = if (query == "long") 30 else 6
        val choices = if (query == "none") emptyList() else (offset.toInt() until minOf(offset.toInt() + 3, count)).map { index ->
            mapOf(
                "value" to "$query-$index",
                "label" to if (query == "long") "Long choice $index. ".repeat(14) else "$query choice $index",
                "disabled" to (index == 1).toString(),
            )
        }
        return Json.encodeToString(ListSerializer(MapSerializer(String.serializer(), String.serializer())), choices)
    }
}
