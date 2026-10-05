package bosca.ai.kit.metadata

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.kit.agents.KitModels
import bosca.ai.kit.agents.actions.metadataDocumentText
import bosca.ai.kit.agents.description.DescriptionAgent
import bosca.ai.kit.agents.description.DescriptionRequest
import bosca.ai.kit.agents.readingtime.ReadingTimeAgent
import bosca.ai.kit.agents.readingtime.ReadingTimeRequest
import bosca.ai.kit.agents.topics.AvailableTopic
import bosca.ai.kit.agents.topics.TopicsAgent
import bosca.ai.kit.agents.topics.TopicsRequest
import bosca.ai.kit.configuration.KitJson
import bosca.ai.models.service.ModelService
import bosca.ai.prompts.service.PromptService
import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.find.FindAttributeInput
import bosca.content.find.FindAttributesInput
import bosca.content.find.FindQueryInput
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataAIService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json

/**
 * Kit's implementation of the content-layer [MetadataAIService] contract — the direct (non-chat) entry
 * point that `MetadataAIController` calls. It reuses Kit's structured sub-agents
 * ([DescriptionAgent]/[TopicsAgent]/[ReadingTimeAgent]) with `sessionService = null` (a one-shot query
 * doesn't checkpoint), reading the document text the same way the chat actions do
 * ([metadataDocumentText] — provided-or-fetched), and returns the suggestions without persisting them.
 *
 * Each feature is driven by its configured [bosca.ai.prompts.model.Prompt] and [bosca.ai.models.model.Model]
 * (the `prompt.metadata.*` / `model.metadata.*` keys seeded by the installers): the prompt supplies the
 * system + user-prompt text and the model selects the LLM. Both are looked up per call so edits take effect
 * without a restart, and both fall back — to the sub-agent's built-in prompt and the [KitModels] default —
 * when absent or unmappable, so enrichment keeps working even if the definitions are missing.
 */
@ServiceImplementation
class KitMetadataAIService(
    private val promptExecutor: PromptExecutor,
    private val models: KitModels,
    private val json: KitJson,
    private val documentService: DocumentService,
    private val collectionService: CollectionService,
    private val promptService: PromptService,
    private val modelService: ModelService,
) : MetadataAIService {

    override suspend fun description(metadata: Metadata, document: DocumentInput?): String {
        val text = metadataDocumentText(metadata, document, documentService)
        if (text.isBlank()) return ""
        val describer = DescriptionAgent(
            promptExecutor,
            model(MODEL_DESCRIPTION, models.describe),
            json,
            null,
            promptService.getByKey(PROMPT_DESCRIPTION),
        )
        return describer.run(DescriptionRequest(text), UUID.random().toString()).description
    }

    override suspend fun topics(metadata: Metadata, document: DocumentInput?): List<Collection> {
        val text = metadataDocumentText(metadata, document, documentService)
        if (text.isBlank()) return emptyList()
        // Candidate topics = collections tagged type = "Topic"; match against them, then resolve the
        // matches back to the real collections (keep only ids actually offered).
        val available = collectionService.find(
            FindQueryInput(
                attributes = listOf(
                    FindAttributesInput(attributes = listOf(FindAttributeInput(key = "type", value = "Topic"))),
                ),
            ),
        )
        val byId = available.associateBy { it.id }
        val topicMatcher = TopicsAgent(
            promptExecutor,
            model(MODEL_TOPICS, models.topics),
            json,
            null,
            promptService.getByKey(PROMPT_TOPICS),
        )
        val response = topicMatcher.run(
            TopicsRequest(text = text, availableTopics = available.map { AvailableTopic(it.id, it.name) }),
            UUID.random().toString(),
        )
        return response.topics.mapNotNull { match -> runCatching { UUID.parse(match.id) }.getOrNull()?.let { byId[it] } }
    }

    override suspend fun readingTimeInMinutes(metadata: Metadata, document: DocumentInput?): Int {
        val text = metadataDocumentText(metadata, document, documentService)
        if (text.isBlank()) return 0
        val estimator = ReadingTimeAgent(
            promptExecutor,
            model(MODEL_READING_TIME, models.readingTime),
            json,
            null,
            promptService.getByKey(PROMPT_READING_TIME),
        )
        return estimator.run(ReadingTimeRequest(text), UUID.random().toString()).readingTimeInMinutes
    }

    /**
     * Resolve the configured model for [key] to a Koog [LLModel], falling back to [fallback] when the model
     * is absent or its stored type can't be mapped — so a missing/misconfigured model never breaks enrichment.
     */
    private suspend fun model(key: String, fallback: LLModel): LLModel =
        modelService.getByKey(key)?.let { runCatching { it.toLLMModel() }.getOrNull() } ?: fallback

    private companion object {
        private const val PROMPT_DESCRIPTION = "prompt.metadata.description"
        private const val PROMPT_TOPICS = "prompt.metadata.topics"
        private const val PROMPT_READING_TIME = "prompt.metadata.reading.time"
        private const val MODEL_DESCRIPTION = "model.metadata.description"
        private const val MODEL_TOPICS = "model.metadata.topics"
        private const val MODEL_READING_TIME = "model.metadata.reading.time"
    }
}
