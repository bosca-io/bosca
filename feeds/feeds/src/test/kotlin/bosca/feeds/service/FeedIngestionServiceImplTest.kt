package bosca.feeds.service

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.feeds.model.FeedItem
import bosca.feeds.model.RawFeedItem
import bosca.feeds.repository.FeedItemRepository
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * ingestion creates+maps a new GUID and edits the existing Metadata for a known GUID.
 * a newly created item is brought to ready (as the system principal) so `MetadataSetReady`
 * fires; a known (deduped) item is only edited and is not re-readied.
 */
@OptIn(ExperimentalUuidApi::class)
class FeedIngestionServiceImplTest {

    private val metadataService = mockk<MetadataService>()
    private val feedItemRepository = mockk<FeedItemRepository>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val bibleService = mockk<BibleService>(relaxed = true)
    private lateinit var service: FeedIngestionServiceImpl

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        // The `impersonate("sa")` extension resolves the principal via these member calls.
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns mockk<Principal>(relaxed = true)
        // Every ingest binds the seeded "Feed Item" template (re-merging its `type` attribute).
        coEvery { metadataService.setDocumentTemplate(any(), any(), any()) } returns Unit
        service = FeedIngestionServiceImpl(metadataService, feedItemRepository, securityService, bibleService)
    }

    @AfterTest
    fun teardown() = unmockkAll()

    private fun stubSupplementaryWrites() {
        coEvery { metadataService.getSupplementaryByMetadataAndKey(any(), any()) } returns null
        coEvery { metadataService.addSupplementary(any()) } returns
            mockk<MetadataSupplementary> { every { id } returns UUID.random() }
        coEvery { metadataService.updateSupplementaryContent(any(), any(), any<String>(), any()) } returns Unit
    }

    @Test
    fun `creates metadata with source attribution and a mapping for a new guid`() = runTest {
        val sourceId = UUID.random()
        val metadataId = UUID.random()
        coEvery { feedItemRepository.get(sourceId, "g1") } returns null
        coEvery { metadataService.add(any(), any(), any()) } returns mockk<Metadata> { every { id } returns metadataId }
        coEvery { metadataService.setReady(any(), any()) } returns mockk<Metadata> { every { id } returns metadataId }
        coEvery { metadataService.setDocument(any(), any(), any()) } returns Unit
        stubSupplementaryWrites()

        val result = service.ingest(
            sourceId,
            RawFeedItem(guid = "g1", title = "T", link = "https://x/1", author = "A", content = "<p>b</p>"),
        )

        assertEquals(metadataId, result.id)
        coVerify(exactly = 1) { metadataService.setReady(any(), any()) }
        coVerify(exactly = 1) {
            metadataService.add(
                isNull(), isNull(),
                match<MetadataInput> {
                    it.source?.id == sourceId && it.source?.identifier == "g1" &&
                        it.source?.sourceUrl == "https://x/1" && it.contentType == "bosca/v-document"
                },
            )
        }
        coVerify(exactly = 1) { feedItemRepository.add(match { it.guid == "g1" && it.metadataId == metadataId }) }
        coVerify(exactly = 0) { metadataService.edit(any(), any()) }
    }

    @Test
    fun `edits the existing metadata for a known guid (dedup)`() = runTest {
        val sourceId = UUID.random()
        val metadataId = UUID.random()
        coEvery { feedItemRepository.get(sourceId, "g1") } returns FeedItem(sourceId = sourceId, guid = "g1", metadataId = metadataId)
        coEvery { metadataService.edit(metadataId, any()) } returns mockk<Metadata> { every { id } returns metadataId }

        val result = service.ingest(sourceId, RawFeedItem(guid = "g1", title = "Updated"))

        assertEquals(metadataId, result.id)
        coVerify(exactly = 1) { metadataService.edit(metadataId, any()) }
        coVerify(exactly = 1) { feedItemRepository.touch(sourceId, "g1") }
        coVerify(exactly = 0) { metadataService.add(any(), any(), any()) }
        coVerify(exactly = 0) { metadataService.setReady(any(), any()) }
    }

    @Test
    fun `decodes HTML entities in the title and sets the body document for a new item`() = runTest {
        val sourceId = UUID.random()
        val metadataId = UUID.random()
        coEvery { feedItemRepository.get(sourceId, "g1") } returns null
        coEvery { metadataService.add(any(), any(), any()) } returns mockk<Metadata> { every { id } returns metadataId }
        coEvery { metadataService.setReady(any(), any()) } returns mockk<Metadata> { every { id } returns metadataId }
        coEvery { metadataService.setDocument(any(), any(), any()) } returns Unit
        stubSupplementaryWrites()

        service.ingest(sourceId, RawFeedItem(guid = "g1", title = "Schmidt&#8217;s rocket", content = "<p>Body</p>"))

        // `&#8217;` (a numeric HTML entity) is decoded to U+2019 in the stored name.
        coVerify(exactly = 1) {
            metadataService.add(isNull(), isNull(), match<MetadataInput> { it.name == "Schmidt’s rocket" })
        }
        coVerify(exactly = 1) { metadataService.setDocument(any(), any(), any()) }
        // The original source HTML is preserved as the `original` supplementary, not an attribute.
        coVerify(exactly = 1) {
            metadataService.addSupplementary(match<MetadataSupplementaryInput> { it.key == "original" && it.contentType == "text/html" })
        }
        coVerify(exactly = 1) { metadataService.updateSupplementaryContent(any(), any(), any<String>(), any()) }
    }

    @Test
    fun `binds the Feed Item template on every ingest, including deduped re-fetches`() = runTest {
        val sourceId = UUID.random()
        val metadataId = UUID.random()
        coEvery { feedItemRepository.get(sourceId, "g1") } returns FeedItem(sourceId = sourceId, guid = "g1", metadataId = metadataId)
        coEvery { metadataService.edit(metadataId, any()) } returns mockk<Metadata> { every { id } returns metadataId }

        service.ingest(sourceId, RawFeedItem(guid = "g1", title = "T"))

        coVerify(exactly = 1) {
            metadataService.setDocumentTemplate(
                any(),
                UUID.parse("f0000000-0000-0000-0000-000000000001"),
                1,
            )
        }
    }

    @Test
    fun `relates the declared feed image, imports it into storage, and attributes it`() = runTest {
        val sourceId = UUID.random()
        val metadataId = UUID.random()
        val imageMetadataId = UUID.random()
        coEvery { feedItemRepository.get(sourceId, "g1") } returns null
        coEvery {
            metadataService.add(any(), any(), match<MetadataInput> { it.contentType == "bosca/v-document" })
        } returns mockk<Metadata> { every { id } returns metadataId }
        coEvery {
            metadataService.add(any(), any(), match<MetadataInput> { it.contentType.startsWith("image/") })
        } returns mockk<Metadata> { every { id } returns imageMetadataId }
        coEvery { metadataService.setReady(any(), any()) } answers { firstArg() }
        coEvery { metadataService.setDocument(any(), any(), any()) } returns Unit
        coEvery { metadataService.getRelationships(metadataId) } returns emptyList()
        coEvery { metadataService.addRelationship(any<bosca.content.metadata.model.MetadataRelationshipInput>()) } returns mockk()
        coEvery { metadataService.importFromUrl(any(), any(), any(), any(), any()) } returns Unit
        stubSupplementaryWrites()

        service.ingest(
            sourceId,
            RawFeedItem(
                guid = "g1", title = "T", link = "https://www.example.news/story", content = "<p>b</p>",
                imageUrl = "https://cdn.x/hero.png?w=1200", imageCredit = "Photo: Jane&#8217;s Studio",
            ),
        )

        coVerify(exactly = 1) {
            metadataService.add(
                isNull(), isNull(),
                match<MetadataInput> {
                    val attrs = it.attributes as? kotlinx.serialization.json.JsonObject
                    it.contentType == "image/png" &&
                        it.source?.identifier == "g1#featured-image" &&
                        it.source?.sourceUrl == "https://cdn.x/hero.png?w=1200" &&
                        it.labels == listOf("featured-image") &&
                        attrs?.get("attribution")?.let { a -> (a as kotlinx.serialization.json.JsonPrimitive).content } == "Photo: Jane’s Studio" &&
                        attrs.get("articleUrl")?.let { a -> (a as kotlinx.serialization.json.JsonPrimitive).content } == "https://www.example.news/story"
                },
            )
        }
        coVerify(exactly = 1) {
            metadataService.addRelationship(
                match<bosca.content.metadata.model.MetadataRelationshipInput> {
                    it.id1 == metadataId && it.id2 == imageMetadataId && it.relationship == "image.featured"
                },
            )
        }
        // The bytes are imported into Bosca storage; the job readies the image when the upload lands.
        coVerify(exactly = 1) {
            metadataService.importFromUrl(imageMetadataId, "https://cdn.x/hero.png?w=1200", "image/png", true, null)
        }
        // Only the item is readied directly — the image's readiness belongs to the import job.
        coVerify(exactly = 1) { metadataService.setReady(any(), any()) }
    }

    @Test
    fun `attribution falls back to the publisher host when no credit is declared`() = runTest {
        val sourceId = UUID.random()
        val metadataId = UUID.random()
        coEvery { feedItemRepository.get(sourceId, "g1") } returns null
        coEvery {
            metadataService.add(any(), any(), match<MetadataInput> { it.contentType == "bosca/v-document" })
        } returns mockk<Metadata> { every { id } returns metadataId }
        coEvery {
            metadataService.add(any(), any(), match<MetadataInput> { it.contentType.startsWith("image/") })
        } returns mockk<Metadata> { every { id } returns UUID.random() }
        coEvery { metadataService.setReady(any(), any()) } answers { firstArg() }
        coEvery { metadataService.setDocument(any(), any(), any()) } returns Unit
        coEvery { metadataService.getRelationships(metadataId) } returns emptyList()
        coEvery { metadataService.addRelationship(any<bosca.content.metadata.model.MetadataRelationshipInput>()) } returns mockk()
        coEvery { metadataService.importFromUrl(any(), any(), any(), any(), any()) } returns Unit
        stubSupplementaryWrites()

        service.ingest(
            sourceId,
            RawFeedItem(guid = "g1", title = "T", link = "https://www.example.news/story",
                content = "<p>b</p>", imageUrl = "https://cdn.x/hero.jpg"),
        )

        coVerify(exactly = 1) {
            metadataService.add(
                isNull(), isNull(),
                match<MetadataInput> {
                    val attrs = it.attributes as? kotlinx.serialization.json.JsonObject
                    it.contentType == "image/jpeg" &&
                        attrs?.get("attribution")?.let { a -> (a as kotlinx.serialization.json.JsonPrimitive).content } == "example.news" &&
                        attrs.get("credit") == null
                },
            )
        }
    }

    @Test
    fun `falls back to the first content image when the feed declares none`() = runTest {
        val sourceId = UUID.random()
        val metadataId = UUID.random()
        coEvery { feedItemRepository.get(sourceId, "g1") } returns null
        coEvery {
            metadataService.add(any(), any(), match<MetadataInput> { it.contentType == "bosca/v-document" })
        } returns mockk<Metadata> { every { id } returns metadataId }
        coEvery {
            metadataService.add(any(), any(), match<MetadataInput> { it.contentType.startsWith("image/") })
        } returns mockk<Metadata> { every { id } returns UUID.random() }
        coEvery { metadataService.setReady(any(), any()) } answers { firstArg() }
        coEvery { metadataService.setDocument(any(), any(), any()) } returns Unit
        coEvery { metadataService.getRelationships(metadataId) } returns emptyList()
        coEvery { metadataService.addRelationship(any<bosca.content.metadata.model.MetadataRelationshipInput>()) } returns mockk()
        coEvery { metadataService.importFromUrl(any(), any(), any(), any(), any()) } returns Unit
        stubSupplementaryWrites()

        service.ingest(
            sourceId,
            RawFeedItem(guid = "g1", title = "T", content = """<figure><img src="https://cdn.x/inline.webp"/></figure><p>b</p>"""),
        )

        coVerify(exactly = 1) {
            metadataService.add(
                isNull(), isNull(),
                match<MetadataInput> { it.contentType == "image/webp" && it.source?.sourceUrl == "https://cdn.x/inline.webp" },
            )
        }
    }

    @Test
    fun `an existing featured relationship is left in place and no image is duplicated`() = runTest {
        val sourceId = UUID.random()
        val metadataId = UUID.random()
        coEvery { feedItemRepository.get(sourceId, "g1") } returns FeedItem(sourceId = sourceId, guid = "g1", metadataId = metadataId)
        coEvery { metadataService.edit(metadataId, any()) } returns mockk<Metadata> { every { id } returns metadataId }
        coEvery { metadataService.setDocument(any(), any(), any()) } returns Unit
        coEvery { metadataService.getRelationships(metadataId) } returns listOf(
            mockk { every { relationship } returns "image.featured" },
        )
        stubSupplementaryWrites()

        service.ingest(sourceId, RawFeedItem(guid = "g1", title = "T", content = "<p>b</p>", imageUrl = "https://cdn.x/hero.jpg"))

        coVerify(exactly = 0) { metadataService.add(any(), any(), any()) }
        coVerify(exactly = 0) { metadataService.addRelationship(any<bosca.content.metadata.model.MetadataRelationshipInput>()) }
    }

    @Test
    fun `an item with no image anywhere creates no relationship`() = runTest {
        val sourceId = UUID.random()
        val metadataId = UUID.random()
        coEvery { feedItemRepository.get(sourceId, "g1") } returns null
        coEvery { metadataService.add(any(), any(), any()) } returns mockk<Metadata> { every { id } returns metadataId }
        coEvery { metadataService.setReady(any(), any()) } answers { firstArg() }
        coEvery { metadataService.setDocument(any(), any(), any()) } returns Unit
        stubSupplementaryWrites()

        service.ingest(sourceId, RawFeedItem(guid = "g1", title = "T", content = "<p>no images here</p>"))

        coVerify(exactly = 0) { metadataService.getRelationships(any()) }
        coVerify(exactly = 0) { metadataService.addRelationship(any<bosca.content.metadata.model.MetadataRelationshipInput>()) }
    }
}
