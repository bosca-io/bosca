package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.image.ImageAgent
import bosca.ai.kit.agents.image.ImageRequest
import bosca.ai.kit.agents.image.ImageServices
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.configuration.KitJson

/**
 * Handles an image request. This action **owns** the [ImageAgent] sub-agent (which runs its own tool
 * loop over the generate/edit image tools) and folds its structured summary into a [KitResponse.Text]
 * so Kit reports what it produced (with the markdown image reference).
 */
class ImageAction(
    promptExecutor: PromptExecutor,
    model: LLModel,
    images: ImageServices,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitAction() {

    private val imageAgent = ImageAgent(promptExecutor, model, images, json, sessionService)

    override val name: String = "manage_images"

    override fun precondition(state: KitState): Boolean = state.route == KitRoute.IMAGE && !state.responded

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        val result = imageAgent.run(ImageRequest(message = state.request.message), sessionId)
        return state.copy(responded = true, response = KitResponse.Text(result.message))
    }
}
