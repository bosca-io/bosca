package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class MetadataContentUrlsTest {

    private fun createMetadata(name: String = "Test") = Metadata(
        id = Uuid.random(),
        name = name,
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 50L,
        languageTag = "en",
        workflowStateId = "draft"
    )

    @Test
    fun `stores metadata field`() {
        val metadata = createMetadata("url-test")
        val urls = MetadataContentUrls(metadata = metadata)
        assertEquals(metadata, urls.metadata)
        assertEquals("url-test", urls.metadata.name)
    }

    @Test
    fun `data class equality`() {
        val metadata = createMetadata()
        val a = MetadataContentUrls(metadata = metadata)
        val b = MetadataContentUrls(metadata = metadata)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy produces new instance`() {
        val metadata1 = createMetadata("first")
        val metadata2 = createMetadata("second")
        val original = MetadataContentUrls(metadata = metadata1)
        val copied = original.copy(metadata = metadata2)
        assertEquals("second", copied.metadata.name)
    }
}
