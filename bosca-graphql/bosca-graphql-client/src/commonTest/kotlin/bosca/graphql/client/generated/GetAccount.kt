package bosca.graphql.client.generated

import bosca.graphql.client.GraphQLJson
import bosca.graphql.client.BoscaOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Hand-written fixture mirroring what [bosca.graphql.codegen.KotlinClientGenerator] emits for a query that
 * uses **aliases** (`account:`, `contact:`) and a **named fragment** (`...UserFields`, flattened into the
 * consuming type). Compiled by the build → the flattened shape compiles; exercised by `RuntimeContractTest`.
 */
@Serializable
data class GetAccountData(
    val account: Account?,
) {
    @Serializable
    data class Account(
        val id: String,
        val name: String,
        val contact: String?,
    )
}

object GetAccount : BoscaOperation<GetAccount.Variables, GetAccountData> {
    @Serializable
    data class Variables(
        val id: String,
    )

    override val operationName: String = "GetAccount"

    override val document: String =
        "query GetAccount(\$id: ID!) { account: user(id: \$id) { ...UserFields contact: email } } " +
            "fragment UserFields on User { id name }"

    override fun encodeVariables(variables: Variables): JsonObject =
        GraphQLJson.encodeToJsonElement(Variables.serializer(), variables).jsonObject

    override fun decodeData(data: JsonElement): GetAccountData =
        GraphQLJson.decodeFromJsonElement(GetAccountData.serializer(), data)
}
