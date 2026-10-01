package bosca.content.metadata.model

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class MetadataSupplementaryContentTest {

    private fun createMetadata() = Metadata(
        id = Uuid.random(),
        name = "Test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 100L,
        languageTag = "en",
        workflowStateId = "draft"
    )

    private fun createSupplementary(key: String = "supp-key") = MetadataSupplementary(
        metadataId = Uuid.random(),
        key = key,
        name = "Supplementary",
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now()
    )

    @Test
    fun `stores metadata and supplementary`() {
        val metadata = createMetadata()
        val supplementary = createSupplementary()
        val content = MetadataSupplementaryContent(
            metadata = metadata,
            supplementary = supplementary
        )
        assertEquals(metadata, content.metadata)
        assertEquals(supplementary, content.supplementary)
    }

    @Test
    fun `data class equality`() {
        val metadata = createMetadata()
        val supplementary = createSupplementary()
        val a = MetadataSupplementaryContent(metadata = metadata, supplementary = supplementary)
        val b = MetadataSupplementaryContent(metadata = metadata, supplementary = supplementary)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality`() {
        val metadata = createMetadata()
        val supp1 = createSupplementary("key-1")
        val supp2 = createSupplementary("key-2")
        val a = MetadataSupplementaryContent(metadata = metadata, supplementary = supp1)
        val b = MetadataSupplementaryContent(metadata = metadata, supplementary = supp2)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies supplementary`() {
        val metadata = createMetadata()
        val supp1 = createSupplementary("original")
        val supp2 = createSupplementary("updated")
        val original = MetadataSupplementaryContent(metadata = metadata, supplementary = supp1)
        val copied = original.copy(supplementary = supp2)
        assertEquals(supp2, copied.supplementary)
        assertEquals(metadata, copied.metadata)
    }
}
