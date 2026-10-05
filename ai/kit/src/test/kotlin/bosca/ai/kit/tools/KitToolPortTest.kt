package bosca.ai.kit.tools

import bosca.ai.kit.tools.bible.GetBibleVersesTool
import bosca.ai.kit.tools.document.ConvertToDocumentTool
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the Koog tool port foundation: the [KitTool] base generates a tool descriptor,
 * resolves the caller from the ambient [KitToolContext], and drives the real ported logic.
 */
class KitToolPortTest {

    private val auth = mockk<AuthenticationContext>(relaxed = true)

    @Test
    fun `document_convert turns HTML into tiptap Content with a superscript mark`() = runTest {
        val tool = ConvertToDocumentTool(mockk<BibleService>())

        // Descriptor (JSON schema) generation must succeed and expose the tool name.
        assertEquals("document.convert", tool.descriptor.name)

        val html = "<h1>God So Loved</h1><p>Footnote<sup>2</sup> here.</p>"
        val output = withContext(KitToolContext(auth)) {
            tool.execute(ConvertToDocumentTool.Input(html = html))
        }

        assertTrue(output.success, "conversion should succeed: ${output.error}")
        // The title and body survive the HTML -> tiptap conversion.
        assertTrue(output.document.contains("God So Loved"), "document should contain the heading text")
        // <sup> is now an inline MARK (not a node) — proving the dom-shared fix end to end.
        assertTrue(output.document.contains("superscript"), "superscript should be present as a mark")
    }

    @Test
    fun `bible_get_verses resolves the caller and reports an unresolvable reference`() = runTest {
        val metadataService = mockk<MetadataService>()
        val bibleService = mockk<BibleService>()
        val metadata = mockk<bosca.content.metadata.model.Metadata>(relaxed = true)
        val bible = mockk<bosca.content.metadata.model.Bible>(relaxed = true)
        coEvery { metadataService.getById(any()) } returns metadata
        coEvery { bibleService.getBible(any(), any(), null) } returns bible
        coEvery { bibleService.getReferences(bible, "Nowhere 9:99") } returns emptyList()

        val tool = GetBibleVersesTool(bibleService, metadataService)
        val output = withContext(KitToolContext(auth)) {
            tool.execute(
                GetBibleVersesTool.Input(
                    bibleId = "550e8400-e29b-41d4-a716-446655440000",
                    reference = "Nowhere 9:99",
                ),
            )
        }

        assertTrue(output.text.isEmpty())
        assertTrue(output.error?.contains("Could not resolve") == true, "expected an unresolved-reference error, got ${output.error}")
    }

    @Test
    fun `KitTool fails loudly when launched without a KitToolContext`() = runTest {
        val tool = ConvertToDocumentTool(mockk<BibleService>())
        val error = runCatching { tool.execute(ConvertToDocumentTool.Input(html = "<p>x</p>")) }.exceptionOrNull()
        assertTrue(error is IllegalStateException, "missing KitToolContext must surface, not silently default")
    }
}
