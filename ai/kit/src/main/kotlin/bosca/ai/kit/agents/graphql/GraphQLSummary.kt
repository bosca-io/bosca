package bosca.ai.kit.agents.graphql

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * The model's structured answer after it has run the operation and **seen the result**: a human-facing
 * [message] that answers the user's request from the actual data, and the GraphQL [type] of that data.
 * The exact returned data isn't repeated here — the agent reads it verbatim from the `graphql_query`
 * tool result and attaches it (with the type's SDL) to the rich [GraphQLResponse].
 */
@Serializable
@LLMDescription("An answer to the user's request, grounded in the operation's result, with the result's GraphQL type.")
data class GraphQLSummary(
    @property:LLMDescription("A direct, concise answer to the user's request, grounded in what the operation actually returned — if it's a list, list the items (names/ids). Mention any errors.")
    val message: String,
    @property:LLMDescription("The GraphQL type name of the data the operation returned — what it IS — e.g. 'Metadata', '[Collection]', 'Boolean'. Empty for an answer with no data.")
    val type: String = "",
)
