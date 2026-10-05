package bosca.documents

import bosca.documents.marks.Bold
import bosca.documents.marks.Italic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AllDocumentNodesTest {

    // ========================
    // ImageNode / ImageAttributes
    // ========================

    @Test
    fun `ImageNode stores all attributes`() {
        val attrs = ImageAttributes(
            src = "https://example.com/image.png",
            alt = "An image",
            title = "Image Title",
            metadataId = "meta-123"
        )
        val node = ImageNode(attributes = attrs)
        assertEquals("https://example.com/image.png", node.attributes.src)
        assertEquals("An image", node.attributes.alt)
        assertEquals("Image Title", node.attributes.title)
        assertEquals("meta-123", node.attributes.metadataId)
    }

    @Test
    fun `ImageAttributes defaults are all null`() {
        val attrs = ImageAttributes()
        assertNull(attrs.classes)
        assertNull(attrs.alt)
        assertNull(attrs.src)
        assertNull(attrs.title)
        assertNull(attrs.metadataId)
    }

    @Test
    fun `ImageAttributes withClasses returns new instance with classes set`() {
        val original = ImageAttributes(src = "img.png")
        val modified = original.withClasses("hero-image")
        assertEquals("hero-image", modified.classes)
        assertEquals("img.png", modified.src)
        assertNull(original.classes)
    }

    @Test
    fun `ImageNode default content is empty list`() {
        val node = ImageNode(attributes = ImageAttributes())
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `ImageNode default marks is empty list`() {
        val node = ImageNode(attributes = ImageAttributes())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `ImageNode content can hold child nodes`() {
        val textNode = TextNode(text = "caption")
        val node = ImageNode(
            attributes = ImageAttributes(src = "img.png"),
            content = listOf(textNode)
        )
        assertEquals(1, node.content.size)
        assertTrue(node.content[0] is TextNode)
    }

    // ========================
    // BulletListNode / BulletListAttributes
    // ========================

    @Test
    fun `BulletListNode default creation`() {
        val node = BulletListNode()
        assertNull(node.attributes.classes)
        assertTrue(node.content.isEmpty())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `BulletListAttributes withClasses returns new instance`() {
        val original = BulletListAttributes()
        val modified = original.withClasses("custom-list")
        assertEquals("custom-list", modified.classes)
        assertNull(original.classes)
    }

    @Test
    fun `BulletListNode with content`() {
        val items = listOf(ListItemNode(), ListItemNode())
        val node = BulletListNode(content = items)
        assertEquals(2, node.content.size)
    }

    // ========================
    // OrderedListNode / OrderedListAttributes
    // ========================

    @Test
    fun `OrderedListNode default creation`() {
        val node = OrderedListNode()
        assertNull(node.attributes.classes)
        assertNull(node.attributes.start)
        assertTrue(node.content.isEmpty())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `OrderedListAttributes stores start value`() {
        val attrs = OrderedListAttributes(start = 5)
        assertEquals(5, attrs.start)
    }

    @Test
    fun `OrderedListAttributes withClasses preserves start`() {
        val original = OrderedListAttributes(start = 3)
        val modified = original.withClasses("numbered")
        assertEquals("numbered", modified.classes)
        assertEquals(3, modified.start)
        assertNull(original.classes)
    }

    @Test
    fun `OrderedListNode with content and marks`() {
        val items = listOf(ListItemNode())
        val marks = listOf(Bold())
        val node = OrderedListNode(content = items, marks = marks)
        assertEquals(1, node.content.size)
        assertEquals(1, node.marks.size)
    }

    // ========================
    // TableNode / TableNodeAttributes
    // ========================

    @Test
    fun `TableNode default creation`() {
        val node = TableNode()
        assertNull(node.attributes.classes)
        assertTrue(node.content.isEmpty())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `TableNodeAttributes withClasses returns new instance`() {
        val original = TableNodeAttributes()
        val modified = original.withClasses("data-table")
        assertEquals("data-table", modified.classes)
        assertNull(original.classes)
    }

    @Test
    fun `TableNode with row content`() {
        val row = TableRowNode()
        val node = TableNode(content = listOf(row))
        assertEquals(1, node.content.size)
        assertTrue(node.content[0] is TableRowNode)
    }

    // ========================
    // TableRowNode / TableRowNodeAttributes
    // ========================

    @Test
    fun `TableRowNode default creation`() {
        val node = TableRowNode()
        assertNull(node.attributes.classes)
        assertTrue(node.content.isEmpty())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `TableRowNodeAttributes withClasses returns new instance`() {
        val original = TableRowNodeAttributes()
        val modified = original.withClasses("header-row")
        assertEquals("header-row", modified.classes)
        assertNull(original.classes)
    }

    @Test
    fun `TableRowNode with cell content`() {
        val cell = TableCellNode()
        val node = TableRowNode(content = listOf(cell))
        assertEquals(1, node.content.size)
        assertTrue(node.content[0] is TableCellNode)
    }

    // ========================
    // TableCellNode / TableCellNodeAttributes
    // ========================

    @Test
    fun `TableCellNode default creation with default colspan and rowspan`() {
        val node = TableCellNode()
        assertEquals(1, node.attributes.colspan)
        assertEquals(1, node.attributes.rowspan)
        assertNull(node.attributes.classes)
        assertTrue(node.content.isEmpty())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `TableCellNodeAttributes stores colspan and rowspan`() {
        val attrs = TableCellNodeAttributes(colspan = 3, rowspan = 2)
        assertEquals(3, attrs.colspan)
        assertEquals(2, attrs.rowspan)
    }

    @Test
    fun `TableCellNodeAttributes withClasses preserves colspan and rowspan`() {
        val original = TableCellNodeAttributes(colspan = 2, rowspan = 4)
        val modified = original.withClasses("highlight-cell")
        assertEquals("highlight-cell", modified.classes)
        assertEquals(2, modified.colspan)
        assertEquals(4, modified.rowspan)
        assertNull(original.classes)
    }

    @Test
    fun `TableCellNode with content`() {
        val paragraph = ParagraphNode()
        val node = TableCellNode(content = listOf(paragraph))
        assertEquals(1, node.content.size)
    }

    // ========================
    // BibleNode / BibleAttributes / BibleReference
    // ========================

    @Test
    fun `BibleNode default creation`() {
        val node = BibleNode()
        assertNull(node.attributes.classes)
        assertTrue(node.attributes.references.isEmpty())
        assertTrue(node.content.isEmpty())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `BibleAttributes withClasses preserves references`() {
        val refs = listOf("PSA.23.1")
        val original = BibleAttributes(references = refs)
        val modified = original.withClasses("bible-verse")
        assertEquals("bible-verse", modified.classes)
        assertEquals(1, modified.references.size)
        assertEquals("PSA.23.1", modified.references[0])
        assertNull(original.classes)
    }

    // ========================
    // ListItemNode / ListItemAttributes
    // ========================

    @Test
    fun `ListItemNode default creation`() {
        val node = ListItemNode()
        assertNull(node.attributes.classes)
        assertTrue(node.content.isEmpty())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `ListItemAttributes withClasses returns new instance`() {
        val original = ListItemAttributes()
        val modified = original.withClasses("list-item-active")
        assertEquals("list-item-active", modified.classes)
        assertNull(original.classes)
    }

    @Test
    fun `ListItemNode with nested content`() {
        val paragraph = ParagraphNode()
        val node = ListItemNode(content = listOf(paragraph))
        assertEquals(1, node.content.size)
        assertTrue(node.content[0] is ParagraphNode)
    }

    // ========================
    // HorizontalRuleNode
    // ========================

    @Test
    fun `HorizontalRuleNode default creation`() {
        val node = HorizontalRuleNode()
        assertTrue(node.attributes is EmptyDocumentAttributes)
        assertTrue(node.content.isEmpty())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `HorizontalRuleNode with marks`() {
        val marks = listOf(Bold(), Italic())
        val node = HorizontalRuleNode(marks = marks)
        assertEquals(2, node.marks.size)
    }

    // ========================
    // HardBreakNode
    // ========================

    @Test
    fun `HardBreakNode default creation`() {
        val node = HardBreakNode()
        assertTrue(node.attributes is EmptyDocumentAttributes)
        assertTrue(node.content.isEmpty())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `HardBreakNode with marks`() {
        val marks = listOf(Bold())
        val node = HardBreakNode(marks = marks)
        assertEquals(1, node.marks.size)
    }

    // ========================
    // ContainerNode / ContainerAttributes
    // ========================

    @Test
    fun `ContainerNode stores all attributes`() {
        val id = Uuid.random()
        val attrs = ContainerAttributes(
            name = "main-container",
            metadataId = id,
            references = listOf("ref1", "ref2"),
            renderer = "custom-renderer"
        )
        val node = ContainerNode(attributes = attrs)
        assertEquals("main-container", node.attributes.name)
        assertEquals(id, node.attributes.metadataId)
        assertEquals(listOf("ref1", "ref2"), node.attributes.references)
        assertEquals("custom-renderer", node.attributes.renderer)
    }

    @Test
    fun `ContainerAttributes defaults`() {
        val attrs = ContainerAttributes()
        assertNull(attrs.classes)
        assertNull(attrs.name)
        assertNull(attrs.metadataId)
        assertNull(attrs.references)
        assertNull(attrs.renderer)
    }

    @Test
    fun `ContainerAttributes withClasses preserves other fields`() {
        val id = Uuid.random()
        val original = ContainerAttributes(name = "box", metadataId = id, renderer = "r1")
        val modified = original.withClasses("container-class")
        assertEquals("container-class", modified.classes)
        assertEquals("box", modified.name)
        assertEquals(id, modified.metadataId)
        assertEquals("r1", modified.renderer)
        assertNull(original.classes)
    }

    @Test
    fun `ContainerAttributes withReferences sets metadataId and references`() {
        val id = Uuid.random()
        val original = ContainerAttributes(name = "box")
        val modified = original.withReferences(id, listOf("ref-a", "ref-b"))
        assertEquals(id, modified.metadataId)
        assertEquals(listOf("ref-a", "ref-b"), modified.references)
        assertEquals("box", modified.name)
    }

    @Test
    fun `ContainerAttributes withReferences clears when references is null`() {
        val id = Uuid.random()
        val original = ContainerAttributes(metadataId = id, references = listOf("ref1"))
        val modified = original.withReferences(Uuid.random(), null)
        assertNull(modified.metadataId)
        assertNull(modified.references)
    }

    @Test
    fun `ContainerAttributes withReferences clears when references is empty`() {
        val id = Uuid.random()
        val original = ContainerAttributes(metadataId = id, references = listOf("ref1"))
        val modified = original.withReferences(Uuid.random(), emptyList())
        assertNull(modified.metadataId)
        assertNull(modified.references)
    }

    @Test
    fun `ContainerNode default content and marks are empty`() {
        val node = ContainerNode(attributes = ContainerAttributes())
        assertTrue(node.content.isEmpty())
        assertTrue(node.marks.isEmpty())
    }

    // ========================
    // TextNode / TextAttributes
    // ========================

    @Test
    fun `TextNode stores text`() {
        val node = TextNode(text = "Hello, world!")
        assertEquals("Hello, world!", node.text)
    }

    @Test
    fun `TextNode default attributes have null classes and transform`() {
        val node = TextNode(text = "test")
        assertNull(node.attributes.classes)
        assertNull(node.attributes.transform)
    }

    @Test
    fun `TextAttributes stores transform`() {
        val attrs = TextAttributes(transform = "uppercase")
        assertEquals("uppercase", attrs.transform)
    }

    @Test
    fun `TextAttributes withClasses preserves transform`() {
        val original = TextAttributes(transform = "lowercase")
        val modified = original.withClasses("text-highlight")
        assertEquals("text-highlight", modified.classes)
        assertEquals("lowercase", modified.transform)
        assertNull(original.classes)
    }

    @Test
    fun `TextNode default content is empty`() {
        val node = TextNode(text = "sample")
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `TextNode default marks is empty`() {
        val node = TextNode(text = "sample")
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `TextNode with marks`() {
        val node = TextNode(text = "bold text", marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
        assertTrue(node.marks[0] is Bold)
    }

    // ========================
    // HtmlNode
    // ========================

    @Test
    fun `HtmlNode stores html content`() {
        val node = HtmlNode(html = "<div>Hello</div>")
        assertEquals("<div>Hello</div>", node.html)
    }

    @Test
    fun `HtmlNode default attributes are EmptyDocumentAttributes`() {
        val node = HtmlNode(html = "<p>test</p>")
        assertTrue(node.attributes is EmptyDocumentAttributes)
    }

    @Test
    fun `HtmlNode default content is empty`() {
        val node = HtmlNode(html = "<br/>")
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `HtmlNode default marks is empty`() {
        val node = HtmlNode(html = "<br/>")
        assertTrue(node.marks.isEmpty())
    }

    // ========================
    // BlockquoteNode
    // ========================

    @Test
    fun `BlockquoteNode default creation`() {
        val node = BlockquoteNode()
        assertTrue(node.attributes is EmptyDocumentAttributes)
        assertTrue(node.content.isEmpty())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `BlockquoteNode with content`() {
        val paragraph = ParagraphNode()
        val node = BlockquoteNode(content = listOf(paragraph))
        assertEquals(1, node.content.size)
        assertTrue(node.content[0] is ParagraphNode)
    }

    @Test
    fun `BlockquoteNode with marks`() {
        val node = BlockquoteNode(marks = listOf(Italic()))
        assertEquals(1, node.marks.size)
    }
}
