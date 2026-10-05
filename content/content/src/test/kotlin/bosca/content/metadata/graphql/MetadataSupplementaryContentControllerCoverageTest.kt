package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataSupplementaryContent
import bosca.content.metadata.model.MetadataType
import bosca.serialization.UUID
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataSupplementaryContentControllerCoverageTest {

    private val storage = mockk<ObjectStorageService>()

    private val controller = MetadataSupplementaryContentController(storage)

    private val path = mockk<ObjectPath>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createMetadata(id: UUID = UUID.random()) = Metadata(
        id = id,
        name = "Test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published"
    )

    private fun createSupplementary(
        id: UUID = UUID.random(),
        metadataId: UUID = UUID.random(),
        contentType: String? = "application/json",
        contentLength: Long? = 42L
    ) = MetadataSupplementary(
        id = id,
        metadataId = metadataId,
        key = "transcript",
        name = "Transcript",
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        contentType = contentType,
        contentLength = contentLength,
        sourceId = null,
        sourceIdentifier = null
    )

    private fun createContent(
        contentType: String? = "application/json",
        contentLength: Long? = 42L
    ): MetadataSupplementaryContent {
        val metadataId = UUID.random()
        return MetadataSupplementaryContent(
            metadata = createMetadata(id = metadataId),
            supplementary = createSupplementary(
                metadataId = metadataId,
                contentType = contentType,
                contentLength = contentLength
            )
        )
    }

    // ---- length ----

    @Test
    fun `length returns supplementary content length`() {
        val content = createContent(contentLength = 1234L)

        assertEquals(1234L, controller.length(content))
    }

    @Test
    fun `length returns null when content length is null`() {
        val content = createContent(contentLength = null)

        assertNull(controller.length(content))
    }

    // ---- type ----

    @Test
    fun `type returns supplementary content type`() {
        val content = createContent(contentType = "text/markdown")

        assertEquals("text/markdown", controller.type(content))
    }

    @Test
    fun `type returns null when content type is null`() {
        val content = createContent(contentType = null)

        assertNull(controller.type(content))
    }

    // ---- urls ----

    @Test
    fun `urls returns urls model wrapping metadata and supplementary`() {
        val content = createContent()

        val urls = controller.urls(content)

        assertEquals(content.metadata, urls.metadata)
        assertEquals(content.supplementary, urls.supplementary)
    }

    // ---- json ----

    @Test
    fun `json reads storage content and decodes to string`() = runTest {
        val content = createContent()
        val payload = """{"key":"value"}"""
        coEvery { storage.getPath(content.metadata, content.supplementary.id) } returns path
        coEvery { storage.getString(path) } returns payload

        assertEquals(payload, controller.json(content))
    }

    @Test
    fun `json returns empty string for empty stream`() = runTest {
        val content = createContent()
        coEvery { storage.getPath(content.metadata, content.supplementary.id) } returns path
        coEvery { storage.getString(path) } returns ""

        assertEquals("", controller.json(content))
    }

    // ---- text ----

    @Test
    fun `text reads storage content and decodes to string`() = runTest {
        val content = createContent()
        val payload = "plain text body"
        coEvery { storage.getPath(content.metadata, content.supplementary.id) } returns path
        coEvery { storage.getString(path) } returns payload

        assertEquals(payload, controller.text(content))
    }

    @Test
    fun `text returns empty string for empty stream`() = runTest {
        val content = createContent()
        coEvery { storage.getPath(content.metadata, content.supplementary.id) } returns path
        coEvery { storage.getString(path) } returns ""

        assertEquals("", controller.text(content))
    }
}
