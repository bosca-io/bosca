package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataContent
import bosca.content.metadata.model.MetadataContentUrls
import bosca.content.metadata.model.MetadataType
import bosca.serialization.UUID
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataContentControllerCoverageTest {

    private val storage = mockk<ObjectStorageService>()
    private val json = Json

    private val controller = MetadataContentController(storage, json)

    private fun metadata(
        contentType: String = "application/json",
        contentLength: Long? = 42
    ) = Metadata(
        id = UUID.random(),
        name = "Test",
        type = MetadataType.STANDARD,
        languageTag = "en",
        contentType = contentType,
        contentLength = contentLength,
        workflowStateId = "published"
    )

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `json returns null when not allowed`() = runTest {
        val content = MetadataContent(metadata = metadata(), allowed = false)

        assertNull(controller.json(content))
    }

    @Test
    fun `json parses json element when allowed`() = runTest {
        val content = MetadataContent(metadata = metadata(), allowed = true)
        val path = mockk<ObjectPath>()
        coEvery { storage.getPath(content.metadata) } returns path
        coEvery { storage.getString(path) } returns "{\"key\":\"value\"}"

        val result = controller.json(content)

        assertEquals(JsonPrimitive("value"), result?.jsonObject?.get("key"))
    }

    @Test
    fun `json returns null when storage throws`() = runTest {
        val content = MetadataContent(metadata = metadata(), allowed = true)
        val path = mockk<ObjectPath>()
        coEvery { storage.getPath(content.metadata) } returns path
        coEvery { storage.getString(path) } throws RuntimeException("boom")

        assertNull(controller.json(content))
    }

    @Test
    fun `text returns null when not allowed`() = runTest {
        val content = MetadataContent(metadata = metadata(), allowed = false)

        assertNull(controller.text(content))
    }

    @Test
    fun `text returns decoded string when allowed`() = runTest {
        val content = MetadataContent(metadata = metadata(), allowed = true)
        val path = mockk<ObjectPath>()
        coEvery { storage.getPath(content.metadata) } returns path
        coEvery { storage.getString(path) } returns "hello world"

        assertEquals("hello world", controller.text(content))
    }

    @Test
    fun `text returns null when storage throws`() = runTest {
        val content = MetadataContent(metadata = metadata(), allowed = true)
        val path = mockk<ObjectPath>()
        coEvery { storage.getPath(content.metadata) } returns path
        coEvery { storage.getString(path) } throws RuntimeException("boom")

        assertNull(controller.text(content))
    }

    @Test
    fun `type returns metadata content type`() {
        val content = MetadataContent(metadata = metadata(contentType = "text/plain"), allowed = true)

        assertEquals("text/plain", controller.type(content))
    }

    @Test
    fun `length returns metadata content length`() {
        val content = MetadataContent(metadata = metadata(contentLength = 123), allowed = true)

        assertEquals(123, controller.length(content))
    }

    @Test
    fun `length returns null when content length absent`() {
        val content = MetadataContent(metadata = metadata(contentLength = null), allowed = true)

        assertNull(controller.length(content))
    }

    @Test
    fun `urls wraps the metadata`() {
        val md = metadata()
        val content = MetadataContent(metadata = md, allowed = true)

        val urls = controller.urls(content)

        assertEquals(MetadataContentUrls(md), urls)
        assertTrue(urls.metadata === md)
    }
}
