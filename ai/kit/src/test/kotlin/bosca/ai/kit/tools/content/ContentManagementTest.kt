package bosca.ai.kit.tools.content

import bosca.ai.kit.tools.KitToolContext
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.documents.Content
import bosca.documents.HtmlNode
import bosca.documents.NodeConverter
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * `create_document` is the single owner of "make a bosca/v-document from a finished tiptap
 * Content". It takes the `Content` object directly (no JSON round-trip) and persists it. Here
 * we build a real `Content` the way the writer does (`NodeConverter`), then save it.
 */
class ContentManagementTest {

    private val auth = mockk<AuthenticationContext>(relaxed = true)

    @Test
    fun `create_document persists a Content object as a bosca v-document Metadata`() = runTest {
        val metadataService = mockk<MetadataService>()
        val created = mockk<Metadata>(relaxed = true)
        coEvery { created.id } returns Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        coEvery { created.version } returns 1
        coEvery { created.contentType } returns "bosca/v-document"
        val captured = slot<MetadataInput>()
        coEvery { metadataService.add(null, null, capture(captured)) } returns created

        // A finished tiptap Content object, built the way the writer produces it.
        val html = "<h1>God So Loved the World</h1>" +
            "<p>John 3:16<sup>a</sup> reveals God's love.</p>" +
            "<blockquote><p>For God so loved the world (JHN.3.16)</p></blockquote>"
        val content = Content(NodeConverter(mockk<BibleService>()).convertDocument(HtmlNode(html = html)))

        val result = withContext(KitToolContext(auth)) {
            CreateDocumentTool(metadataService).execute(CreateDocumentTool.Input(title = "God So Loved the World", content = content))
        }

        assertTrue(result.success, "create should succeed: ${result.error}")
        assertEquals("bosca/v-document", result.contentType)

        val input = captured.captured
        assertEquals("bosca/v-document", input.contentType)
        assertEquals("God So Loved the World", input.name)
        assertEquals("God So Loved the World", input.document?.title)
        assertNotNull(input.document?.content, "the tiptap Content object must be attached")
    }
}
