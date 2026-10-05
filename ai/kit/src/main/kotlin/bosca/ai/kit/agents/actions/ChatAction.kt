package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.chat.ChatAgent
import bosca.ai.kit.agents.chat.ChatRequest
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.configuration.KitJson
import kotlinx.serialization.json.Json

/**
 * The conversational fallback: when the request isn't a document or a data question, Kit just replies.
 * This action **owns** the [ChatAgent] (which holds Kit's voice + history compression) and folds its
 * reply into the shared state.
 */
class ChatAction(
    promptExecutor: PromptExecutor,
    model: LLModel,
    json: KitJson,
    sessionService: KitSessionService,
) : KitAction() {

    private val chat = ChatAgent(promptExecutor, model, json, sessionService)

    override val name: String = "chat"

    override fun precondition(state: KitState): Boolean = state.route == KitRoute.CHAT && !state.responded

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        val reply = chat.run(ChatRequest(state.request.message), sessionId)
        return when (reply.handOffTo) {
            // Chat isn't the agent for this — hand it up by re-routing; the planner re-plans to the
            // matching action. `responded` stays false, and our precondition (route == CHAT) won't re-fire.
            KitRoute.SCRIPTURE -> state.copy(
                route = reply.handOffTo,
                references = reply.references,
                translation = reply.translation,
            )
            KitRoute.WRITE, KitRoute.QUERY, KitRoute.GRAPHQL -> state.copy(route = reply.handOffTo)
            else -> state.copy(responded = true, response = KitResponse.Text(reply.text))
        }
    }
}
