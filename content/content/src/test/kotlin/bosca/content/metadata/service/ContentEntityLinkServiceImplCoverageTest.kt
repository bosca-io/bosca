package bosca.content.metadata.service

import bosca.content.metadata.model.ContentEntityLink
import bosca.content.metadata.model.ContentLinkTarget
import bosca.content.metadata.repository.ContentEntityLinkRepository
import bosca.documents.Content
import bosca.documents.Document
import bosca.documents.DocumentNode
import bosca.documents.ParagraphNode
import bosca.documents.TextNode
import bosca.documents.marks.Link
import bosca.documents.marks.LinkAttributes
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit coverage for [ContentEntityLinkServiceImpl]. The service is a thin delegation layer over
 * [ContentEntityLinkRepository]; the only real logic is [ContentEntityLinkServiceImpl.extractAndStore],
 * which deletes prior links, runs the extractor, and loops over the results. We mock the repository and
 * drive the extractor with real [Content] instances so both the loop-runs and loop-skipped arms execute.
 */
class ContentEntityLinkServiceImplCoverageTest {

    private val repository = mockk<ContentEntityLinkRepository>(relaxed = true)
    private val service = ContentEntityLinkServiceImpl(repository)

    @AfterTest
    fun teardown() {
        unmockkAll()
    }

    private fun docWith(vararg children: DocumentNode) =
        Content(document = Document(content = children.toList()))

    private fun paragraph(vararg children: DocumentNode) =
        ParagraphNode(content = children.toList())

    private fun text(value: String, vararg marks: bosca.documents.marks.Mark) =
        TextNode(text = value, marks = marks.toList())

    private fun link(href: String) =
        Link(attributes = LinkAttributes(href = href))

    @Test
    fun `extractAndStore deletes prior links and stores each extracted link`() = runTest {
        val metadataId = UUID.random()
        val metadataVersion = 3
        val targetMetadataId = UUID.random()
        val profileId = UUID.random()

        // Two distinct extractable references -> the for-loop body runs at least twice.
        val content = docWith(
            paragraph(text("see ", link("/metadata/$targetMetadataId"))),
            paragraph(text("cc @$profileId")),
        )

        val added = mutableListOf<ContentLinkTarget>()
        coEvery {
            repository.add(any(), any(), any(), any(), any(), any())
        } answers {
            added.add(thirdArg())
            mockk(relaxed = true)
        }

        service.extractAndStore(metadataId, metadataVersion, content)

        // deleteBySource is always called first with the exact source coordinates.
        coVerify(exactly = 1) { repository.deleteBySource(metadataId, metadataVersion) }

        // Each extracted link is persisted via add(...) with the source metadata coordinates.
        coVerify(exactly = 1) {
            repository.add(
                metadataId = metadataId,
                metadataVersion = metadataVersion,
                targetType = ContentLinkTarget.METADATA,
                targetId = targetMetadataId.toString(),
                nodeType = "link",
                position = any<JsonElement>(),
            )
        }
        coVerify(exactly = 1) {
            repository.add(
                metadataId = metadataId,
                metadataVersion = metadataVersion,
                targetType = ContentLinkTarget.PROFILE,
                targetId = profileId.toString(),
                nodeType = "mention",
                position = any<JsonElement>(),
            )
        }
        assertEquals(2, added.size, "both extracted links should be added")
        assertTrue(added.contains(ContentLinkTarget.METADATA))
        assertTrue(added.contains(ContentLinkTarget.PROFILE))
    }

    @Test
    fun `extractAndStore with empty content deletes prior links but adds nothing`() = runTest {
        val metadataId = UUID.random()
        val metadataVersion = 1

        // Empty document -> extractor returns an empty list -> the for-loop body is skipped.
        service.extractAndStore(metadataId, metadataVersion, Content())

        coVerify(exactly = 1) { repository.deleteBySource(metadataId, metadataVersion) }
        coVerify(exactly = 0) { repository.add(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `listBySource delegates to repository and returns its result`() = runTest {
        val metadataId = UUID.random()
        val metadataVersion = 7
        val expected = listOf(
            ContentEntityLink(
                metadataId = metadataId,
                metadataVersion = metadataVersion,
                targetType = ContentLinkTarget.METADATA,
                targetId = UUID.random().toString(),
            )
        )
        coEvery { repository.listBySource(metadataId, metadataVersion) } returns expected

        val result = service.listBySource(metadataId, metadataVersion)

        assertSame(expected, result)
        coVerify(exactly = 1) { repository.listBySource(metadataId, metadataVersion) }
    }

    @Test
    fun `listByTarget delegates to repository and returns its result`() = runTest {
        val targetId = UUID.random().toString()
        val expected = listOf(
            ContentEntityLink(
                metadataId = UUID.random(),
                metadataVersion = 1,
                targetType = ContentLinkTarget.COLLECTION,
                targetId = targetId,
            )
        )
        coEvery { repository.listByTarget(ContentLinkTarget.COLLECTION, targetId) } returns expected

        val result = service.listByTarget(ContentLinkTarget.COLLECTION, targetId)

        assertSame(expected, result)
        coVerify(exactly = 1) { repository.listByTarget(ContentLinkTarget.COLLECTION, targetId) }
    }

    @Test
    fun `listByMetadata delegates to repository and returns its result`() = runTest {
        val metadataId = UUID.random()
        val expected = emptyList<ContentEntityLink>()
        coEvery { repository.listByMetadata(metadataId) } returns expected

        val result = service.listByMetadata(metadataId)

        assertSame(expected, result)
        coVerify(exactly = 1) { repository.listByMetadata(metadataId) }
    }

    @Test
    fun `deleteByMetadata delegates to repository`() = runTest {
        val metadataId = UUID.random()

        service.deleteByMetadata(metadataId)

        coVerify(exactly = 1) { repository.deleteByMetadata(metadataId) }
    }
}
