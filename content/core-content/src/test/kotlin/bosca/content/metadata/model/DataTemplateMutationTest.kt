package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class DataTemplateMutationTest {

    private fun createMetadata(name: String = "Test") = Metadata(
        id = Uuid.random(),
        name = name,
        type = MetadataType.STANDARD,
        contentType = "application/json",
        contentLength = 0L,
        languageTag = "en",
        workflowStateId = "draft"
    )

    @Test
    fun `stores metadata field`() {
        val metadata = createMetadata("data-template")
        val mutation = DataTemplateMutation(metadata = metadata)
        assertEquals(metadata, mutation.metadata)
        assertEquals("data-template", mutation.metadata.name)
    }

    @Test
    fun `data class equality`() {
        val metadata = createMetadata()
        val a = DataTemplateMutation(metadata = metadata)
        val b = DataTemplateMutation(metadata = metadata)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy produces new instance with updated metadata`() {
        val original = DataTemplateMutation(metadata = createMetadata("original"))
        val newMetadata = createMetadata("updated")
        val copied = original.copy(metadata = newMetadata)
        assertEquals("updated", copied.metadata.name)
    }
}
