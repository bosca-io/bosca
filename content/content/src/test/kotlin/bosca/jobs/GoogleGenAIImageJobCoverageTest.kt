package bosca.jobs

import bosca.ai.models.model.Model
import bosca.ai.models.service.ModelService
import bosca.ai.prompts.service.PromptService
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.service.ObjectStorageService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

@OptIn(InternalDI::class)
class GoogleGenAIImageJobCoverageTest {

    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val modelService = mockk<ModelService>(relaxed = true)
    private val promptService = mockk<PromptService>(relaxed = true)
    private val objectStorageService = mockk<ObjectStorageService>(relaxed = true)
    private val documentService = mockk<DocumentService>(relaxed = true)
    private val bibleService = mockk<BibleService>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val application = BoscaApplication(
        ApplicationConfig.load(
            """
            google:
              genai:
                account: "not-valid-service-account-json"
                vertexai:
                  enabled: false
            """.trimIndent().byteInputStream()
        )
    )

    private val executor = GoogleGenAIImageExecutor(
        metadataService = metadataService,
        modelService = modelService,
        promptService = promptService,
        application = application,
        objectStorageService = objectStorageService,
        documentService = documentService,
        bibleService = bibleService,
        json = json
    )

    @BeforeTest
    fun setup() {
        // Override the Json the BoscaApplication registered so getJobDefinition() decodes
        // with the same instance the test encodes with.
        provides<Json>(overrideExisting = true) { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun job(modelKey: String = "model-key", promptKey: String = "prompt-key") =
        InternalJobConstructor(
            definition = json.encodeToJsonElement(
                GoogleGenAIImageJob(
                    id = UUID.random(),
                    version = 1,
                    modelKey = modelKey,
                    promptKey = promptKey
                )
            ),
            executor = GoogleGenAIImageExecutor::class
        )

    @OptIn(Internal::class)
    @Test
    fun `fails when model is missing`() = runTest {
        val jobQueue = mockk<JobQueue>()
        coEvery { modelService.getByKey("model-key") } returns null

        val jobObj = job()

        val ex = assertFailsWith<IllegalStateException> {
            withContext(jobQueue.asCoroutineContext(jobObj)) {
                executor.execute()
            }
        }
        assertEquals("Missing model", ex.message)

        val key = "model-key"
        coVerify { modelService.getByKey(key) }
    }

    @OptIn(Internal::class)
    @Test
    fun `propagates credential parse failure after model is resolved`() = runTest {
        val jobQueue = mockk<JobQueue>()
        // Model resolves, so execution proceeds to credential loading, which fails
        // because the configured service-account payload is not valid JSON.
        coEvery { modelService.getByKey("model-key") } returns Model(
            id = UUID.random(),
            key = "model-key",
            type = "google.Gemini2_5Flash",
            name = "Gemini",
            description = "test model"
        )

        val jobObj = job()

        // GoogleCredentials.fromStream throws on the invalid payload; the executor does not
        // catch it, so the exception propagates. It must NOT be one of the later
        // "Missing prompt"/"Missing metadata" errors, proving we passed the model check.
        val ex = assertFails {
            withContext(jobQueue.asCoroutineContext(jobObj)) {
                executor.execute()
            }
        }
        val message = ex.message ?: ""
        assertNotEquals("Missing model", message)
        assertNotEquals("Missing prompt", message)
        assertNotEquals("Missing metadata", message)

        val key = "model-key"
        coVerify { modelService.getByKey(key) }
    }
}
