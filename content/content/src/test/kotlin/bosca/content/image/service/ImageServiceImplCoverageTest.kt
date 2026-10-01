package bosca.content.image.service

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.service.CollectionService
import bosca.content.image.model.Coordinates
import bosca.content.image.model.ImageAttributes
import bosca.content.image.model.ImageResizerConfiguration
import bosca.content.image.model.ImageSize
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.content.timeevent.service.TimeEventService
import bosca.http.Client
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.SignedUrl
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.File
import java.time.OffsetDateTime
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ImageServiceImpl covered with mocked services (no containers). Exercises the two public
 * [optimize] entry points plus every private branch reachable through them: the distributed-lock
 * wrapper (running vs unacquired), the image/non-image content-type split, metadata-vs-relationship
 * optimization, all four [bosca.content.model.ContentRelationship] subtypes, dimension probing,
 * resize URL construction (sized vs ratio), the already-uploaded short-circuit, and error arms.
 */
class ImageServiceImplCoverageTest {

    private val storage = mockk<ObjectStorageService>(relaxed = true)
    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val timeEventService = mockk<TimeEventService>(relaxed = true)
    private val client = mockk<Client>()
    private val lockFactory = mockk<DistributedLockFactory>()

    private val json = Json { ignoreUnknownKeys = true }

    /** A [DistributedLock] whose [withLock] simply runs the block, simulating a successful acquire. */
    private class RunningLock : DistributedLock {
        override val isHeld: Boolean = true
        override suspend fun tryAcquire(ttlMillis: Long): Boolean = true
        override suspend fun acquire(ttlMillis: Long, waitTimeoutMillis: Long?, retryDelayMillis: Long): Boolean = true
        override suspend fun renew(ttlMillis: Long): Boolean = true
        override suspend fun release(): Boolean = true
        override suspend fun <T> withLock(
            ttlMillis: Long,
            waitTimeoutMillis: Long?,
            retryDelayMillis: Long,
            block: suspend () -> T,
        ): T? = block()
    }

    /** A [DistributedLock] whose [withLock] returns null, simulating a failed acquire. */
    private class UnacquiredLock : DistributedLock {
        override val isHeld: Boolean = false
        override suspend fun tryAcquire(ttlMillis: Long): Boolean = false
        override suspend fun acquire(ttlMillis: Long, waitTimeoutMillis: Long?, retryDelayMillis: Long): Boolean = false
        override suspend fun renew(ttlMillis: Long): Boolean = false
        override suspend fun release(): Boolean = false
        override suspend fun <T> withLock(
            ttlMillis: Long,
            waitTimeoutMillis: Long?,
            retryDelayMillis: Long,
            block: suspend () -> T,
        ): T? = null
    }

    /**
     * Writes a brand-new, on-disk 32x24 PNG and returns it. The code under test re-opens each
     * downloaded file from disk when uploading it (the `File` overload of `ObjectStorageService.upload`
     * calls `file.inputStream()` / `file.length()`), and the dimension probe in `Metadata.optimize`
     * calls `file.delete()` on the probe's download. Because `resize` runs once per format/size, a
     * single shared file would be deleted or already consumed by the time a later read happens,
     * yielding [java.io.FileNotFoundException]. Handing out a fresh, existing, readable file for
     * EVERY download keeps every read valid.
     */
    private fun freshPng(): File = File.createTempFile("img-cov", ".png").apply {
        deleteOnExit()
        ImageIO.write(BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "png", this)
    }

    /** A fresh file whose `.bin` extension no ImageIO reader recognizes -> getDimensions returns null. */
    private fun freshBin(): File = File.createTempFile("img-cov", ".bin").apply {
        deleteOnExit()
        writeText("not an image")
    }

    @BeforeTest
    fun setup() {
        unmockkAll()

        coEvery { lockFactory.create(any()) } returns RunningLock()

        val signed = SignedUrl(url = "https://storage.example/object", headers = emptyList())
        coEvery { storage.getPath(any<Metadata>(), any()) } returns object : ObjectPath {}
        coEvery { storage.getSignedDownloadUrl(any(), any(), any<Metadata>(), any(), any()) } returns signed
        coEvery { storage.setInputStream(any(), any(), any()) } returns 123L
    }

