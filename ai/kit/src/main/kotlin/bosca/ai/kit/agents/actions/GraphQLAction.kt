package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.graphql.GraphQLAgent
import bosca.ai.kit.agents.graphql.GraphQLRequest
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.configuration.KitJson
import bosca.graphql.GraphQLService

/**
 * Handles a request that's best served by introspecting/managing Bosca through its GraphQL API. This
 * action **owns** the [GraphQLAgent] sub-agent (which discovers the schema, plans an operation, and
 * executes it) and folds its rich [bosca.ai.kit.agents.graphql.GraphQLResponse] — the summary, the
 * exact returned data, and what it is (type + SDL) — into a [KitResponse.GraphQL].
 */
class GraphQLAction(
    promptExecutor: PromptExecutor,
    model: LLModel,
    graphQLService: GraphQLService,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitAction() {

    private val graphQLAgent = GraphQLAgent(promptExecutor, model, graphQLService, json, sessionService)

    override val name: String = "use_graphql"

    override fun precondition(state: KitState): Boolean = state.route == KitRoute.GRAPHQL && !state.responded

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        val result = graphQLAgent.run(GraphQLRequest(message = state.request.message), sessionId)
        return state.copy(
            responded = true,
            response = KitResponse.GraphQL(
                message = result.message,
                data = result.data,
                type = result.type,
                sdl = result.sdl,
            ),
        )
    }
}
