package bosca.jobs

import bosca.ai.models.model.Model
import bosca.ai.models.service.ModelService
import bosca.ai.prompts.model.Prompt
import bosca.ai.prompts.service.PromptService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.transformations.DocumentToTextConfiguration
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.service.ObjectStorageService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [GoogleGenAITTSExecutor.execute] covering the early
 * error-condition branches that precede any Google GenAI client construction.
 *
 * The happy path (lines that build a [com.google.genai.Client] via its static
 * builder, read Ktor [io.ktor.server.config.ApplicationConfig] properties, invoke
 * the GenAI models API, and run [convertPcmToWav]) is not unit-testable: the GenAI
 * SDK client is created inline through a static, final factory that requires real
 * Google credentials and network access, so those lines are left to integration
 * coverage.
 */
@OptIn(InternalDI::class)
class GoogleGenAITTSJobCoverageTest {

    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val documentService = mockk<DocumentService>(relaxed = true)
    private val bibleService = mockk<BibleService>(relaxed = true)
    private val modelService = mockk<ModelService>(relaxed = true)
    private val promptService = mockk<PromptService>(relaxed = true)
    private val application = mockk<BoscaApplication>(relaxed = true)
    private val objectStorageService = mockk<ObjectStorageService>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private val executor = GoogleGenAITTSExecutor(
        metadataService,
        documentService,
        bibleService,
        modelService,
        promptService,
        application,
        objectStorageService,
        json,
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun model(key: String) = Model(
        key = key,
        type = "google.Gemini2_5Flash",
        name = "TTS Model",
        description = "test",
    )

    private fun prompt(key: String) = Prompt(
        key = key,
        name = "TTS Prompt",
        description = "test",
        systemPrompt = "system",
        userPrompt = "user {document}",
        inputType = "text",
        outputType = "audio",
        schema = null,
    )

    private fun metadata(id: UUID) = Metadata(
        id = id,
        name = "test-doc",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 512L,
        languageTag = "en",
        workflowStateId = "published",
        created = OffsetDateTime.now(),
    )

    @OptIn(Internal::class)
    private suspend fun executeJob(
        id: UUID = UUID.random(),
        version: Int = 1,
        modelKey: String = "tts-model",
        promptKey: String = "tts-prompt",
        configuration: DocumentToTextConfiguration = DocumentToTextConfiguration(),
    ) {
        val jobDefinition = GoogleGenAITTSJob(
            id = id,
            version = version,
            modelKey = modelKey,
            promptKey = promptKey,
            configuration = configuration,
        )
        val jobQueue = mockk<JobQueue>(relaxed = true)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(jobDefinition),
            executor = GoogleGenAITTSExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `fails when model is missing`() = runTest {
        coEvery { modelService.getByKey("tts-model") } returns null

        val exception = assertFailsWith<IllegalStateException> {
            executeJob(modelKey = "tts-model")
        }
        assertTrue(exception.message?.contains("Missing model") == true)
    }

    @OptIn(Internal::class)
    @Test
    fun `fails when prompt is missing`() = runTest {
        coEvery { modelService.getByKey("tts-model") } returns model("tts-model")
        coEvery { promptService.getByKey("tts-prompt") } returns null

        val exception = assertFailsWith<IllegalStateException> {
            executeJob(modelKey = "tts-model", promptKey = "tts-prompt")
        }
        assertTrue(exception.message?.contains("Missing prompt") == true)
    }

    @OptIn(Internal::class)
    @Test
    fun `fails when metadata is missing`() = runTest {
        val id = UUID.random()
        coEvery { modelService.getByKey("tts-model") } returns model("tts-model")
        coEvery { promptService.getByKey("tts-prompt") } returns prompt("tts-prompt")
        coEvery { metadataService.getById(id, 1) } returns null

        val exception = assertFailsWith<IllegalStateException> {
            executeJob(id = id, version = 1, modelKey = "tts-model", promptKey = "tts-prompt")
        }
        assertTrue(exception.message?.contains("Missing metadata") == true)
    }

    @OptIn(Internal::class)
    @Test
    fun `fails when document is missing`() = runTest {
        val id = UUID.random()
        coEvery { modelService.getByKey("tts-model") } returns model("tts-model")
        coEvery { promptService.getByKey("tts-prompt") } returns prompt("tts-prompt")
        coEvery { metadataService.getById(id, 1) } returns metadata(id)
        coEvery { documentService.getDocument(id, 1) } returns null

        val exception = assertFailsWith<IllegalStateException> {
            executeJob(id = id, version = 1, modelKey = "tts-model", promptKey = "tts-prompt")
        }
        assertTrue(exception.message?.contains("Missing document") == true)
    }
}
