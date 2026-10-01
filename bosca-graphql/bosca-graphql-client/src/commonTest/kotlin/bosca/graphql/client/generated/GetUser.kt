package bosca.graphql.client.generated

import bosca.graphql.client.GraphQLJson
import bosca.graphql.client.BoscaOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Hand-written fixture mirroring exactly what [bosca.graphql.codegen.KotlinClientGenerator] emits for the
 * `GetUser` query. Compiled by the normal build (with the serialization plugin), so it proves the generated
 * SHAPE compiles and its explicit serializers work; [bosca.graphql.client.RuntimeContractTest] then drives
 * it end-to-end. `KotlinClientGeneratorTest` independently asserts the generator emits this shape.
 */
@Serializable
data class GetUserData(
    val user: User?,
) {
    @Serializable
    data class User(
        val id: String,
        val name: String,
        val email: String?,
    )
}

object GetUser : BoscaOperation<GetUser.Variables, GetUserData> {
    @Serializable
    data class Variables(
        val id: String,
    )

    override val operationName: String = "GetUser"

    override val document: String =
        "query GetUser(\$id: ID!) { user(id: \$id) { id name email } }"

    override fun encodeVariables(variables: Variables): JsonObject =
        GraphQLJson.encodeToJsonElement(Variables.serializer(), variables).jsonObject

    override fun decodeData(data: JsonElement): GetUserData =
        GraphQLJson.decodeFromJsonElement(GetUserData.serializer(), data)
}
