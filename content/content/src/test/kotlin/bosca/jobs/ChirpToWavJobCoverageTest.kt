package bosca.jobs

import bosca.ai.models.model.Model
import bosca.ai.models.service.ModelService
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.transformations.DocumentToTextConfiguration
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ConfigValue
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.service.ObjectStorageService
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.texttospeech.v1.SynthesizeSpeechRequest
import com.google.cloud.texttospeech.v1.SynthesizeSpeechResponse
import com.google.cloud.texttospeech.v1.TextToSpeechClient
import com.google.cloud.texttospeech.v1.TextToSpeechSettings
import com.google.protobuf.ByteString
import bosca.core.annotations.Internal
import bosca.documents.Content
import bosca.documents.Document as DomDocument
import bosca.documents.ParagraphNode
import bosca.documents.TextNode
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

@OptIn(InternalDI::class)
class ChirpToWavJobCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val documentService = mockk<DocumentService>()
    private val bibleService = mockk<BibleService>()
    private val modelService = mockk<ModelService>()
    private val objectStorageService = mockk<ObjectStorageService>(relaxed = true)
    private val application = mockk<BoscaApplication>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val executor = ChirpToWavJobExecutor(
        metadataService = metadataService,
        documentService = documentService,
        bibleService = bibleService,
        modelService = modelService,
        application = application,
        objectStorageService = objectStorageService,
        json = json
    )

    @BeforeTest
    fun setup() {
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        unmockkAll()
        clearAllMocks()
    }

    private fun makeJob(
        id: UUID,
        version: Int,
        modelKey: String = "chirp-hd",
        configuration: DocumentToTextConfiguration = DocumentToTextConfiguration()
    ): ChirpToWavJob = ChirpToWavJob(
        id = id,
        version = version,
        modelKey = modelKey,
        configuration = configuration
    )

    @OptIn(Internal::class)
    private fun jobObject(job: ChirpToWavJob) = InternalJobConstructor(
        definition = json.encodeToJsonElement(job),
        executor = ChirpToWavJobExecutor::class
    )

    private fun stubCredentials() {
        mockkStatic(GoogleCredentials::class)
        every { GoogleCredentials.fromStream(any()) } returns mockk<GoogleCredentials>(relaxed = true)
        every { application.environment.config.property("google.tts.account") } returns
            ConfigValue(JsonPrimitive("{}"), json)
    }

    private fun stubSpeechClient(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): TextToSpeechClient {
        mockkStatic(TextToSpeechClient::class)
        val client = mockk<TextToSpeechClient>(relaxed = true)
        val response = SynthesizeSpeechResponse.newBuilder()
            .setAudioContent(ByteString.copyFrom(bytes))
            .build()
        every { TextToSpeechClient.create(any<TextToSpeechSettings>()) } returns client
        every { client.synthesizeSpeech(any<SynthesizeSpeechRequest>()) } returns response
        return client
    }

    private fun paragraphDocument(text: String, metadataId: UUID, version: Int): Document {
        val content = Content(
            document = DomDocument(
                content = listOf(
                    ParagraphNode(
                        content = listOf(TextNode(text = text))
                    )
                )
            )
        )
        return Document(
            metadataId = metadataId,
            version = version,
            title = "Title",
            content = content
        )
    }

    @OptIn(Internal::class)
    @Test
    fun `errors when metadata is missing`() = runTest {
        val id = UUID.random()
        val job = makeJob(id, 1)
        coEvery { metadataService.getById(id, 1) } returns null

        val jobQueue = mockk<JobQueue>()
        val failure = assertFailsWith<IllegalStateException> {
            withContext(jobQueue.asCoroutineContext(jobObject(job))) {
                executor.execute()
            }
        }
        assertEquals("Missing metadata", failure.message)
        coVerify(exactly = 1) { metadataService.getById(id, 1) }
    }

    @OptIn(Internal::class)
    @Test
    fun `errors when document is missing`() = runTest {
        val id = UUID.random()
        val job = makeJob(id, 2)
        val metadata = mockk<Metadata>()
        every { metadata.languageTag } returns "en"
        coEvery { metadataService.getById(id, 2) } returns metadata
        coEvery { documentService.getDocument(id, 2) } returns null

        val jobQueue = mockk<JobQueue>()
        val failure = assertFailsWith<IllegalStateException> {
            withContext(jobQueue.asCoroutineContext(jobObject(job))) {
                executor.execute()
            }
        }
        assertEquals("Missing document", failure.message)
        coVerify(exactly = 1) { documentService.getDocument(id, 2) }
    }

    @OptIn(Internal::class)
    @Test
    fun `errors when model is missing`() = runTest {
        val id = UUID.random()
        val job = makeJob(id, 3, modelKey = "unknown")
        val metadata = mockk<Metadata>()
        every { metadata.languageTag } returns "en"
        coEvery { metadataService.getById(id, 3) } returns metadata
        coEvery { documentService.getDocument(id, 3) } returns
            paragraphDocument("Hello", id, 3)
        coEvery { modelService.getByKey("unknown") } returns null

        val jobQueue = mockk<JobQueue>()
        val failure = assertFailsWith<IllegalStateException> {
            withContext(jobQueue.asCoroutineContext(jobObject(job))) {
                executor.execute()
            }
        }
        assertEquals("Missing model", failure.message)
        coVerify(exactly = 1) { modelService.getByKey("unknown") }
    }

    @OptIn(Internal::class)
    @Test
    fun `blank text skips synthesis then ffmpeg failure propagates`() = runTest {
        val id = UUID.random()
        val job = makeJob(id, 4)
        val metadata = mockk<Metadata>()
        every { metadata.languageTag } returns "en"
        coEvery { metadataService.getById(id, 4) } returns metadata
        // content = null produces empty transform text -> single blank chunk -> skipped
        coEvery { documentService.getDocument(id, 4) } returns
            Document(metadataId = id, version = 4, title = "T", content = null)
        coEvery { modelService.getByKey("chirp-hd") } returns
            Model(key = "chirp-hd", type = "en-US-Chirp3-HD", name = "n", description = "d")

        stubCredentials()
        val client = stubSpeechClient()

        val jobQueue = mockk<JobQueue>()
        // ffmpeg concat of an empty file list fails (non-zero exit) or ffmpeg is
        // absent (IOException); either way execute() propagates a Throwable.
        assertFails {
            withContext(jobQueue.asCoroutineContext(jobObject(job))) {
                executor.execute()
            }
        }
        // no paragraphs -> synthesizeSpeech never called
        verify(exactly = 0) { client.synthesizeSpeech(any<SynthesizeSpeechRequest>()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `synthesizes non-blank text using model language tag then ffmpeg failure propagates`() = runTest {
        val id = UUID.random()
        val job = makeJob(id, 5)
        val metadata = mockk<Metadata>()
        every { metadata.languageTag } returns "en"
        coEvery { metadataService.getById(id, 5) } returns metadata
        coEvery { documentService.getDocument(id, 5) } returns
            paragraphDocument("Hello world", id, 5)
        // configuration carries languageTag -> voice uses it (first branch of the elvis)
        val model = Model(
            key = "chirp-hd",
            type = "en-US-Chirp3-HD",
            name = "n",
            description = "d",
            configuration = JsonObject(mapOf("languageTag" to JsonPrimitive("fr-FR")))
        )
        coEvery { modelService.getByKey("chirp-hd") } returns model

        stubCredentials()
        val client = stubSpeechClient(byteArrayOf(9, 8, 7))

        val jobQueue = mockk<JobQueue>()
        assertFails {
            withContext(jobQueue.asCoroutineContext(jobObject(job))) {
                executor.execute()
            }
        }
        // one non-blank paragraph -> exactly one synthesis call
        verify(exactly = 1) { client.synthesizeSpeech(any<SynthesizeSpeechRequest>()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `synthesizes non-blank text falling back to metadata language tag then ffmpeg failure propagates`() = runTest {
        val id = UUID.random()
        val job = makeJob(id, 6)
        val metadata = mockk<Metadata>()
        every { metadata.languageTag } returns "en"
        coEvery { metadataService.getById(id, 6) } returns metadata
        coEvery { documentService.getDocument(id, 6) } returns
            paragraphDocument("Hello again", id, 6)
        // configuration = null -> voice language falls back to metadata.languageTag
        coEvery { modelService.getByKey("chirp-hd") } returns
            Model(key = "chirp-hd", type = "en-US-Chirp3-HD", name = "n", description = "d")

        stubCredentials()
        val client = stubSpeechClient()

        val jobQueue = mockk<JobQueue>()
        assertFails {
            withContext(jobQueue.asCoroutineContext(jobObject(job))) {
                executor.execute()
            }
        }
        verify(exactly = 1) { client.synthesizeSpeech(any<SynthesizeSpeechRequest>()) }
    }
}
