package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.readingtime.ReadingTimeAgent
import bosca.ai.kit.agents.readingtime.ReadingTimeRequest
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.configuration.KitJson
import bosca.content.metadata.service.DocumentService
import kotlinx.serialization.json.Json

/**
 * Estimates the reading time of the document in context (`request.metadata`). This action **owns** the
 * [ReadingTimeAgent], extracts the document's text, and folds the estimate into a
 * [KitResponse.ReadingTime]. Nothing is persisted.
 */
class ProvideReadingTimeAction(
    promptExecutor: PromptExecutor,
    model: LLModel,
    private val documentService: DocumentService,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitAction() {

    private val estimator = ReadingTimeAgent(promptExecutor, model, json, sessionService)

    override val name: String = "provide_reading_time"

    override fun precondition(state: KitState): Boolean = state.route == KitRoute.READING_TIME && !state.responded

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        val metadata = state.request.metadata
            ?: return state.copy(responded = true, response = KitResponse.Text("I need a document in context to estimate reading time for — open one and ask again."))
        val text = metadataDocumentText(metadata, state.request.document, documentService)
        if (text.isBlank()) {
            return state.copy(responded = true, response = KitResponse.Text("That document has no readable text to estimate a reading time from yet."))
        }
        val result = estimator.run(ReadingTimeRequest(text), sessionId)
        return state.copy(
            responded = true,
            response = KitResponse.ReadingTime(
                totalWordCount = result.totalWordCount,
                readingTimeInMinutes = result.readingTimeInMinutes,
            ),
        )
    }
}
