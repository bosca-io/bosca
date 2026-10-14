package bosca.ai.kit.tools

import bosca.ai.kit.tools.bible.GetBibleVersesTool
import bosca.ai.kit.tools.document.ConvertToDocumentTool
import bosca.bible.BibleChapterContent
import bosca.bible.BibleContentUse
import bosca.bible.Chapter
import bosca.bible.Reference
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.components.Text
import bosca.bible.components.VerseEnd
import bosca.bible.components.VerseStart
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.coVerify
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
    fun `bible_get_verses reads provider text for AI context and reports a refused use`() = runTest {
        val metadataService = mockk<MetadataService>()
        val bibleService = mockk<BibleService>()
        val metadata = mockk<bosca.content.metadata.model.Metadata>(relaxed = true)
        val bible = mockk<bosca.content.metadata.model.Bible>(relaxed = true)
        val reference = Reference("JHN.3.16")
        coEvery { metadataService.getById(any()) } returns metadata
        coEvery { bibleService.getBible(any(), any(), null) } returns bible
        coEvery { bibleService.getReferences(bible, "John 3:16") } returns listOf(reference)
        coEvery { bibleService.getChapterContent(bible, reference, BibleContentUse.AI_CONTEXT) } returns BibleChapterContent(
            Chapter(Reference("JHN.3"), ComponentContainer(ContainerType.PARAGRAPH, listOf(
                VerseStart(Reference("JHN.3.16")), Text("For God so loved the world", null), VerseEnd(),
                VerseStart(Reference("JHN.3.17")), Text("Not to condemn", null), VerseEnd(),
            ), null)),
        )
        val tool = GetBibleVersesTool(bibleService, metadataService)
        val input = GetBibleVersesTool.Input(bibleId = "550e8400-e29b-41d4-a716-446655440000", reference = "John 3:16")

        val output = withContext(KitToolContext(auth)) { tool.execute(input) }

        assertEquals("[16] For God so loved the world", output.text)
        assertEquals("JHN.3.16", output.resolvedUsfm)
        coVerify(exactly = 0) { bibleService.getChapter(any<bosca.content.metadata.model.Bible>(), any()) }

        coEvery { bibleService.getChapterContent(bible, reference, BibleContentUse.AI_CONTEXT) } throws
            IllegalArgumentException("Bible source does not permit the requested use")
        val refused = withContext(KitToolContext(auth)) { tool.execute(input) }
        assertTrue(refused.text.isEmpty())
        assertTrue(refused.error?.contains("does not permit the requested use") == true, "expected the refusal, got ${refused.error}")
    }

    @Test
    fun `KitTool fails loudly when launched without a KitToolContext`() = runTest {
        val tool = ConvertToDocumentTool(mockk<BibleService>())
        val error = runCatching { tool.execute(ConvertToDocumentTool.Input(html = "<p>x</p>")) }.exceptionOrNull()
        assertTrue(error is IllegalStateException, "missing KitToolContext must surface, not silently default")
    }
}
