package bosca.design.model

import bosca.content.metadata.model.Document as MetadataDocument
import bosca.documents.*
import bosca.documents.marks.Bold
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class MetadataDocumentTest {
    private val id = Uuid.parse("00000000-0000-4000-8000-000000000001")

    @Test
    fun `metadata document encodes only its formatted body`() {
        val body = Document(content = listOf(HeadingNode(HeadingAttributes(level = 2), content = listOf(
            TextNode(text = "Article body", marks = listOf(Bold())),
        ))))
        val document = MetadataDocument(id, 7, id, 3, "Server title", Content(body))
        val value = documentValue(document)
        assertEquals(documentValue(body), value)
        assertEquals("doc", value.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("type", "content"), value.keys)
        assertFalse(value.toString().contains("Server title"))
        assertFalse(value.toString().contains(id.toString()))
        assertEquals(7, document.version)
        assertEquals(3, document.templateMetadataVersion)
    }

    @Test
    fun `missing and empty content initialize an editable paragraph`() {
        assertEquals(emptyDocument(), documentValue(MetadataDocument(id, 1, title = "Empty")))
        assertEquals(emptyDocument(), documentValue(MetadataDocument(id, 1, title = "Empty", content = Content())))
    }

    @Test
    fun `unsupported shared nodes remain intact for the native editor fallback`() {
        val body = Document(content = listOf(HtmlNode(html = "<aside>Custom content</aside>")))
        val document = MetadataDocument(id, 1, title = "Custom", content = Content(body))
        assertEquals(documentValue(body), documentValue(document))
    }
}
