package bosca.ai.kit.image

import bosca.ai.kit.agents.image.KitImageClient
import bosca.content.collection.model.Collection
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import bosca.storage.service.*
import com.google.genai.Client
import com.google.genai.types.HttpOptions
import com.sun.net.httpserver.HttpServer
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.time.OffsetDateTime
import java.util.Base64
import kotlin.test.*

/** Real SDK HTTP and filesystem round trips; the provider and metadata database are local fixtures. */
@OptIn(bosca.di.annotation.InternalDI::class)
class KitImageGenerationServiceTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var client: Client
    private lateinit var storage: FileSystemObjectStorageService
    private lateinit var service: KitImageGenerationService
    private val metadata = mockk<MetadataService>()
    private val records = mutableMapOf<UUID, Metadata>()
    private val requests = mutableListOf<JsonObject>()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
    private var status = 200
    private var parts = ""

    @BeforeTest fun setup() {
        parts = """[{"inlineData":{"mimeType":"image/png","data":"${Base64.getEncoder().encodeToString(png)}"}}]"""
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val body = (if (status == 200) """{"candidates":[{"content":{"role":"model","parts":$parts},"finishReason":"STOP"}]}"""
                else """{"error":{"code":$status,"message":"fixture failure","status":"INTERNAL"}}""").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        client = Client.builder().apiKey("fixture").vertexAI(false)
            .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:${server.address.port}").build()).build()
        val imageClient = mockk<KitImageClient> {
            every { require() } returns client
            every { model } returns "fixture-model"
        }
        storage = FileSystemObjectStorageService("http://fixture", "http://fixture", mockk(), directory.root.path, mockk())
        coEvery { metadata.add(any(), any(), any<MetadataInput>()) } answers {
            val input = thirdArg<MetadataInput>()
            Metadata(UUID.random(), name = input.name, type = MetadataType.STANDARD,
                contentType = requireNotNull(input.contentType), contentLength = input.contentLength,
                languageTag = input.languageTag, workflowStateId = "draft").also { records[it.id] = it }
        }
        coEvery { metadata.setUploaded(any(), any(), any()) } answers {
            val id = firstArg<UUID>()
            records[id] = records.getValue(id).copy(uploaded = OffsetDateTime.now(), contentType = secondArg(), contentLength = thirdArg())
        }
        coEvery { metadata.getById(any()) } answers { records[firstArg<UUID>()] }
        ProviderRegistry.clear()
        provides<KitImageClient> { imageClient }
        provides<MetadataService> { metadata }
        provides<ObjectStorageService> { storage }
        service = runBlocking { assertIs<KitImageGenerationService>(KitImageGenerationServiceProvider().get()) }
    }
    @AfterTest fun cleanup() { client.close(); server.stop(0); ProviderRegistry.clear() }

    @Test fun `generate and edit store new drafts and preserve source bytes`() = runBlocking {
        val parent = Collection(UUID.random(), "Images", "en", workflowStateId = "draft")
        val original = service.generate("a bird", "Bird", null, parent)
        assertNotNull(original.uploaded)
        assertEquals("draft", original.workflowStateId)
        assertEquals(png.size.toLong(), original.contentLength)
        assertContentEquals(png, storage.download(original).use { it.readBytes() })
        coVerify { metadata.add(parent, null, match<MetadataInput> { it.parentCollectionId == parent.id && it.name == "Bird" }) }
        val edited = service.generate("make it blue", "Blue bird", original.copy(languageTag = "fr"), null)
        assertNotEquals(original.id, edited.id)
        assertEquals("fr", edited.languageTag)
        assertContentEquals(png, storage.download(edited).use { it.readBytes() })
        assertContentEquals(png, storage.download(original).use { it.readBytes() })
        assertEquals(2, requests.size)
        val input = requests.last().getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray
        assertEquals("make it blue", input.last().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(png), input.first().jsonObject.getValue("inlineData").jsonObject.getValue("data").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("TEXT"), JsonPrimitive("IMAGE"))), requests.first().getValue("generationConfig").jsonObject["responseModalities"])
        coVerify(exactly = 0) { metadata.setReady(any(), any()) }
    }

    @Test fun `missing empty or non-image output creates no metadata`() = runBlocking {
        for (output in listOf("[]", """[{"text":"no image"}]""", """[{"inlineData":{"mimeType":"text/plain","data":"YQ=="}}]""", """[{"inlineData":{"mimeType":"image/png","data":""}}]""")) {
            parts = output
            assertContains(assertFailsWith<IllegalStateException> { service.generate("bird", "Bird", null, null) }.message.orEmpty(), "no image")
        }
        coVerify(exactly = 0) { metadata.add(any(), any(), any<MetadataInput>()) }
    }

    @Test fun `provider failure is propagated without retries or storage mutations`() = runBlocking {
        status = 503
        assertFailsWith<com.google.genai.errors.ServerException> { service.generate("bird", "Bird", null, null) }
        assertEquals(1, requests.size)
        assertTrue(records.isEmpty())
    }

    @Test fun `invalid input and missing configuration never call provider`() = runBlocking {
        val source = Metadata(UUID.random(), name = "Source", type = MetadataType.STANDARD, contentType = "text/plain", contentLength = 3, languageTag = "en", workflowStateId = "draft")
        assertFailsWith<IllegalArgumentException> { service.generate(" ", "Bird", null, null) }
        assertFailsWith<IllegalArgumentException> { service.generate("bird", " ", null, null) }
        for (invalid in listOf(source, source.copy(contentType = "image/png"), source.copy(contentType = "image/png", uploaded = OffsetDateTime.now(), contentLength = null))) {
            assertFailsWith<IllegalArgumentException> { service.generate("bird", "Bird", invalid, null) }
        }
        assertFailsWith<IllegalStateException> { KitImageGenerationService(KitImageClient(null, "unused"), metadata, storage).generate("bird", "Bird", null, null) }
        assertTrue(requests.isEmpty())
        assertTrue(records.isEmpty())
    }

    @Test fun `upload failure and cancellation do not mark the image uploaded`() = runBlocking {
        val brokenStorage = mockk<ObjectStorageService> {
            coEvery { getPath(any<Metadata>(), any()) } returns StringObjectPath("fixture")
        }
        val imageClient = mockk<KitImageClient> { every { require() } returns client; every { model } returns "fixture-model" }
        val broken = KitImageGenerationService(imageClient, metadata, brokenStorage)
        coEvery { brokenStorage.setInputStream(any(), any(), any()) } throws java.io.IOException("storage failed")
        assertFailsWith<java.io.IOException> { broken.generate("bird", "Bird", null, null) }
        coEvery { brokenStorage.setInputStream(any(), any(), any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { broken.generate("bird", "Bird", null, null) }
        coVerify(exactly = 0) { metadata.setUploaded(any(), any(), any()) }
        assertTrue(records.values.all { it.uploaded == null })
    }
}
