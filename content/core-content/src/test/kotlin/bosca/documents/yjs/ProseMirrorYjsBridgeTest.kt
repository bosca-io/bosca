package bosca.documents.yjs

import bosca.content.metadata.model.BibleReference
import bosca.documents.BibleAttributes
import bosca.documents.BibleNode
import bosca.documents.BulletListNode
import bosca.documents.CodeBlockAttributes
import bosca.documents.CodeBlockNode
import bosca.documents.ContainerAttributes
import bosca.documents.ContainerNode
import bosca.documents.Content
import bosca.documents.Document
import bosca.documents.HardBreakNode
import bosca.documents.HeadingAttributes
import bosca.documents.HeadingNode
import bosca.documents.ImageAttributes
import bosca.documents.ImageNode
import bosca.documents.ListItemNode
import bosca.documents.MentionAttributes
import bosca.documents.MentionNode
import bosca.documents.ParagraphNode
import bosca.documents.TableCellNode
import bosca.documents.TableHeaderNode
import bosca.documents.TableNode
import bosca.documents.TableRowNode
import bosca.documents.TextNode
import bosca.documents.marks.Bold
import bosca.documents.marks.Code
import bosca.documents.marks.Italic
import bosca.documents.marks.Link
import bosca.documents.marks.LinkAttributes
import bosca.documents.marks.Strike
import bosca.documents.marks.Underline
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProseMirrorYjsBridgeTest {

    @Test
    fun `paragraph round-trips through Yjs`() {
        val original = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "Hello world")))
        )))
        val update = ProseMirrorYjsBridge.toYDocUpdate(original)
        assertTrue(update.isNotEmpty(), "encoded update should be non-empty")
        val decoded = ProseMirrorYjsBridge.toContent(update)
        val para = decoded.document.content.firstOrNull()
        assertTrue(para is ParagraphNode, "expected ParagraphNode, got ${para?.let { it::class.simpleName }}")
        assertEquals("Hello world", (para.content[0] as TextNode).text)
    }

    @Test
    fun `heading round-trips with level attribute`() {
        val original = Content(document = Document(content = listOf(
            HeadingNode(attributes = HeadingAttributes(level = 2), content = listOf(TextNode(text = "Title")))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val heading = decoded.document.content.firstOrNull() as? HeadingNode
        assertNotNull(heading, "heading present")
        assertEquals(2, heading.attributes.level)
        assertEquals("Title", (heading.content[0] as TextNode).text)
    }

    @Test
    fun `bold mark round-trips as text mark`() {
        val original = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "loud", marks = listOf(Bold()))))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val text = (decoded.document.content[0] as ParagraphNode).content[0] as TextNode
        assertEquals("loud", text.text)
        assertTrue(text.marks.any { it is Bold }, "bold mark preserved")
    }

    @Test
    fun `link mark round-trips with attrs`() {
        val original = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(
                TextNode(text = "click", marks = listOf(Link(attributes = LinkAttributes(href = "https://x.test"))))
            ))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val text = (decoded.document.content[0] as ParagraphNode).content[0] as TextNode
        val link = text.marks.firstOrNull { it is Link } as? Link
        assertNotNull(link, "link mark preserved")
        assertEquals("https://x.test", link.attributes?.href)
    }

    @Test
    fun `mixed marks on adjacent text segments stay separate`() {
        val original = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(
                TextNode(text = "bold", marks = listOf(Bold())),
                TextNode(text = " then "),
                TextNode(text = "italic", marks = listOf(Italic())),
            ))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val children = (decoded.document.content[0] as ParagraphNode).content
        // The Yjs delta encoding should produce three distinct text segments by mark.
        assertEquals(3, children.size, "three segments expected, got ${children.map { (it as? TextNode)?.text to (it as? TextNode)?.marks }}")
        assertEquals("bold", (children[0] as TextNode).text)
        assertTrue((children[0] as TextNode).marks.any { it is Bold })
        assertEquals(" then ", (children[1] as TextNode).text)
        assertTrue((children[1] as TextNode).marks.isEmpty())
        assertEquals("italic", (children[2] as TextNode).text)
        assertTrue((children[2] as TextNode).marks.any { it is Italic })
    }

    @Test
    fun `nested list round-trips`() {
        val original = Content(document = Document(content = listOf(
            BulletListNode(content = listOf(
                ListItemNode(content = listOf(
                    ParagraphNode(content = listOf(TextNode(text = "outer"))),
                    BulletListNode(content = listOf(
                        ListItemNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "inner")))))
                    )),
                )),
            ))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val outer = decoded.document.content.firstOrNull() as? BulletListNode
        assertNotNull(outer, "outer bullet list present")
        val outerItem = outer.content[0] as ListItemNode
        assertEquals("outer", extractText(outerItem.content[0]))
        val inner = outerItem.content.firstOrNull { it is BulletListNode } as? BulletListNode
        assertNotNull(inner, "inner list nested under outer item")
        assertEquals("inner", extractText((inner.content[0] as ListItemNode).content[0]))
    }

    @Test
    fun `table round-trips`() {
        val original = Content(document = Document(content = listOf(
            TableNode(content = listOf(
                TableRowNode(content = listOf(
                    TableHeaderNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "h1"))))),
                    TableHeaderNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "h2"))))),
                )),
                TableRowNode(content = listOf(
                    TableCellNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "a"))))),
                    TableCellNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "b"))))),
                )),
            ))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val table = decoded.document.content.firstOrNull() as? TableNode
        assertNotNull(table, "table present")
        val rows = table.content.filterIsInstance<TableRowNode>()
        assertEquals(2, rows.size, "header + body row")
        assertTrue(rows[0].content.all { it is TableHeaderNode }, "first row is headers")
        assertEquals("h1", extractText(rows[0].content[0]))
        assertEquals("a", extractText(rows[1].content[0]))
    }

    @Test
    fun `empty Yjs state decodes to empty document`() {
        val decoded = ProseMirrorYjsBridge.toContent(ByteArray(0))
        assertEquals(0, decoded.document.content.size, "empty state -> empty document")
    }

    @Test
    fun `string-shaped attribute values stay strings through round-trip`() {
        // Regression for the original bridge encoding which stored attribute values
        // verbatim as Yjs strings, then re-parsed them as JSON on the way back.
        // A literal string like "42" or "true" or "null" used to round-trip as the
        // corresponding number / boolean / null, silently changing the field's type
        // and breaking deserialization for downstream attribute schemas.
        val containerId = bosca.serialization.UUID.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val original = Content(document = Document(content = listOf(
            ContainerNode(
                attributes = ContainerAttributes(
                    name = "42",
                    metadataId = containerId,
                    references = listOf("true", "null", "{}"),
                    renderer = "[1,2,3]",
                ),
                content = listOf(ParagraphNode(content = listOf(TextNode(text = "x")))),
            )
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val container = decoded.document.content.firstOrNull() as? ContainerNode
        assertNotNull(container, "container preserved")
        // Each of these would have been silently mangled by the old encoding.
        assertEquals("42", container.attributes.name, "string \"42\" must not become number 42")
        assertEquals(listOf("true", "null", "{}"), container.attributes.references, "JSON-keyword strings must stay strings")
        assertEquals("[1,2,3]", container.attributes.renderer, "JSON-array-shaped string must stay a string")
        assertEquals(containerId, container.attributes.metadataId, "UUID survives")
    }

    @Test
    fun `unrecognized node type surfaces as a SerializationException`() {
        // A Yjs state that contains a YXmlElement whose tag isn't in the DocumentNode
        // polymorphic registry must NOT silently decode to an empty document. The bridge
        // used to swallow the failure; that hid real model-evolution bugs and lost user
        // content. The current contract: surface the failure to the caller.
        val update = run {
            val doc = yks.utils.Doc()
            val frag = doc.getXmlFragment("default")
            frag.insert(0, listOf(yks.types.YXmlElement("notARealNodeType")))
            yks.utils.encodeStateAsUpdate(doc)
        }
        val ex = kotlin.runCatching { ProseMirrorYjsBridge.toContent(update) }.exceptionOrNull()
        assertNotNull(ex, "expected an exception, but decode succeeded")
        assertTrue(
            ex is kotlinx.serialization.SerializationException,
            "expected SerializationException, got ${ex::class.simpleName}",
        )
    }

    @Test
    fun `mergeDocument keeps non-default Yjs maps intact`() {
        // First, build a Yjs Doc with a "default" fragment AND an unrelated map
        // that callers (collections-dirty flag, awareness, etc.) would rely on.
        val firstUpdate = run {
            val doc = yks.utils.Doc()
            val frag = doc.getXmlFragment("default")
            val p = yks.types.YXmlElement("paragraph")
            frag.insert(0, listOf(p))
            val txt = yks.types.YXmlText()
            p.insert(0, listOf(txt))
            txt.insert(0, "old text")
            // unrelated map
            val map = doc.getMap("collections")
            map.set("|__dirty__|", "true")
            yks.utils.encodeStateAsUpdate(doc)
        }

        val replacement = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "new text")))
        )))

        val merged = ProseMirrorYjsBridge.mergeDocument(firstUpdate, replacement)

        // Apply the merged update to a fresh doc and inspect both fragments.
        val verifyDoc = yks.utils.Doc()
        yks.utils.applyUpdate(verifyDoc, merged)
        val frag = verifyDoc.getXmlFragment("default")
        val resultText = (frag.toArray()
            .filterIsInstance<yks.types.YXmlElement>()
            .firstOrNull()
            ?.toArray()
            ?.firstOrNull() as? yks.types.YXmlText)
            ?.toString()
        assertEquals("new text", resultText, "default fragment was updated with the new content")
        val map = verifyDoc.getMap("collections")
        assertEquals("true", map.get("|__dirty__|")?.toString(), "non-default map data preserved through mergeDocument")
    }

    @Test
    fun `mergeDocument with identical content emits no Yjs operations`() {
        // The defining property of a real merge: when nothing has changed, nothing in
        // the underlying CRDT moves. Two snapshots of the doc state taken before and
        // after a no-op merge must be byte-identical, otherwise unchanged subtrees
        // wouldn't actually be unchanged from the editor's perspective and concurrent
        // in-flight edits could be disturbed by spurious deletions/inserts.
        val original = Content(document = Document(content = listOf(
            HeadingNode(attributes = HeadingAttributes(level = 1), content = listOf(TextNode(text = "Title"))),
            ParagraphNode(content = listOf(TextNode(text = "Body."))),
        )))
        val baseline = ProseMirrorYjsBridge.toYDocUpdate(original)
        val merged = ProseMirrorYjsBridge.mergeDocument(baseline, original)

        // Compare the resulting state vectors and updates: identical state → identical
        // serialized update.
        val baselineDoc = yks.utils.Doc()
        yks.utils.applyUpdate(baselineDoc, baseline)
        val mergedDoc = yks.utils.Doc()
        yks.utils.applyUpdate(mergedDoc, merged)
        assertEquals(
            yks.utils.encodeStateVector(baselineDoc).toList(),
            yks.utils.encodeStateVector(mergedDoc).toList(),
            "no-op merge must not bump any clock — state vectors should match exactly",
        )
    }

    @Test
    fun `mergeDocument preserves item identity when a middle section is removed`() {
        // Old: [A, B, C, D]  New: [A, C, D]
        // A naive position-by-position diff would delete-and-reinsert A→C→D after the
        // removal of B, churning every item from position 1 onward. The LCS-based diff
        // should only delete B and keep A, C, D as their original Yjs items.
        val before = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "A"))),
            ParagraphNode(content = listOf(TextNode(text = "B"))),
            ParagraphNode(content = listOf(TextNode(text = "C"))),
            ParagraphNode(content = listOf(TextNode(text = "D"))),
        )))
        val after = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "A"))),
            ParagraphNode(content = listOf(TextNode(text = "C"))),
            ParagraphNode(content = listOf(TextNode(text = "D"))),
        )))

        val baseline = ProseMirrorYjsBridge.toYDocUpdate(before)
        val baselineDoc = yks.utils.Doc()
        yks.utils.applyUpdate(baselineDoc, baseline)
        val baselineFrag = baselineDoc.getXmlFragment("default")
        val baselineIds = listOf(0, 2, 3).map { (baselineFrag.get(it) as yks.types.YXmlElement).item?.id }

        val mergedBytes = ProseMirrorYjsBridge.mergeDocument(baseline, after)
        val mergedDoc = yks.utils.Doc()
        yks.utils.applyUpdate(mergedDoc, mergedBytes)
        val mergedFrag = mergedDoc.getXmlFragment("default")
        val mergedIds = (0 until mergedFrag.length).map { (mergedFrag.get(it) as yks.types.YXmlElement).item?.id }

        assertEquals(3, mergedFrag.length, "result has the three retained paragraphs")
        assertEquals(baselineIds, mergedIds, "A, C, D must keep their original item identities through the deletion of B")
        // And the structural decode is what we asked for.
        val decoded = ProseMirrorYjsBridge.toContent(mergedBytes)
        assertEquals(listOf("A", "C", "D"), decoded.document.content.map { extractText(it) })
    }

    @Test
    fun `mergeDocument preserves item identity when a section is inserted in the middle`() {
        // Old: [A, C, D]  New: [A, B, C, D]
        // Naive diff would delete-and-reinsert C→D after the insertion of B at position 1,
        // churning every item from position 1 onward. The LCS-based diff should insert
        // only B and leave A, C, D untouched.
        val before = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "A"))),
            ParagraphNode(content = listOf(TextNode(text = "C"))),
            ParagraphNode(content = listOf(TextNode(text = "D"))),
        )))
        val after = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "A"))),
            ParagraphNode(content = listOf(TextNode(text = "B"))),
            ParagraphNode(content = listOf(TextNode(text = "C"))),
            ParagraphNode(content = listOf(TextNode(text = "D"))),
        )))

        val baseline = ProseMirrorYjsBridge.toYDocUpdate(before)
        val baselineDoc = yks.utils.Doc()
        yks.utils.applyUpdate(baselineDoc, baseline)
        val baselineFrag = baselineDoc.getXmlFragment("default")
        val baselineIds = (0 until baselineFrag.length).map { (baselineFrag.get(it) as yks.types.YXmlElement).item?.id }

        val mergedBytes = ProseMirrorYjsBridge.mergeDocument(baseline, after)
        val mergedDoc = yks.utils.Doc()
        yks.utils.applyUpdate(mergedDoc, mergedBytes)
        val mergedFrag = mergedDoc.getXmlFragment("default")
        // After: [A(kept), B(new), C(kept), D(kept)]. Compare item IDs at positions 0, 2, 3.
        val keptIds = listOf(0, 2, 3).map { (mergedFrag.get(it) as yks.types.YXmlElement).item?.id }

        assertEquals(4, mergedFrag.length, "result has the four paragraphs")
        assertEquals(baselineIds, keptIds, "A, C, D must keep their original item identities across the insertion of B")
        val decoded = ProseMirrorYjsBridge.toContent(mergedBytes)
        assertEquals(listOf("A", "B", "C", "D"), decoded.document.content.map { extractText(it) })
    }

    @Test
    fun `mergeDocument leaves unchanged top-level subtrees as the same Yjs items`() {
        // Concurrent-edit preservation: change the second paragraph but leave the
        // first untouched. The first paragraph's underlying YXmlElement must survive
        // the merge as the same item (not deleted-then-reinserted), so an editor that
        // had buffered a local edit against it would still see its baseline intact.
        // We verify the property by checking that the YXmlElement for the unchanged
        // paragraph carries the same item identity (origin clock) before and after.
        val before = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "stable"))),
            ParagraphNode(content = listOf(TextNode(text = "old"))),
        )))
        val after = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "stable"))),
            ParagraphNode(content = listOf(TextNode(text = "new"))),
        )))

        val baseline = ProseMirrorYjsBridge.toYDocUpdate(before)

        val baselineDoc = yks.utils.Doc()
        yks.utils.applyUpdate(baselineDoc, baseline)
        val baselineFirstId = (baselineDoc.getXmlFragment("default").get(0) as yks.types.YXmlElement)
            .item?.id

        val mergedBytes = ProseMirrorYjsBridge.mergeDocument(baseline, after)
        val mergedDoc = yks.utils.Doc()
        yks.utils.applyUpdate(mergedDoc, mergedBytes)
        val mergedFirstId = (mergedDoc.getXmlFragment("default").get(0) as yks.types.YXmlElement)
            .item?.id

        assertEquals(
            baselineFirstId,
            mergedFirstId,
            "the unchanged first paragraph should keep its underlying Yjs item identity through the merge",
        )

        // And the structural result is the new content.
        val decoded = ProseMirrorYjsBridge.toContent(mergedBytes)
        assertEquals("stable", extractText(decoded.document.content[0]))
        assertEquals("new", extractText(decoded.document.content[1]))
    }

    @Test
    fun `mention node round-trips with id and label`() {
        val original = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(
                TextNode(text = "Hi "),
                MentionNode(attributes = MentionAttributes(
                    id = "abc-123",
                    label = "Alice",
                    entityType = "profile",
                )),
            ))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val mention = (decoded.document.content[0] as ParagraphNode).content
            .firstOrNull { it is MentionNode } as? MentionNode
        assertNotNull(mention, "mention preserved")
        assertEquals("abc-123", mention.attributes.id)
        assertEquals("Alice", mention.attributes.label)
        assertEquals("profile", mention.attributes.entityType)
    }

    @Test
    fun `image node round-trips as block`() {
        val original = Content(document = Document(content = listOf(
            ImageNode(attributes = ImageAttributes(
                src = "https://example.com/x.png",
                alt = "an example",
                title = "Example Title",
            ))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val image = decoded.document.content.firstOrNull() as? ImageNode
        assertNotNull(image, "image preserved")
        assertEquals("https://example.com/x.png", image.attributes.src)
        assertEquals("an example", image.attributes.alt)
        assertEquals("Example Title", image.attributes.title)
    }

    @Test
    fun `code block round-trips with language attribute`() {
        val original = Content(document = Document(content = listOf(
            CodeBlockNode(
                attributes = CodeBlockAttributes(language = "kotlin"),
                content = listOf(TextNode(text = "fun main() {}")),
            )
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val block = decoded.document.content.firstOrNull() as? CodeBlockNode
        assertNotNull(block, "code block preserved")
        assertEquals("kotlin", block.attributes.language)
        assertEquals("fun main() {}", (block.content[0] as TextNode).text)
    }

    @Test
    fun `bible node round-trips with reference list attribute`() {
        // BibleAttributes carries a List<BibleReference>; the bridge JSON-encodes complex
        // attributes as strings, then re-parses them on the way back. This exercises that path.
        val original = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(
                TextNode(text = "See "),
                BibleNode(attributes = BibleAttributes(references = listOf("GEN.1.1", "JHN.3.16"))),
            ))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val bible = (decoded.document.content[0] as ParagraphNode).content
            .firstOrNull { it is BibleNode } as? BibleNode
        assertNotNull(bible, "bible node preserved")
        assertEquals(2, bible.attributes.references.size)
        assertEquals("GEN.1.1", bible.attributes.references[0])
        assertEquals("JHN.3.16", bible.attributes.references[1])
    }

    @Test
    fun `container node round-trips with UUID and references`() {
        // ContainerAttributes carries an @Contextual UUID and a List<String> — the two
        // attribute shapes that most often trip up serialization wiring.
        val containerId = UUID.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val original = Content(document = Document(content = listOf(
            ContainerNode(
                attributes = ContainerAttributes(
                    name = "intro",
                    metadataId = containerId,
                    references = listOf("ref-a", "ref-b"),
                    renderer = "html",
                ),
                content = listOf(
                    ParagraphNode(content = listOf(TextNode(text = "inside container"))),
                ),
            )
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val container = decoded.document.content.firstOrNull() as? ContainerNode
        assertNotNull(container, "container preserved: ${decoded.document.content}")
        assertEquals("intro", container.attributes.name)
        assertEquals(containerId, container.attributes.metadataId)
        assertEquals(listOf("ref-a", "ref-b"), container.attributes.references)
        assertEquals("html", container.attributes.renderer)
        val inner = (container.content[0] as ParagraphNode).content[0] as TextNode
        assertEquals("inside container", inner.text)
    }

    @Test
    fun `strike and underline marks round-trip`() {
        val original = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(
                TextNode(text = "old", marks = listOf(Strike())),
                TextNode(text = " "),
                TextNode(text = "underlined", marks = listOf(Underline())),
            ))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val children = (decoded.document.content[0] as ParagraphNode).content
        assertTrue((children[0] as TextNode).marks.any { it is Strike }, "strike preserved")
        assertTrue((children[2] as TextNode).marks.any { it is Underline }, "underline preserved")
    }

    @Test
    fun `inline code mark round-trips`() {
        val original = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "x = 1", marks = listOf(Code()))))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val text = (decoded.document.content[0] as ParagraphNode).content[0] as TextNode
        assertTrue(text.marks.any { it is Code }, "code mark preserved")
        assertEquals("x = 1", text.text)
    }

    @Test
    fun `multiple marks on same text round-trip`() {
        val original = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(
                TextNode(text = "very", marks = listOf(Bold(), Italic())),
            ))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val text = (decoded.document.content[0] as ParagraphNode).content[0] as TextNode
        assertTrue(text.marks.any { it is Bold }, "bold preserved")
        assertTrue(text.marks.any { it is Italic }, "italic preserved")
    }

    @Test
    fun `hard break round-trips inline`() {
        val original = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(
                TextNode(text = "line one"),
                HardBreakNode(),
                TextNode(text = "line two"),
            ))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val children = (decoded.document.content[0] as ParagraphNode).content
        // Three children: text, hardBreak, text.
        assertEquals(3, children.size, "expected text/hardBreak/text, got: $children")
        assertTrue(children[1] is HardBreakNode, "hard break preserved")
    }

    @Test
    fun `link with target attribute round-trips both fields`() {
        val original = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(
                TextNode(
                    text = "site",
                    marks = listOf(Link(attributes = LinkAttributes(href = "https://x.test", target = "_blank")))
                )
            ))
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val link = ((decoded.document.content[0] as ParagraphNode).content[0] as TextNode)
            .marks.firstOrNull { it is Link } as? Link
        assertNotNull(link, "link mark preserved")
        assertEquals("https://x.test", link.attributes?.href)
        assertEquals("_blank", link.attributes?.target)
    }

    @Test
    fun `large document with many block types round-trips`() {
        // Every supported block + mark in one document; catches any node type that's
        // accidentally being dropped or reordered by the bridge.
        val original = Content(document = Document(content = listOf(
            HeadingNode(attributes = HeadingAttributes(level = 1), content = listOf(TextNode(text = "Title"))),
            ParagraphNode(content = listOf(
                TextNode(text = "intro with "),
                TextNode(text = "bold", marks = listOf(Bold())),
                TextNode(text = ", "),
                TextNode(text = "italic", marks = listOf(Italic())),
                TextNode(text = ", "),
                TextNode(text = "code", marks = listOf(Code())),
                TextNode(text = "."),
            )),
            BulletListNode(content = listOf(
                ListItemNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "one"))))),
                ListItemNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "two"))))),
            )),
            CodeBlockNode(
                attributes = CodeBlockAttributes(language = "rust"),
                content = listOf(TextNode(text = "fn main() {}")),
            ),
            TableNode(content = listOf(
                TableRowNode(content = listOf(
                    TableHeaderNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "h"))))),
                )),
                TableRowNode(content = listOf(
                    TableCellNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "c"))))),
                )),
            )),
            ImageNode(attributes = ImageAttributes(src = "x.png", alt = "x")),
        )))
        val decoded = ProseMirrorYjsBridge.toContent(ProseMirrorYjsBridge.toYDocUpdate(original))
        val types = decoded.document.content.map { it::class.simpleName }
        assertEquals(
            listOf("HeadingNode", "ParagraphNode", "BulletListNode", "CodeBlockNode", "TableNode", "ImageNode"),
            types,
            "all block types preserved in order",
        )
    }

    private fun extractText(node: bosca.documents.DocumentNode): String {
        val sb = StringBuilder()
        fun walk(n: bosca.documents.DocumentNode) {
            if (n is TextNode) sb.append(n.text)
            else n.content.forEach(::walk)
        }
        walk(node)
        return sb.toString()
    }
}
