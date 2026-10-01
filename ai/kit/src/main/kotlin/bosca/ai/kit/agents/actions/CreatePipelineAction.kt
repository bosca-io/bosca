package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.pipeline.PipelineAgent
import bosca.ai.kit.agents.pipeline.PipelineRequest
import bosca.ai.kit.agents.pipeline.PipelineServices
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.configuration.KitJson

/** Routes pipeline intents through the bounded, tool-driven [PipelineAgent] authoring loop. */
class CreatePipelineAction(
    promptExecutor: PromptExecutor,
    model: LLModel,
    pipelines: PipelineServices,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitAction() {
    private val pipelineAgent = PipelineAgent(promptExecutor, model, pipelines, json, sessionService)

    override val name: String = "create_or_edit_pipeline"

    override fun precondition(state: KitState): Boolean = state.route == KitRoute.PIPELINE && !state.responded

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        val result = pipelineAgent.run(PipelineRequest(state.request.message), sessionId)
        val message = buildString {
            append(result.message)
            result.editorPath?.takeIf { it !in result.message }?.let { append("\n\nOpen in Studio: ").append(it) }
        }
        return state.copy(responded = true, response = KitResponse.Text(message))
    }
}
