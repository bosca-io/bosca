package bosca.content.metadata.model

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class MetadataSupplementaryContentUrlsTest {

    private fun createMetadata() = Metadata(
        id = Uuid.random(),
        name = "Test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 100L,
        languageTag = "en",
        workflowStateId = "draft"
    )

    private fun createSupplementary() = MetadataSupplementary(
        metadataId = Uuid.random(),
        key = "supp-key",
        name = "Supplementary",
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now()
    )

    @Test
    fun `stores metadata and supplementary`() {
        val metadata = createMetadata()
        val supplementary = createSupplementary()
        val urls = MetadataSupplementaryContentUrls(
            metadata = metadata,
            supplementary = supplementary
        )
        assertEquals(metadata, urls.metadata)
        assertEquals(supplementary, urls.supplementary)
    }

    @Test
    fun `not a data class so uses reference equality`() {
        val metadata = createMetadata()
        val supplementary = createSupplementary()
        val a = MetadataSupplementaryContentUrls(metadata = metadata, supplementary = supplementary)
        val b = MetadataSupplementaryContentUrls(metadata = metadata, supplementary = supplementary)
        assert(a !== b)
    }
}
