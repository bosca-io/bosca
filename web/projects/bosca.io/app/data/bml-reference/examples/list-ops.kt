package example

import bml.generated.ListOps
import bml.generated.ListOpsDispatcher
import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.BmlContractDispatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Implements the server half generated from the `ListOps` contract in lists.bml. */
class ListOpsImpl : ListOps {
    override suspend fun reorder(gql: GraphQLClient, listId: String, ids: List<String>) {
        gql.execute(
            query = REORDER_LIST,
            variables = buildJsonObject {
                put("listId", listId)
                put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
            },
            operationName = "ReorderList",
        )
    }

    private companion object {
        val REORDER_LIST = """
            mutation ReorderList(${ '$' }listId: ID!, ${ '$' }ids: [ID!]!) {
              reorderList(listId: ${ '$' }listId, ids: ${ '$' }ids)
            }
        """.trimIndent()
    }
}

/** Pass this list as `BmlServer(..., contracts = listContracts())`. */
fun listContracts(): List<BmlContractDispatcher> =
    listOf(ListOpsDispatcher(ListOpsImpl()))
