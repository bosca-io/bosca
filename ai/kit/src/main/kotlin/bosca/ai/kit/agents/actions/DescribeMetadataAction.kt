package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.description.DescriptionAgent
import bosca.ai.kit.agents.description.DescriptionRequest
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.configuration.KitJson
import bosca.content.metadata.service.DocumentService
import kotlinx.serialization.json.Json

/**
 * Generates a concise description for the document in context (`request.metadata`). This action **owns**
 * the [DescriptionAgent], extracts the document's text (the live `request.document` if provided, else the
 * stored document), and folds the result into a [KitResponse.Description] — it does not persist anything.
 */
class DescribeMetadataAction(
    promptExecutor: PromptExecutor,
    model: LLModel,
    private val documentService: DocumentService,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitAction() {

    private val describer = DescriptionAgent(promptExecutor, model, json, sessionService)

    override val name: String = "describe_metadata"

    override fun precondition(state: KitState): Boolean = state.route == KitRoute.DESCRIBE && !state.responded

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        val metadata = state.request.metadata
            ?: return state.copy(responded = true, response = KitResponse.Text("I need a document in context to describe — open one and ask again."))
        val text = metadataDocumentText(metadata, state.request.document, documentService)
        if (text.isBlank()) {
            return state.copy(responded = true, response = KitResponse.Text("That document has no readable text to summarize yet."))
        }
        val result = describer.run(DescriptionRequest(text), sessionId)
        return state.copy(responded = true, response = KitResponse.Description(result.description))
    }
}
