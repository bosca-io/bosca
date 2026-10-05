package bosca.content.collection.model

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class CollectionTemplateMutationTest {

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
        val metadata = createMetadata("collection-template")
        val mutation = CollectionTemplateMutation(metadata = metadata)
        assertEquals(metadata, mutation.metadata)
        assertEquals("collection-template", mutation.metadata.name)
    }

    @Test
    fun `data class equality`() {
        val metadata = createMetadata()
        val a = CollectionTemplateMutation(metadata = metadata)
        val b = CollectionTemplateMutation(metadata = metadata)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy produces new instance with updated metadata`() {
        val original = CollectionTemplateMutation(metadata = createMetadata("original"))
        val newMetadata = createMetadata("updated")
        val copied = original.copy(metadata = newMetadata)
        assertEquals("updated", copied.metadata.name)
    }
}
