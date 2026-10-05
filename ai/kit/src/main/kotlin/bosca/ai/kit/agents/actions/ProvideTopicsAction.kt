package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.KitTopic
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.agents.topics.AvailableTopic
import bosca.ai.kit.agents.topics.TopicsAgent
import bosca.ai.kit.agents.topics.TopicsRequest
import bosca.ai.kit.configuration.KitJson
import bosca.content.collection.service.CollectionService
import bosca.content.find.FindAttributeInput
import bosca.content.find.FindAttributesInput
import bosca.content.find.FindQueryInput
import bosca.serialization.UUID
import kotlinx.serialization.json.Json

/**
 * Suggests relevant topics for the document in context (`request.metadata`). This action **owns** the
 * [TopicsAgent], gathers the candidate topics (collections tagged `type = "Topic"`) and the document
 * text, has the agent match them, then resolves the matches back to real collections — keeping only ids
 * that were actually offered, with the catalog's authoritative names. Returns [KitResponse.Topics];
 * nothing is persisted.
 */
class ProvideTopicsAction(
    promptExecutor: PromptExecutor,
    model: LLModel,
    private val documentService: bosca.content.metadata.service.DocumentService,
    private val collectionService: CollectionService,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitAction() {

    private val topicsAgent = TopicsAgent(promptExecutor, model, json, sessionService)

    override val name: String = "provide_topics"

    override fun precondition(state: KitState): Boolean = state.route == KitRoute.TOPICS && !state.responded

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        val metadata = state.request.metadata
            ?: return state.copy(responded = true, response = KitResponse.Text("I need a document in context to suggest topics for — open one and ask again."))
        val text = metadataDocumentText(metadata, state.request.document, documentService)
        if (text.isBlank()) {
            return state.copy(responded = true, response = KitResponse.Text("That document has no readable text to find topics from yet."))
        }
        val available = collectionService.find(
            FindQueryInput(
                attributes = listOf(
                    FindAttributesInput(attributes = listOf(FindAttributeInput(key = "type", value = "Topic"))),
                ),
            ),
        ).map { AvailableTopic(it.id, it.name) }

        val response = topicsAgent.run(TopicsRequest(text = text, availableTopics = available), sessionId)

        // Resolve matches to real topics: keep only ids the agent was actually offered, with the
        // catalog's own names (never a hallucinated id or a model-rewritten name).
        val byId = available.associateBy { it.id }
        val matched = response.topics.mapNotNull { match ->
            runCatching { UUID.parse(match.id) }.getOrNull()?.let { id -> byId[id]?.let { KitTopic(id, it.name) } }
        }
        return state.copy(responded = true, response = KitResponse.Topics(matched))
    }
}
