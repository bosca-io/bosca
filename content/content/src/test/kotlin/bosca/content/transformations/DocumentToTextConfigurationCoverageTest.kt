package bosca.content.transformations

import bosca.bible.Reference
import bosca.content.find.FindQueryInput
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.LocaleAwareDocument
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.documents.BulletListNode
import bosca.documents.ContainerAttributes
import bosca.documents.ContainerNode
import bosca.documents.Content
import bosca.documents.DocumentAttributes
import bosca.documents.DocumentNode
import bosca.documents.EmptyDocumentAttributes
import bosca.documents.HeadingAttributes
import bosca.documents.HeadingNode
import bosca.documents.ListItemNode
import bosca.documents.OrderedListNode
import bosca.documents.ParagraphNode
import bosca.documents.TaskItemNode
import bosca.documents.TaskListNode
import bosca.documents.TextAttributes
import bosca.documents.TextNode
import bosca.search.IndexStorageSystem
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the transformation classes and the private document->text rendering logic in
 * DocumentToTextConfiguration.kt. The DocumentToTextConfiguration data class itself is already
 * covered by DocumentToTextConfigurationTest; this file covers everything else in the source file.
 */
class DocumentToTextConfigurationCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val documentService = mockk<DocumentService>()
    private val bibleService = mockk<BibleService>()
    private val json = Json

    private val context = IndexStorageSystem(UUID.random(), "Index")

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    // ---------- helpers ----------

    private fun metadata(
        id: UUID = UUID.random(),
        languageTag: String = "en",
        parentId: UUID? = null,
    ) = Metadata(
        id = id,
        name = "Test Metadata",
        type = MetadataType.STANDARD,
        languageTag = languageTag,
        contentType = "text/plain",
        contentLength = 100,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        public = true,
        workflowStateId = "published",
        parentId = parentId,
    )

    private fun document(
        content: Content?,
        id: UUID = UUID.random(),
    ) = Document(
        metadataId = id,
        version = 1,
        title = "Doc Title",
        content = content,
    )

    private fun content(vararg nodes: DocumentNode): Content =
        Content(document = bosca.documents.Document(content = nodes.toList()))

    private fun text(value: String): TextNode =
        TextNode(attributes = TextAttributes(), text = value)

    private fun paragraph(vararg children: DocumentNode): ParagraphNode =
        ParagraphNode(content = children.toList())

    private fun heading(level: Int, vararg children: DocumentNode): HeadingNode =
        HeadingNode(attributes = HeadingAttributes(level = level), content = children.toList())

    private fun bible(id: UUID) = Bible(
        metadataId = id,
        version = 1,
        systemId = "sys",
        variant = "default",
        defaultVariant = true,
        name = "Bible",
        nameLocal = "Bible",
        description = "",
        abbreviation = "BIB",
        abbreviationLocal = "BIB",
        styles = JsonNull,
    )

    private fun book(usfm: String, nameLong: String?, nameShort: String?, abbreviation: String) =
        BibleBook(
            metadataId = UUID.random(),
            version = 1,
            variant = "default",
            usfm = usfm,
            nameShort = nameShort,
            nameLong = nameLong,
            abbreviation = abbreviation,
            sort = 0,
        )

    private fun chapter(components: kotlinx.serialization.json.JsonElement?) = BibleChapter(
        metadataId = UUID.random(),
        version = 1,
        variant = "default",
        bookUsfm = "GEN",
        usfm = "GEN.1",
        components = components,
        sort = 0,
    )

    // ---------- MetadataDocumentToTextTransformation ----------

    @Test
    fun `metadata transform renders document text with default config`() = runTest {
        val meta = metadata()
        val doc = document(content(paragraph(text("Hello world"))))
        coEvery { documentService.getDocument(meta.id, meta.version) } returns doc

        val transformer = MetadataDocumentToTextTransformation(metadataService, documentService, bibleService, json)
        val result = transformer.transform(context, meta)

        assertTrue(result.contains("Hello world"))
    }

    @Test
    fun `metadata transform returns empty string when document is null`() = runTest {
        val meta = metadata()
        coEvery { documentService.getDocument(meta.id, meta.version) } returns null

        val transformer = MetadataDocumentToTextTransformation(metadataService, documentService, bibleService, json)
        val result = transformer.transform(context, meta)

        assertEquals("", result)
    }

    @Test
    fun `metadata transform returns empty string when service throws`() = runTest {
        val meta = metadata()
        coEvery { documentService.getDocument(meta.id, meta.version) } throws RuntimeException("boom")

        val transformer = MetadataDocumentToTextTransformation(metadataService, documentService, bibleService, json)
        val result = transformer.transform(context, meta)

        assertEquals("", result)
    }

    @Test
    fun `metadata transform returns empty string when document content is null`() = runTest {
        val meta = metadata()
        coEvery { documentService.getDocument(meta.id, meta.version) } returns document(null)

        val transformer = MetadataDocumentToTextTransformation(metadataService, documentService, bibleService, json)
        val result = transformer.transform(context, meta)

        assertEquals("", result)
    }

    // ---------- IndexableDocumentToTextTransformation ----------

    @Test
    fun `indexable transform renders document text`() = runTest {
        val doc = document(content(paragraph(text("Indexed text"))))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)

        val transformer = IndexableDocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(context, item)

        assertTrue(result.contains("Indexed text"))
    }

    @Test
    fun `indexable transform returns empty when content null`() = runTest {
        val item = LocaleAwareDocument(Locale.ENGLISH, document(null))

        val transformer = IndexableDocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(context, item)

        assertEquals("", result)
    }

    // ---------- DocumentToTextTransformation ----------

    @Test
    fun `document transform renders document text with unit context`() = runTest {
        val doc = document(content(paragraph(text("Unit context text"))))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("Unit context text"))
    }

    // ---------- config branches in Content.asText ----------

    @Test
    fun `excludeTitle config skips the level-one heading`() = runTest {
        val doc = document(
            content(
                heading(1, text("The Title")),
                paragraph(text("Body content")),
            )
        )
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)
        val config = DocumentToTextConfiguration(includeTitle = false)

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json, config)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("Body content"))
        assertTrue(!result.contains("The Title"))
    }

    @Test
    fun `tts markup config emits title then body and pause markers`() = runTest {
        val doc = document(
            content(
                heading(1, text("Chapter Title")),
                paragraph(text("Some body text")),
            )
        )
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)
        val config = DocumentToTextConfiguration(includeTtsMarkup = true)

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json, config)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("Chapter Title"))
        assertTrue(result.contains("Some body text"))
        assertTrue(result.contains("[pause]"))
    }

    @Test
    fun `tts markup config with no title still renders body`() = runTest {
        // includeTitle=true (default) + tts, but no level-1 heading -> title firstOrNull() is null
        val doc = document(content(paragraph(text("No title body"))))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)
        val config = DocumentToTextConfiguration(includeTtsMarkup = true)

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json, config)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("No title body"))
    }

    @Test
    fun `tts markup split marker inserted when byte length exceeds threshold`() = runTest {
        // A very long text so appendText accumulates > 4000 bytes and inserts [split].
        val big = "x".repeat(5000)
        val doc = document(content(paragraph(text(big))))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)
        val config = DocumentToTextConfiguration(includeTtsMarkup = true)

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json, config)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("[split]"))
    }

    // ---------- node type branches in append(DocumentNode) ----------

    @Test
    fun `list node types render newlines and text with apostrophe normalization`() = runTest {
        val listItem = ListItemNode(content = listOf(paragraph(text("It’s here"))))
        val taskItem = TaskItemNode(attributes = bosca.documents.TaskItemAttributes(checked = false), content = listOf(paragraph(text("task"))))
        val doc = document(
            content(
                OrderedListNode(content = listOf(listItem)),
                BulletListNode(content = listOf(listItem)),
                TaskListNode(content = listOf(taskItem)),
                heading(2, text("H2")),
            )
        )
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(Unit, item)

        // apostrophe normalized from curly to straight
        assertTrue(result.contains("It's here"))
        assertTrue(result.contains("H2"))
    }

    @Test
    fun `heading with tts markup emits pause`() = runTest {
        val doc = document(content(heading(2, text("Section"))))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)
        val config = DocumentToTextConfiguration(includeTtsMarkup = true)

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json, config)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("Section"))
        assertTrue(result.contains("[pause]"))
    }

    // ---------- ContainerNode branches ----------

    @Test
    fun `container in excludeContainers is skipped`() = runTest {
        val container = ContainerNode(
            attributes = ContainerAttributes(name = "NOTES"),
            content = listOf(paragraph(text("hidden note"))),
        )
        val doc = document(content(container, paragraph(text("visible"))))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)
        val config = DocumentToTextConfiguration(excludeContainers = setOf("NOTES"))

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json, config)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("visible"))
        assertTrue(!result.contains("hidden note"))
    }

    @Test
    fun `container with metadataId and references resolves bible chapter via getById`() = runTest {
        val bibleId = UUID.random()
        val container = ContainerNode(
            attributes = ContainerAttributes(
                name = "BIBLE",
                metadataId = bibleId,
                references = listOf("GEN.1.1"),
            ),
        )
        val doc = document(content(container))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)

        val resolved = metadata(id = bibleId, languageTag = "en")
        coEvery { metadataService.getById(bibleId) } returns resolved
        val bib = bible(bibleId)
        coEvery { bibleService.getBible(resolved.id, resolved.version, null) } returns bib
        coEvery { bibleService.getChapter(bib, any()) } returns chapter(
            JsonObject(mapOf("text" to JsonPrimitive("In the beginning")))
        )
        coEvery { bibleService.getHuman(bib, any()) } returns "Genesis 1:1"

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("Genesis 1:1"))
        assertTrue(result.contains("In the beginning"))
    }

    @Test
    fun `container falls back to find when getById metadata locale mismatches`() = runTest {
        val bibleId = UUID.random()
        val container = ContainerNode(
            attributes = ContainerAttributes(
                name = "BIBLE",
                metadataId = bibleId,
                references = listOf("GEN.1.1"),
            ),
        )
        val doc = document(content(container))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)

        // getById returns a metadata whose languageTag doesn't match the locale -> takeIf yields null
        val mismatched = metadata(id = bibleId, languageTag = "de")
        coEvery { metadataService.getById(bibleId) } returns mismatched
        val found = metadata(languageTag = "en")
        coEvery { metadataService.find(any()) } returns listOf(found)
        val bib = bible(found.id)
        coEvery { bibleService.getBible(found.id, found.version, null) } returns bib
        coEvery { bibleService.getChapter(bib, any()) } returns chapter(
            JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive("verse text")))))
        )
        coEvery { bibleService.getHuman(bib, any()) } returns "Gen 1:1"

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("Gen 1:1"))
        assertTrue(result.contains("verse text"))
    }

    @Test
    fun `container with tts markup emits pause markers around chapter`() = runTest {
        val bibleId = UUID.random()
        val container = ContainerNode(
            attributes = ContainerAttributes(
                name = "BIBLE",
                metadataId = bibleId,
                references = listOf("GEN.1.1"),
            ),
        )
        val doc = document(content(container))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)
        val config = DocumentToTextConfiguration(includeTtsMarkup = true)

        val resolved = metadata(id = bibleId, languageTag = "en")
        coEvery { metadataService.getById(bibleId) } returns resolved
        val bib = bible(bibleId)
        coEvery { bibleService.getBible(resolved.id, resolved.version, null) } returns bib
        // components null -> the ?.let is skipped
        coEvery { bibleService.getChapter(bib, any()) } returns chapter(null)
        coEvery { bibleService.getHuman(bib, any()) } returns "Genesis 1:1"

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json, config)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("Genesis 1:1"))
        assertTrue(result.contains("[pause]"))
    }

    @Test
    fun `container continues when bible not found`() = runTest {
        val bibleId = UUID.random()
        val container = ContainerNode(
            attributes = ContainerAttributes(
                name = "BIBLE",
                metadataId = bibleId,
                references = listOf("GEN.1.1"),
            ),
        )
        val doc = document(content(container, paragraph(text("after"))))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)

        val resolved = metadata(id = bibleId, languageTag = "en")
        coEvery { metadataService.getById(bibleId) } returns resolved
        coEvery { bibleService.getBible(resolved.id, resolved.version, null) } returns null

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("after"))
    }

    @Test
    fun `container with no resolvable metadata skips chapter`() = runTest {
        val bibleId = UUID.random()
        val container = ContainerNode(
            attributes = ContainerAttributes(
                name = "BIBLE",
                metadataId = bibleId,
                references = listOf("GEN.1.1"),
            ),
        )
        val doc = document(content(container, paragraph(text("tail"))))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)

        coEvery { metadataService.getById(bibleId) } returns null
        coEvery { metadataService.find(any()) } returns emptyList()

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("tail"))
    }

    @Test
    fun `container without references is rendered as normal container`() = runTest {
        // metadataId set but references empty -> the inner references branch is not entered
        val container = ContainerNode(
            attributes = ContainerAttributes(name = "OTHER", metadataId = UUID.random(), references = emptyList()),
            content = listOf(paragraph(text("plain container body"))),
        )
        val doc = document(content(container))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("plain container body"))
    }

    @Test
    fun `container chapter lookup exception is caught and rendering continues`() = runTest {
        val bibleId = UUID.random()
        val container = ContainerNode(
            attributes = ContainerAttributes(
                name = "BIBLE",
                metadataId = bibleId,
                references = listOf("GEN.1.1"),
            ),
        )
        val doc = document(content(container, paragraph(text("recovered"))))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)

        val resolved = metadata(id = bibleId, languageTag = "en")
        coEvery { metadataService.getById(bibleId) } returns resolved
        val bib = bible(bibleId)
        coEvery { bibleService.getBible(resolved.id, resolved.version, null) } returns bib
        coEvery { bibleService.getChapter(bib, any()) } throws RuntimeException("no chapter")
        coEvery { bibleService.getHuman(bib, any()) } throws RuntimeException("no human")

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("recovered"))
    }

    // ---------- DocumentReferencesToListTransformation ----------

    @Test
    fun `references transform returns references from BIBLE_REFERENCES container`() = runTest {
        val meta = metadata()
        val container = ContainerNode(
            attributes = ContainerAttributes(name = "BIBLE_REFERENCES", references = listOf("GEN.1.1", "EXO.2.2")),
        )
        coEvery { documentService.getDocument(meta.id, meta.version) } returns document(content(container), meta.id)

        val transformer = DocumentReferencesToListTransformation(metadataService, documentService, json)
        val result = transformer.transform(context, meta)

        assertEquals(listOf(Reference("GEN.1.1"), Reference("EXO.2.2")), result)
    }

    @Test
    fun `references transform ignores non-reference containers and null references`() = runTest {
        val meta = metadata()
        val nonRef = ContainerNode(attributes = ContainerAttributes(name = "OTHER", references = listOf("GEN.1.1")))
        val nullRefs = ContainerNode(attributes = ContainerAttributes(name = "BIBLE_REFERENCES", references = null))
        coEvery { documentService.getDocument(meta.id, meta.version) } returns
            document(content(nonRef, nullRefs), meta.id)

        val transformer = DocumentReferencesToListTransformation(metadataService, documentService, json)
        val result = transformer.transform(context, meta)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `references transform falls back to parent when empty`() = runTest {
        val parentId = UUID.random()
        val meta = metadata(parentId = parentId)
        // child has no references
        coEvery { documentService.getDocument(meta.id, meta.version) } returns document(content(), meta.id)

        val parent = metadata(id = parentId)
        coEvery { metadataService.getById(parentId) } returns parent
        val container = ContainerNode(
            attributes = ContainerAttributes(name = "BIBLE_REFERENCES", references = listOf("PSA.23.1")),
        )
        coEvery { documentService.getDocument(parent.id, parent.version) } returns document(content(container), parent.id)

        val transformer = DocumentReferencesToListTransformation(metadataService, documentService, json)
        val result = transformer.transform(context, meta)

        assertEquals(listOf(Reference("PSA.23.1")), result)
    }

    @Test
    fun `references transform returns empty when parent missing`() = runTest {
        val parentId = UUID.random()
        val meta = metadata(parentId = parentId)
        coEvery { documentService.getDocument(meta.id, meta.version) } returns document(content(), meta.id)
        coEvery { metadataService.getById(parentId) } returns null

        val transformer = DocumentReferencesToListTransformation(metadataService, documentService, json)
        val result = transformer.transform(context, meta)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `references transform returns empty when no parent and no references`() = runTest {
        val meta = metadata(parentId = null)
        coEvery { documentService.getDocument(meta.id, meta.version) } returns document(content(), meta.id)

        val transformer = DocumentReferencesToListTransformation(metadataService, documentService, json)
        val result = transformer.transform(context, meta)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `references transform returns empty when document is null`() = runTest {
        val meta = metadata(parentId = null)
        coEvery { documentService.getDocument(meta.id, meta.version) } returns null

        val transformer = DocumentReferencesToListTransformation(metadataService, documentService, json)
        val result = transformer.transform(context, meta)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `references transform returns empty when getDocument throws`() = runTest {
        val meta = metadata(parentId = null)
        coEvery { documentService.getDocument(meta.id, meta.version) } throws RuntimeException("db down")

        val transformer = DocumentReferencesToListTransformation(metadataService, documentService, json)
        val result = transformer.transform(context, meta)

        assertTrue(result.isEmpty())
    }

    // ---------- ReferencesListToBookListTransformation ----------

    @Test
    fun `book list transform returns empty when no bible metadata found`() = runTest {
        coEvery { metadataService.find(any()) } returns emptyList()

        val transformer = ReferencesListToBookListTransformation(bibleService, metadataService)
        val result = transformer.transform(context, References(Locale.ENGLISH, listOf(Reference("GEN.1.1"))))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `book list transform returns empty when bible missing`() = runTest {
        val meta = metadata()
        coEvery { metadataService.find(any()) } returns listOf(meta)
        coEvery { bibleService.getBible(meta.id, meta.version, null) } returns null

        val transformer = ReferencesListToBookListTransformation(bibleService, metadataService)
        val result = transformer.transform(context, References(Locale.ENGLISH, listOf(Reference("GEN.1.1"))))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `book list transform maps references to book names with fallbacks`() = runTest {
        val meta = metadata()
        coEvery { metadataService.find(any()) } returns listOf(meta)
        val bib = bible(meta.id)
        coEvery { bibleService.getBible(meta.id, meta.version, null) } returns bib
        coEvery { bibleService.getBooks(bib) } returns listOf(
            book(usfm = "GEN", nameLong = "Genesis", nameShort = "Gen", abbreviation = "GN"),
            book(usfm = "EXO", nameLong = null, nameShort = "Exod", abbreviation = "EX"),
            book(usfm = "LEV", nameLong = null, nameShort = null, abbreviation = "LV"),
        )

        val transformer = ReferencesListToBookListTransformation(bibleService, metadataService)
        val result = transformer.transform(
            context,
            References(
                Locale.ENGLISH,
                listOf(
                    Reference("GEN.1.1"),
                    Reference("EXO.2.2"),
                    Reference("LEV.3.3"),
                    Reference("UNKNOWN.9.9"), // no matching book -> continue
                ),
            ),
        )

        assertEquals(setOf("Genesis", "Exod", "LV"), result.toSet())
    }

    // ---------- append(JsonElement) primitive/else branches via chapter components ----------

    @Test
    fun `chapter components with non-text primitive and nested structures are handled`() = runTest {
        val bibleId = UUID.random()
        val container = ContainerNode(
            attributes = ContainerAttributes(
                name = "BIBLE",
                metadataId = bibleId,
                references = listOf("GEN.1.1"),
            ),
        )
        val doc = document(content(container))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)

        val resolved = metadata(id = bibleId, languageTag = "en")
        coEvery { metadataService.getById(bibleId) } returns resolved
        val bib = bible(bibleId)
        coEvery { bibleService.getBible(resolved.id, resolved.version, null) } returns bib
        // components: object holding a non-text primitive (else branch) and an array with a text object
        val components = JsonObject(
            mapOf(
                "type" to JsonPrimitive("verse"),
                "children" to JsonArray(
                    listOf(
                        JsonObject(mapOf("text" to JsonPrimitive("nested verse"))),
                        JsonPrimitive(42),
                    )
                ),
            )
        )
        coEvery { bibleService.getChapter(bib, any()) } returns chapter(components)
        coEvery { bibleService.getHuman(bib, any()) } returns "Genesis 1:1"

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("nested verse"))
        // the non-text primitive "verse" and the numeric 42 are not emitted as text
        assertTrue(!result.contains("42"))
    }

    // ---------- References data class round trip ----------

    @Test
    fun `references data class equality and copy`() {
        val a = References(Locale.ENGLISH, listOf(Reference("GEN.1.1")))
        val b = References(Locale.ENGLISH, listOf(Reference("GEN.1.1")))
        assertEquals(a, b)
        val c = a.copy(references = emptyList())
        assertTrue(c.references.isEmpty())
        assertEquals(Locale.ENGLISH, c.locale)
    }

    // ---------- empty document (no config, whole-doc branch) ----------

    @Test
    fun `empty document renders empty string`() = runTest {
        val doc = document(content())
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(Unit, item)

        assertEquals("", result)
    }

    // ---------- document attributes sanity (EmptyDocumentAttributes) ----------

    @Test
    fun `content with default document attributes still renders`() = runTest {
        val attrs: DocumentAttributes = EmptyDocumentAttributes()
        val innerDoc = bosca.documents.Document(attributes = attrs, content = listOf(paragraph(text("attr body"))))
        val doc = document(Content(document = innerDoc))
        val item = LocaleAwareDocument(Locale.ENGLISH, doc)

        val transformer = DocumentToTextTransformation(metadataService, bibleService, json)
        val result = transformer.transform(Unit, item)

        assertTrue(result.contains("attr body"))
    }
}
