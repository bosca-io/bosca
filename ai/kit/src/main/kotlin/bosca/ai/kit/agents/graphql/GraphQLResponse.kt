package bosca.ai.kit.agents.graphql

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The GraphQL sub-agent's **rich** result: a human-facing [message], the actual [data] the operation
 * returned (the GraphQL response JSON, captured **verbatim** from the execution — never re-typed by the
 * model), and a reference to *what that data is* — its GraphQL [type] name and the [sdl] definition of
 * that type. Assembled by the agent's strategy graph (the execute node produces the data; the model
 * supplies the summary and type via a [GraphQLPlan]; the SDL is resolved from the live schema).
 */
@Serializable
data class GraphQLResponse(
    val message: String,
    val data: JsonElement,
    val type: String,
    val sdl: String,
)
