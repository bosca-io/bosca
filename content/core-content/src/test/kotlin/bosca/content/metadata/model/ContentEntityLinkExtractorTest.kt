package bosca.content.metadata.model

import bosca.documents.*
import bosca.documents.marks.Link
import bosca.documents.marks.LinkAttributes
import bosca.documents.marks.Bold
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentEntityLinkExtractorTest {

    private fun doc(vararg children: DocumentNode) =
        Content(document = Document(content = children.toList()))

    private fun paragraph(vararg children: DocumentNode) =
        ParagraphNode(content = children.toList())

    private fun text(value: String, vararg marks: bosca.documents.marks.Mark) =
        TextNode(text = value, marks = marks.toList())

    private fun link(href: String) =
        Link(attributes = LinkAttributes(href = href))

    @Test
    fun `extracts metadata link from href`() {
        val metadataId = UUID.random()
        val content = doc(
            paragraph(text("see this", link("/metadata/$metadataId")))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.METADATA, links[0].targetType)
        assertEquals(metadataId.toString(), links[0].targetId)
        assertEquals("link", links[0].nodeType)
    }

    @Test
    fun `extracts profile link from href`() {
        val profileId = UUID.random()
        val content = doc(
            paragraph(text("by", link("/profile/$profileId")))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.PROFILE, links[0].targetType)
        assertEquals(profileId.toString(), links[0].targetId)
    }

    @Test
    fun `extracts task link from href`() {
        val taskId = UUID.random()
        val content = doc(
            paragraph(text("related", link("/task/$taskId")))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.TASK, links[0].targetType)
    }

    @Test
    fun `extracts spec link from href`() {
        val specId = UUID.random()
        val content = doc(
            paragraph(text("see spec", link("/spec/$specId")))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.SPEC, links[0].targetType)
    }

    @Test
    fun `extracts requirement link from href`() {
        val reqId = UUID.random()
        val content = doc(
            paragraph(text("req", link("/requirement/$reqId")))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.REQUIREMENT, links[0].targetType)
    }

    @Test
    fun `extracts project link from href`() {
        val projectId = UUID.random()
        val content = doc(
            paragraph(text("proj", link("/project/$projectId")))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.PROJECT, links[0].targetType)
    }

    @Test
    fun `extracts git repository link from href`() {
        val repoId = UUID.random()
        val content = doc(
            paragraph(text("repo", link("/git/repository/$repoId")))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.GIT_REPOSITORY, links[0].targetType)
    }

    @Test
    fun `external https link becomes EXTERNAL_URI`() {
        val content = doc(
            paragraph(text("docs", link("https://example.com/docs")))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.EXTERNAL_URI, links[0].targetType)
        assertEquals("https://example.com/docs", links[0].targetId)
    }

    @Test
    fun `external http link becomes EXTERNAL_URI`() {
        val content = doc(
            paragraph(text("old", link("http://legacy.example.com")))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.EXTERNAL_URI, links[0].targetType)
    }

    @Test
    fun `container node metadataId is extracted`() {
        val metadataId = UUID.random()
        val content = doc(
            ContainerNode(
                attributes = ContainerAttributes(name = "bible", metadataId = metadataId),
                content = listOf(paragraph(text("Genesis 1:1")))
            )
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.METADATA, links[0].targetType)
        assertEquals(metadataId.toString(), links[0].targetId)
        assertEquals("container", links[0].nodeType)
    }

    @Test
    fun `image node metadataId is extracted`() {
        val metadataId = UUID.random()
        val content = doc(
            ImageNode(attributes = ImageAttributes(metadataId = metadataId.toString(), alt = "photo"))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.METADATA, links[0].targetType)
        assertEquals(metadataId.toString(), links[0].targetId)
        assertEquals("image", links[0].nodeType)
    }

    @Test
    fun `image node with non-uuid metadataId is ignored`() {
        val content = doc(
            ImageNode(attributes = ImageAttributes(metadataId = "not-a-uuid"))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertTrue(links.isEmpty())
    }

    @Test
    fun `at-mention in text extracts profile reference`() {
        val profileId = UUID.random()
        val content = doc(
            paragraph(text("Hey @$profileId check this"))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.PROFILE, links[0].targetType)
        assertEquals(profileId.toString(), links[0].targetId)
        assertEquals("mention", links[0].nodeType)
    }

    @Test
    fun `multiple mentions in same text node`() {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val content = doc(
            paragraph(text("cc @$id1 and @$id2"))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(2, links.size)
        assertEquals(setOf(id1.toString(), id2.toString()), links.map { it.targetId }.toSet())
    }

    @Test
    fun `deeply nested nodes are extracted`() {
        val metadataId = UUID.random()
        val content = doc(
            BulletListNode(
                content = listOf(
                    ListItemNode(
                        content = listOf(
                            paragraph(text("link", link("/metadata/$metadataId")))
                        )
                    )
                )
            )
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.METADATA, links[0].targetType)
    }

    @Test
    fun `duplicate references are deduplicated`() {
        val metadataId = UUID.random()
        val content = doc(
            paragraph(text("first", link("/metadata/$metadataId"))),
            paragraph(text("second", link("/metadata/$metadataId"))),
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size, "same target+type+nodeType should be deduplicated")
    }

    @Test
    fun `mixed entity types in one document`() {
        val metadataId = UUID.random()
        val profileId = UUID.random()
        val taskId = UUID.random()
        val content = doc(
            paragraph(
                text("see ", link("/metadata/$metadataId")),
                text(" by @$profileId"),
            ),
            paragraph(text("task", link("/task/$taskId"))),
            paragraph(text("docs", link("https://docs.example.com"))),
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(4, links.size)
        val types = links.map { it.targetType }.toSet()
        assertEquals(
            setOf(ContentLinkTarget.METADATA, ContentLinkTarget.PROFILE, ContentLinkTarget.TASK, ContentLinkTarget.EXTERNAL_URI),
            types,
        )
    }

    @Test
    fun `empty document produces no links`() {
        val content = doc()
        val links = ContentEntityLinkExtractor.extract(content)
        assertTrue(links.isEmpty())
    }

    @Test
    fun `link with url field instead of href is extracted`() {
        val specId = UUID.random()
        val content = doc(
            paragraph(text("spec", Link(attributes = LinkAttributes(url = "/spec/$specId"))))
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertEquals(ContentLinkTarget.SPEC, links[0].targetType)
    }

    @Test
    fun `position records document path`() {
        val metadataId = UUID.random()
        val content = doc(
            paragraph(text("first")),
            paragraph(text("link", link("/metadata/$metadataId"))),
        )
        val links = ContentEntityLinkExtractor.extract(content)
        assertEquals(1, links.size)
        assertTrue(links[0].position.toString().contains("1"), "position should reflect second paragraph")
    }
}