    @AfterTest
    fun teardown() {
        clearAllMocks()
        unmockkAll()
    }

    private fun service() = ImageServiceImpl(
        configuration = ImageResizerConfiguration(
            url = "https://processor.example",
            sizes = listOf(
                ImageSize(name = "thumb-original", ratio = 100f, size = Coordinates(width = 100f, height = 100f)),
                ImageSize(name = "small", ratio = 50f, size = null),
            ),
        ),
        storage = storage,
        metadataService = metadataService,
        collectionService = collectionService,
        timeEventService = timeEventService,
        client = client,
        json = json,
        distributedLockFactory = lockFactory,
    )

    private fun imageMetadata(
        id: UUID = UUID.random(),
        contentType: String = "image/png",
        uploaded: OffsetDateTime? = OffsetDateTime.now(),
        contentLength: Long? = 100L,
        attributes: JsonObject? = null,
    ) = Metadata(
        id = id,
        name = "img",
        type = MetadataType.STANDARD,
        contentType = contentType,
        contentLength = contentLength,
        languageTag = "en",
        workflowStateId = "draft",
        uploaded = uploaded,
        attributes = attributes,
    )

    private fun supplementary(
        id: UUID = UUID.random(),
        metadataId: UUID,
        key: String,
        uploaded: OffsetDateTime? = null,
        contentLength: Long? = null,
    ) = MetadataSupplementary(
        id = id,
        metadataId = metadataId,
        key = key,
        name = key,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        uploaded = uploaded,
        contentLength = contentLength,
    )

    // ── optimize(Metadata) — image content path ────────────────────────────────

    @Test
    fun `optimize metadata image with no attributes probes dimensions and resizes`() = runTest {
        val md = imageMetadata(contentType = "image/png", attributes = null)

        // getDimensions downloads a real PNG; resize downloads any file.
        coEvery { client.download(any(), any()) } coAnswers { freshPng() }
        coEvery { metadataService.getSupplementary(md.id) } returns emptyList()
        coEvery { metadataService.addSupplementary(any()) } answers {
            val input = firstArg<MetadataSupplementaryInput>()
            supplementary(metadataId = input.metadataId, key = input.key)
        }

        val result = service().optimize(md)

        // Two formats (jpeg, webp) x one sized entry each -> at least the sized supplementary ids.
        assertTrue(result.isNotEmpty())
        val attrSlot = slot<JsonElement>()
        coVerify { metadataService.mergeAttributes(md, capture(attrSlot)) }
        assertTrue(attrSlot.captured.jsonObject.containsKey("size"), "probed size dimension recorded")
        assertTrue(attrSlot.captured.jsonObject.containsKey("jpeg"))
        assertTrue(attrSlot.captured.jsonObject.containsKey("webp"))
    }

    @Test
    fun `optimize metadata image with existing size attribute skips dimension probe`() = runTest {
        val attrs = json.encodeToJsonElement(ImageAttributes.serializer(), ImageAttributes(size = "10x10")) as JsonObject
        val md = imageMetadata(attributes = attrs)

        coEvery { client.download(any(), any()) } coAnswers { freshPng() }
        coEvery { metadataService.getSupplementary(md.id) } returns emptyList()
        coEvery { metadataService.addSupplementary(any()) } answers {
            supplementary(metadataId = firstArg<MetadataSupplementaryInput>().metadataId, key = firstArg<MetadataSupplementaryInput>().key)
        }

        service().optimize(md)

        val attrSlot = slot<JsonElement>()
        coVerify { metadataService.mergeAttributes(md, capture(attrSlot)) }
        // No dimension probe because size was already present.
        assertTrue(!attrSlot.captured.jsonObject.containsKey("size"))
    }

