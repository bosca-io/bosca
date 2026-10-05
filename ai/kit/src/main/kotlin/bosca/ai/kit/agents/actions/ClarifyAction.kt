package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState

/**
 * Asks the user for more information. When the [RouteAgent][bosca.ai.kit.agents.routing.RouteAgent]
 * is not confident enough to route — rather than assume — it picks `CLARIFY` and supplies a
 * question; this action surfaces that question as a [KitResponse.Question] so the user can answer it
 * on the next turn. No LLM call: the router already wrote the question.
 */
class ClarifyAction : KitAction() {

    override val name: String = "clarify"

    override fun precondition(state: KitState): Boolean = state.route == KitRoute.CLARIFY && !state.responded

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState =
        state.copy(responded = true, response = KitResponse.Question(state.clarification))
}
