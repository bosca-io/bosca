package bosca.workops.jobs

import bosca.documents.Content
import bosca.documents.Document
import bosca.documents.MentionAttributes
import bosca.documents.MentionNode
import bosca.documents.ParagraphNode
import bosca.documents.TextNode
import bosca.documents.yjs.ProseMirrorYjsBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate

class MentionResolverTest {

    private val resolver = MentionResolver()

    @Test
    fun `empty content and empty resolutions do no work`() {
        assertEquals(emptySet(), resolver.extractSlugs(byteArrayOf()))
        assertNull(resolver.resolveMentions(byteArrayOf(), mapOf("ada" to entity("1", "Ada"))))
        assertNull(resolver.resolveMentions(document("@ada"), emptyMap()))
    }

    @Test
    fun `extract slugs deduplicates text mentions and skips existing mention nodes`() {
        val content = ProseMirrorYjsBridge.toYDocUpdate(
            Content(
                document = Document(
                    content = listOf(
                        ParagraphNode(
                            content = listOf(
                                TextNode(text = "@ada and @bob and @ada"),
                                MentionNode(attributes = MentionAttributes(label = "@existing")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(setOf("ada", "bob"), resolver.extractSlugs(content))
    }

    @Test
    fun `resolve replaces every resolvable mention even after an unresolved mention`() {
        val original = document("Hi @unknown, @ada and @bob!")
        val delta = resolver.resolveMentions(
            original,
            mapOf(
                "ada" to entity("profile-1", "Ada"),
                "bob" to entity("profile-2", "Bob"),
            ),
        )

        val resolved = decoded(original, assertNotNull(delta))
        val nodes = assertIs<ParagraphNode>(resolved.document.content.single()).content
        assertEquals(5, nodes.size)
        assertEquals("Hi @unknown, ", assertIs<TextNode>(nodes[0]).text)
        assertEquals("profile-1", assertIs<MentionNode>(nodes[1]).attributes.id)
        assertEquals(" and ", assertIs<TextNode>(nodes[2]).text)
        assertEquals("profile-2", assertIs<MentionNode>(nodes[3]).attributes.id)
        assertEquals("!", assertIs<TextNode>(nodes[4]).text)
    }

    @Test
    fun `resolve handles a mention that occupies the entire text node`() {
        val original = document("@ada")
        val delta = resolver.resolveMentions(original, mapOf("ada" to entity("profile-1", "Ada")))

        val nodes = assertIs<ParagraphNode>(decoded(original, assertNotNull(delta)).document.content.single()).content
        val mention = assertIs<MentionNode>(nodes.single())
        assertEquals("profile-1", mention.attributes.id)
        assertEquals("Ada", mention.attributes.label)
        assertEquals("profile", mention.attributes.entityType)
    }

    @Test
    fun `resolve returns null when no text mention has a resolution`() {
        assertNull(resolver.resolveMentions(document("@unknown"), mapOf("ada" to entity("1", "Ada"))))
    }

    @Test
    fun `close is safe after use`() {
        resolver.close()
    }

    private fun document(text: String): ByteArray = ProseMirrorYjsBridge.toYDocUpdate(
        Content(
            document = Document(
                content = listOf(ParagraphNode(content = listOf(TextNode(text = text)))),
            ),
        ),
    )

    private fun entity(id: String, label: String) = ResolvedEntity(id, label, "profile")

    private fun decoded(original: ByteArray, delta: ByteArray): Content {
        val doc = Doc()
        applyUpdate(doc, original)
        applyUpdate(doc, delta)
        return ProseMirrorYjsBridge.toContent(encodeStateAsUpdate(doc))
    }
}