    @Test
    fun `optimize metadata image with unreadable file records no size dimension`() = runTest {
        val md = imageMetadata(attributes = null)

        // Non-image file -> getDimensions returns null -> no "size" recorded.
        coEvery { client.download(any(), any()) } coAnswers { freshBin() }
        coEvery { metadataService.getSupplementary(md.id) } returns emptyList()
        coEvery { metadataService.addSupplementary(any()) } answers {
            supplementary(metadataId = firstArg<MetadataSupplementaryInput>().metadataId, key = firstArg<MetadataSupplementaryInput>().key)
        }

        service().optimize(md)

        val attrSlot = slot<JsonElement>()
        coVerify { metadataService.mergeAttributes(md, capture(attrSlot)) }
        assertTrue(!attrSlot.captured.jsonObject.containsKey("size"))
    }

    @Test
    fun `optimize metadata image with malformed attributes logs and continues`() = runTest {
        // attributes present but not decodable to ImageAttributes forces the catch arm.
        val attrs = buildJsonObject { put("crop", JsonPrimitive("not-an-object")) }
        val md = imageMetadata(attributes = attrs)

        coEvery { client.download(any(), any()) } coAnswers { freshPng() }
        coEvery { metadataService.getSupplementary(md.id) } returns emptyList()
        coEvery { metadataService.addSupplementary(any()) } answers {
            supplementary(metadataId = firstArg<MetadataSupplementaryInput>().metadataId, key = firstArg<MetadataSupplementaryInput>().key)
        }

        val result = service().optimize(md)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `optimize metadata image with dimension probe failure is swallowed`() = runTest {
        val md = imageMetadata(attributes = null)

        // First download (dimension probe) throws; later resize downloads succeed.
        var call = 0
        coEvery { client.download(any(), any()) } answers {
            call++
            if (call == 1) throw RuntimeException("download boom") else freshPng()
        }
        coEvery { metadataService.getSupplementary(md.id) } returns emptyList()
        coEvery { metadataService.addSupplementary(any()) } answers {
            supplementary(metadataId = firstArg<MetadataSupplementaryInput>().metadataId, key = firstArg<MetadataSupplementaryInput>().key)
        }

        val result = service().optimize(md)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `optimize metadata image not uploaded throws`() = runTest {
        val md = imageMetadata(uploaded = null, contentLength = null)
        assertFailsWith<IllegalStateException> { service().optimize(md) }
    }

    @Test
    fun `optimize metadata resize short-circuits when supplementary already uploaded`() = runTest {
        // size already present so no dimension probe; supplementary exists and is uploaded ->
        // resize returns isNew=false, so no "size" keys are written and mergeAttributes is skipped.
        val attrs = json.encodeToJsonElement(ImageAttributes.serializer(), ImageAttributes(size = "5x5")) as JsonObject
        val md = imageMetadata(attributes = attrs)

        coEvery { metadataService.getSupplementary(md.id) } answers {
            listOf(
                supplementary(metadataId = md.id, key = "thumb-original-100.0-jpeg", uploaded = OffsetDateTime.now(), contentLength = 9L),
                supplementary(metadataId = md.id, key = "thumb-original-100.0-webp", uploaded = OffsetDateTime.now(), contentLength = 9L),
            )
        }

        val result = service().optimize(md)

        // Only sized entries are resized in the metadata path (jpeg + webp of thumb-original),
        // and both already-uploaded supplementary ids are collected without creating anything new.
        assertEquals(2, result.size)
        coVerify(exactly = 0) { metadataService.mergeAttributes(any<Metadata>(), any()) }
        coVerify(exactly = 0) { client.download(any(), any()) }
    }

    // ── optimize(Metadata) — non-image path (relationships + time events) ────────

    @Test
    fun `optimize non-image metadata walks relationships and time events`() = runTest {
        val parent = imageMetadata(contentType = "application/pdf")
        val relatedId = UUID.random()
        val timeEventRelatedId = UUID.random()

        val related = imageMetadata(id = relatedId, contentType = "image/png", attributes = null)
        val teRelated = imageMetadata(id = timeEventRelatedId, contentType = "image/jpeg", attributes = null)

        coEvery { metadataService.getRelationships(parent.id) } returns listOf(
            MetadataRelationship(metadataId1 = parent.id, metadataId2 = relatedId, relationship = "rel"),
        )
        coEvery { metadataService.getById(relatedId) } returns related
        coEvery { metadataService.getById(timeEventRelatedId) } returns teRelated

        val timeEvent = TimeEvent(id = UUID.random(), metadataId = parent.id, metadataVersion = parent.version, type = "slide", startOffsetMs = 0L)
        coEvery { timeEventService.getTimeEvents(parent.id, parent.version) } returns listOf(timeEvent)
        coEvery { timeEventService.getMetadataRelationships(timeEvent.id) } returns listOf(
            TimeEventMetadataRelationship(timeEventId = timeEvent.id, metadataId = timeEventRelatedId, relationship = "te-rel"),
        )

        coEvery { client.download(any(), any()) } coAnswers { freshPng() }
        coEvery { metadataService.getSupplementary(any()) } returns emptyList()
        coEvery { metadataService.addSupplementary(any()) } answers {
            supplementary(metadataId = firstArg<MetadataSupplementaryInput>().metadataId, key = firstArg<MetadataSupplementaryInput>().key)
        }

        val result = service().optimize(parent)

        assertTrue(result.isNotEmpty())
        // MetadataRelationship branch merges via metadataService.mergeAttributes(id1,id2,rel,attrs)
        coVerify { metadataService.mergeAttributes(parent.id, relatedId, "rel", any()) }
        // TimeEventMetadataRelationship branch merges via timeEventService
        coVerify { timeEventService.mergeMetadataRelationshipAttributes(timeEvent.id, timeEventRelatedId, "te-rel", any()) }
    }

    // ── ContentRelationship.optimize branches ────────────────────────────────────

    @Test
    fun `relationship with missing target metadata is skipped`() = runTest {
        val parent = imageMetadata(contentType = "application/pdf")
        val missingId = UUID.random()
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(
            MetadataRelationship(metadataId1 = parent.id, metadataId2 = missingId, relationship = "rel"),
        )
        coEvery { metadataService.getById(missingId) } returns null
        coEvery { timeEventService.getTimeEvents(parent.id, parent.version) } returns emptyList()

        val result = service().optimize(parent)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `relationship to non-image target is skipped`() = runTest {
        val parent = imageMetadata(contentType = "application/pdf")
        val nonImageId = UUID.random()
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(
            MetadataRelationship(metadataId1 = parent.id, metadataId2 = nonImageId, relationship = "rel"),
        )
        coEvery { metadataService.getById(nonImageId) } returns imageMetadata(id = nonImageId, contentType = "text/plain")
        coEvery { timeEventService.getTimeEvents(parent.id, parent.version) } returns emptyList()

        val result = service().optimize(parent)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `relationship to svg target is skipped`() = runTest {
        val parent = imageMetadata(contentType = "application/pdf")
        val svgId = UUID.random()
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(
            MetadataRelationship(metadataId1 = parent.id, metadataId2 = svgId, relationship = "rel"),
        )
        coEvery { metadataService.getById(svgId) } returns imageMetadata(id = svgId, contentType = "image/svg+xml")
        coEvery { timeEventService.getTimeEvents(parent.id, parent.version) } returns emptyList()

        val result = service().optimize(parent)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `relationship to un-uploaded image target throws`() = runTest {
        val parent = imageMetadata(contentType = "application/pdf")
        val id = UUID.random()
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(
            MetadataRelationship(metadataId1 = parent.id, metadataId2 = id, relationship = "rel"),
        )
        coEvery { metadataService.getById(id) } returns imageMetadata(id = id, contentType = "image/png", uploaded = null, contentLength = null)
        coEvery { timeEventService.getTimeEvents(parent.id, parent.version) } returns emptyList()

        assertFailsWith<IllegalStateException> { service().optimize(parent) }
    }

    @Test
    fun `relationship with crop attributes resizes ratio sizes and merges`() = runTest {
        val parent = imageMetadata(contentType = "application/pdf")
        val id = UUID.random()
        val cropAttrs = json.encodeToJsonElement(
            ImageAttributes.serializer(),
            ImageAttributes(crop = Coordinates(top = 5f, left = 10f, width = 200f, height = 100f)),
        )
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(
            MetadataRelationship(metadataId1 = parent.id, metadataId2 = id, relationship = "rel", attributes = cropAttrs),
        )
        coEvery { metadataService.getById(id) } returns imageMetadata(id = id, contentType = "image/png")
        coEvery { timeEventService.getTimeEvents(parent.id, parent.version) } returns emptyList()

        coEvery { client.download(any(), any()) } coAnswers { freshPng() }
        coEvery { metadataService.getSupplementary(any()) } returns emptyList()
        coEvery { metadataService.addSupplementary(any()) } answers {
            supplementary(metadataId = firstArg<MetadataSupplementaryInput>().metadataId, key = firstArg<MetadataSupplementaryInput>().key)
        }

        val result = service().optimize(parent)

        assertTrue(result.isNotEmpty())
        coVerify { metadataService.mergeAttributes(parent.id, id, "rel", any()) }
    }

    @Test
    fun `relationship resize failure marks errors and throws`() = runTest {
        val parent = imageMetadata(contentType = "application/pdf")
        val id = UUID.random()
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(
            MetadataRelationship(metadataId1 = parent.id, metadataId2 = id, relationship = "rel"),
        )
        coEvery { metadataService.getById(id) } returns imageMetadata(id = id, contentType = "image/png")
        coEvery { timeEventService.getTimeEvents(parent.id, parent.version) } returns emptyList()
        coEvery { metadataService.getSupplementary(any()) } returns emptyList()
        coEvery { metadataService.addSupplementary(any()) } answers {
            supplementary(metadataId = firstArg<MetadataSupplementaryInput>().metadataId, key = firstArg<MetadataSupplementaryInput>().key)
        }
        // The cropped (parent) resize succeeds via a real file, then every ratio resize fails
        // during download so `errors` becomes true and the whole optimize throws.
        var call = 0
        coEvery { client.download(any(), any()) } answers {
            call++
            if (call % 2 == 1) freshPng() else throw RuntimeException("resize boom")
        }

        assertFailsWith<Exception> { service().optimize(parent) }
    }

    // ── optimize(Collection) branches ────────────────────────────────────────────

    @Test
    fun `optimize collection walks relationships and language variants`() = runTest {
        val collection = Collection(id = UUID.random(), name = "c", languageTag = "en", workflowStateId = "draft")
        val cmrTargetId = UUID.random()
        val variantTargetId = UUID.random()

        coEvery { collectionService.getMetadataRelationships(collection.id) } returns listOf(
            CollectionMetadataRelationship(collectionId = collection.id, metadataId = cmrTargetId, relationship = "hero"),
        )
        val variant = CollectionLanguageVariant(id = collection.id, languageTag = "fr", name = "c-fr")
        coEvery { collectionService.getLanguageVariants(collection.id) } returns listOf(variant)
        coEvery { collectionService.getMetadataRelationships(variant.id, variant.languageTag) } returns listOf(
            CollectionLanguageVariantMetadataRelationship(
                collectionId = collection.id,
                metadataId = variantTargetId,
                languageTag = "fr",
                relationship = "hero",
            ),
        )

        coEvery { metadataService.getById(cmrTargetId) } returns imageMetadata(id = cmrTargetId, contentType = "image/png")
        coEvery { metadataService.getById(variantTargetId) } returns imageMetadata(id = variantTargetId, contentType = "image/png")
        coEvery { client.download(any(), any()) } coAnswers { freshPng() }
        coEvery { metadataService.getSupplementary(any()) } returns emptyList()
        coEvery { metadataService.addSupplementary(any()) } answers {
            supplementary(metadataId = firstArg<MetadataSupplementaryInput>().metadataId, key = firstArg<MetadataSupplementaryInput>().key)
        }

        val result = service().optimize(collection)

        assertTrue(result.isNotEmpty())
        // CollectionMetadataRelationship branch
        coVerify { collectionService.mergeMetadataRelationshipAttributes(collection.id, cmrTargetId, "hero", any()) }
        // CollectionLanguageVariantMetadataRelationship branch
        coVerify { collectionService.mergeMetadataRelationshipAttributes(collection.id, "fr", variantTargetId, "hero", any()) }
    }

    @Test
    fun `optimize collection returns empty when lock cannot be acquired`() = runTest {
        coEvery { lockFactory.create(any()) } returns UnacquiredLock()
        val collection = Collection(id = UUID.random(), name = "c", languageTag = "en", workflowStateId = "draft")

        val result = service().optimize(collection)
        assertTrue(result.isEmpty())
        // Block never runs, so no relationship lookups.
        coVerify(exactly = 0) { collectionService.getMetadataRelationships(any()) }
    }

    @Test
    fun `optimize metadata returns empty when lock cannot be acquired`() = runTest {
        coEvery { lockFactory.create(any()) } returns UnacquiredLock()
        val md = imageMetadata()

        val result = service().optimize(md)
        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { metadataService.getSupplementary(any()) }
    }

    // ── resize URL construction ───────────────────────────────────────────────────

    @Test
    fun `resize builds a ratio url when crop is zero`() = runTest {
        // The relationship path always runs the ratio-config entries (size == null); with a zero
        // crop the cropped resize itself also falls into the ratio branch (pw/ph params).
        val parent = imageMetadata(contentType = "application/pdf")
        val id = UUID.random()
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(
            MetadataRelationship(metadataId1 = parent.id, metadataId2 = id, relationship = "rel"),
        )
        coEvery { metadataService.getById(id) } returns imageMetadata(id = id, contentType = "image/png")
        coEvery { timeEventService.getTimeEvents(parent.id, parent.version) } returns emptyList()

        val urlSlot = mutableListOf<String>()
        coEvery { client.download(capture(urlSlot), any()) } coAnswers { freshPng() }
        coEvery { metadataService.getSupplementary(any()) } returns emptyList()
        coEvery { metadataService.addSupplementary(any()) } answers {
            supplementary(metadataId = firstArg<MetadataSupplementaryInput>().metadataId, key = firstArg<MetadataSupplementaryInput>().key)
        }

        service().optimize(parent)

        assertTrue(urlSlot.any { it.contains("pw=") && it.contains("ph=") }, "ratio url uses pw/ph params")
    }

    @Test
    fun `resize builds a sized url when size is present`() = runTest {
        val md = imageMetadata(attributes = null)
        val urlSlot = mutableListOf<String>()
        coEvery { client.download(capture(urlSlot), any()) } coAnswers { freshPng() }
        coEvery { metadataService.getSupplementary(md.id) } returns emptyList()
        coEvery { metadataService.addSupplementary(any()) } answers {
            supplementary(metadataId = firstArg<MetadataSupplementaryInput>().metadataId, key = firstArg<MetadataSupplementaryInput>().key)
        }

        service().optimize(md)

        assertTrue(urlSlot.any { it.contains("&w=") && it.contains("&h=") }, "sized url uses w/h params")
    }

    @Test
    fun `resize reuses existing un-uploaded supplementary without creating a new one`() = runTest {
        // size present -> no probe. Existing supplementary rows with null uploaded force the
        // "supplementary != null but not uploaded" path: no addSupplementary, still uploads.
        val attrs = json.encodeToJsonElement(ImageAttributes.serializer(), ImageAttributes(size = "5x5")) as JsonObject
        val md = imageMetadata(attributes = attrs)

        coEvery { client.download(any(), any()) } coAnswers { freshPng() }
        coEvery { metadataService.getSupplementary(md.id) } answers {
            listOf(
                supplementary(metadataId = md.id, key = "thumb-original-100.0-jpeg"),
                supplementary(metadataId = md.id, key = "thumb-original-100.0-webp"),
            )
        }

        val result = service().optimize(md)

        assertTrue(result.isNotEmpty())
        coVerify(exactly = 0) { metadataService.addSupplementary(any()) }
        coVerify { metadataService.setSupplementaryUploaded(any(), any<String>(), any<Long>()) }
    }
}
