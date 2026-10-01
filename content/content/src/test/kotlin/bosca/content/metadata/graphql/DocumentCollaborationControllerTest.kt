package bosca.content.metadata.graphql

import bosca.content.metadata.model.DocumentCollaboration
import bosca.content.metadata.service.MetadataService
import bosca.serialization.OffsetDateTime
import io.mockk.clearAllMocks
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DocumentCollaborationControllerTest {

    private val service = mockk<MetadataService>()
    private val controller = DocumentCollaborationController(service)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `content returns collaboration content`() {
        val byteContent = "hello".toByteArray()
        val collaboration = DocumentCollaboration(
            version = 1,
            content = byteContent,
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now()
        )

        assertContentEquals(byteContent, controller.content(collaboration))
    }

    @Test
    fun `created returns collaboration created timestamp`() {
        val created = OffsetDateTime.now()
        val collaboration = DocumentCollaboration(
            version = 1,
            content = ByteArray(0),
            created = created,
            modified = OffsetDateTime.now()
        )

        assertEquals(created, controller.created(collaboration))
    }

    @Test
    fun `modified returns collaboration modified timestamp`() {
        val modified = OffsetDateTime.now()
        val collaboration = DocumentCollaboration(
            version = 1,
            content = ByteArray(0),
            created = OffsetDateTime.now(),
            modified = modified
        )

        assertEquals(modified, controller.modified(collaboration))
    }
}
