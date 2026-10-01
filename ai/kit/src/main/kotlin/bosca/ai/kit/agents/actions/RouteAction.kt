package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.routing.RouteAgent
import bosca.ai.kit.agents.routing.RouteRequest
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.configuration.KitJson
import kotlinx.serialization.json.Json

/**
 * Routes the request by delegating to the [RouteAgent], which decides WRITE/QUERY/CHAT (and, for the
 * WRITE path, the title + Scripture references). Routing is the entry of every request.
 * Analytics artifact persistence stays on QUERY because the analytics sub-agent owns both analysis
 * and durable analytics entities; it does not require another planner route or terminal action.
 *
 * Its [belief] predicts `responded = true`: an honest, non-arbitrary heuristic that says "routing
 * leads to an answer" — unlike committing the plan to a specific route up front. The planner then
 * re-plans from the route the agent actually chose, filling in the real work for that path.
 */
class RouteAction(
    promptExecutor: PromptExecutor,
    model: LLModel,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitAction() {

    private val router = RouteAgent(promptExecutor, model, json, sessionService)

    override val name: String = "route"

    override fun precondition(state: KitState): Boolean = state.route == null

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        val decision = router.run(RouteRequest(state.request.message), sessionId)
        return state.copy(
            route = decision.route,
            title = decision.title,
            references = decision.references,
            translation = decision.translation,
            clarification = decision.question,
        )
    }
}
