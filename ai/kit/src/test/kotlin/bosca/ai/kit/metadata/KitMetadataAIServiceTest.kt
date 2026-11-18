package bosca.ai.kit.metadata

import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import bosca.ai.kit.agents.KitModels
import bosca.ai.kit.agents.koogJson
import bosca.ai.kit.configuration.KitJson
import bosca.ai.models.service.ModelService
import bosca.ai.prompts.service.PromptService
import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.EmptyMetadata
import bosca.content.metadata.service.DocumentService
import bosca.documents.Content
import bosca.documents.Document
import bosca.documents.ParagraphNode
import bosca.documents.TextNode
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

/** Direct content enrichment remains independent of the ACP conversation path. */
class KitMetadataAIServiceTest {
    private val model = LLModel(OpenAILLMProvider, "mock", listOf(LLMCapability.Completion))
    private val json = KitJson(koogJson(Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } }))
    private val documents = mockk<DocumentService>()
    private val collections = mockk<CollectionService>()
    private val prompts = mockk<PromptService> { coEvery { getByKey(any()) } returns null }
    private val models = mockk<ModelService> { coEvery { getByKey(any()) } returns null }
    private val metadata = EmptyMetadata.copy(id = UUID.random(), name = "Document", version = 3)
    private fun document(text: String) = DocumentInput(title = "Document",
        content = Content(Document(content = listOf(ParagraphNode(content = listOf(TextNode(text = text)))))))
    private fun service(response: String, marker: String) = KitMetadataAIService(
        getMockExecutor { mockLLMAnswer(response) onRequestContains marker }, KitModels(model), json,
        documents, collections, prompts, models)

    @Test fun `description reads the live editor document without fetching or persisting it`() = runTest {
        val result = service("""{"description":"A concise description."}""", "Unsaved editor text")
            .description(metadata, document("Unsaved editor text"))
        assertEquals("A concise description.", result)
        verify { documents wasNot Called }
    }

    @Test fun `reading time loads the selected stored version through the owning document service`() = runTest {
        coEvery { documents.getDocument(metadata.id, metadata.version) } returns document("Stored text").toDocument(metadata)
        assertEquals(2, service("""{"totalWordCount":400,"readingTimeInMinutes":2}""", "Stored text")
            .readingTimeInMinutes(metadata, null))
        coVerify(exactly = 1) { documents.getDocument(metadata.id, metadata.version) }
    }

    @Test fun `topics return only installed candidates and discard invented identifiers`() = runTest {
        val topic = Collection(id = UUID.random(), name = "Gardening", languageTag = "en", workflowStateId = "published")
        coEvery { collections.find(any()) } returns listOf(topic)
        val response = """{"topics":[{"id":"${topic.id}","name":"Gardening"},{"id":"${UUID.random()}","name":"Invented"},{"id":"invalid","name":"Invalid"}]}"""
        assertEquals(listOf(topic), service(response, "Gardening text").topics(metadata, document("Gardening text")))
        coVerify { collections.find(match { it.attributes?.single()?.attributes?.single()?.let { a -> a.key == "type" && a.value == "Topic" } == true }) }
    }

    @Test fun `empty content returns no suggestions and document cancellation propagates`() = runTest {
        val service = service("unused", "unused")
        val empty = DocumentInput(title = "Empty", content = null)
        assertEquals("", service.description(metadata, empty))
        assertEquals(0, service.readingTimeInMinutes(metadata, empty))
        assertEquals(emptyList(), service.topics(metadata, empty))
        coVerify(exactly = 0) { collections.find(any()) }
        coEvery { documents.getDocument(metadata.id, metadata.version) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { service.description(metadata, null) }
    }
}
