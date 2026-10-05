package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.script.ScriptAgent
import bosca.ai.kit.agents.script.ScriptRequest
import bosca.ai.kit.agents.script.ScriptServices
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.configuration.KitJson

/**
 * Handles a scripting request. This action **owns** the [ScriptAgent] sub-agent (which runs its own
 * tool loop over the script + trigger-binding tools) and folds its structured summary into a
 * [KitResponse.Text] so Kit reports what it did.
 */
class ScriptAction(
    promptExecutor: PromptExecutor,
    model: LLModel,
    scripts: ScriptServices,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitAction() {

    private val scriptAgent = ScriptAgent(promptExecutor, model, scripts, json, sessionService)

    override val name: String = "manage_scripts"

    override fun precondition(state: KitState): Boolean = state.route == KitRoute.SCRIPT && !state.responded

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        val result = scriptAgent.run(ScriptRequest(message = state.request.message), sessionId)
        return state.copy(responded = true, response = KitResponse.Text(result.message))
    }
}
