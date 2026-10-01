package bosca.content.collection.jobs

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.service.CollectionService
import bosca.content.image.service.ImageService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(InternalDI::class)
class CollectionProcessContentExecutorCoverageTest {

    private val collectionService = mockk<CollectionService>()
    private val metadataService = mockk<MetadataService>()
    private val imageService = mockk<ImageService>()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val executor = CollectionProcessContentExecutor(collectionService, metadataService, imageService)

    @BeforeTest
    fun setup() {
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun collection(id: UUID): Collection = Collection(
        id = id,
        name = "collection",
        languageTag = "en",
        workflowStateId = "published",
        public = true,
        deleted = false,
        searchable = true,
    )

    private fun variant(id: UUID): CollectionLanguageVariant = CollectionLanguageVariant(
        id = id,
        languageTag = "es",
        name = "variant",
        workflowStateId = "published",
        public = true,
    )

    private fun uploadedMetadata(): Metadata {
        val metadata = mockk<Metadata>()
        every { metadata.uploaded } returns OffsetDateTime.now()
        return metadata
    }

    private fun notUploadedMetadata(): Metadata {
        val metadata = mockk<Metadata>()
        every { metadata.uploaded } returns null
        return metadata
    }

    @OptIn(Internal::class)
    private suspend fun run() {
        val jobQueue = mockk<JobQueue>()
        val config = CollectionProcessContentJob(id = UUID.random())
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionProcessContentExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `throws FailException when collection not found`() = runTest {
        coEvery { collectionService.getById(any()) } returns null

        assertFailsWith<FailException> {
            run()
        }

        coVerify(exactly = 0) { imageService.optimize(any<Collection>()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `optimizes when an image relationship metadata is uploaded`() = runTest {
        val collectionId = UUID.random()
        val metadataId = UUID.random()
        val col = collection(collectionId)

        coEvery { collectionService.getById(any()) } returns col
        coEvery { collectionService.getMetadataRelationships(collectionId) } returns listOf(
            CollectionMetadataRelationship(
                collectionId = collectionId,
                metadataId = metadataId,
                relationship = "contains",
            )
        )
        coEvery { metadataService.getById(metadataId) } returns uploadedMetadata()
        // Both `hasImageRelationships` and `hasVariantRelationships` are computed as vals
        // before the `||` guard, so getLanguageVariants is always invoked. Stub it with an
        // empty list so the variant branch contributes nothing and the image branch drives optimize.
        coEvery { collectionService.getLanguageVariants(collectionId) } returns emptyList()
        coEvery { imageService.optimize(col) } returns emptyList()

        run()

        coVerify(exactly = 1) { collectionService.getMetadataRelationships(collectionId) }
        coVerify(exactly = 1) { collectionService.getLanguageVariants(collectionId) }
        coVerify(exactly = 1) { imageService.optimize(col) }
    }

    @OptIn(Internal::class)
    @Test
    fun `optimizes via variant relationship when image relationship metadata is missing`() = runTest {
        val collectionId = UUID.random()
        val variantId = UUID.random()
        val imageMetadataId = UUID.random()
        val variantMetadataId = UUID.random()
        val col = collection(collectionId)
        val v = variant(variantId)

        coEvery { collectionService.getById(any()) } returns col
        // Image relationship whose metadata resolves to null -> inner any() returns false.
        coEvery { collectionService.getMetadataRelationships(collectionId) } returns listOf(
            CollectionMetadataRelationship(
                collectionId = collectionId,
                metadataId = imageMetadataId,
                relationship = "contains",
            )
        )
        coEvery { metadataService.getById(imageMetadataId) } returns null

        // Variant relationship whose metadata is uploaded -> variant any() returns true.
        coEvery { collectionService.getLanguageVariants(collectionId) } returns listOf(v)
        coEvery { collectionService.getMetadataRelationships(variantId, "es") } returns listOf(
            CollectionLanguageVariantMetadataRelationship(
                collectionId = variantId,
                metadataId = variantMetadataId,
                languageTag = "es",
                relationship = "translates",
            )
        )
        coEvery { metadataService.getById(variantMetadataId) } returns uploadedMetadata()
        coEvery { imageService.optimize(col) } returns emptyList()

        run()

        coVerify(exactly = 1) { collectionService.getLanguageVariants(collectionId) }
        coVerify(exactly = 1) { imageService.optimize(col) }
    }

    @OptIn(Internal::class)
    @Test
    fun `does not optimize when image relationship not uploaded and variant metadata missing`() = runTest {
        val collectionId = UUID.random()
        val variantId = UUID.random()
        val imageMetadataId = UUID.random()
        val variantMetadataId = UUID.random()
        val col = collection(collectionId)
        val v = variant(variantId)

        coEvery { collectionService.getById(any()) } returns col
        // Image relationship whose metadata exists but uploaded == null -> false.
        coEvery { collectionService.getMetadataRelationships(collectionId) } returns listOf(
            CollectionMetadataRelationship(
                collectionId = collectionId,
                metadataId = imageMetadataId,
                relationship = "contains",
            )
        )
        coEvery { metadataService.getById(imageMetadataId) } returns notUploadedMetadata()

        // Variant relationship whose metadata resolves to null -> variant any() false.
        coEvery { collectionService.getLanguageVariants(collectionId) } returns listOf(v)
        coEvery { collectionService.getMetadataRelationships(variantId, "es") } returns listOf(
            CollectionLanguageVariantMetadataRelationship(
                collectionId = variantId,
                metadataId = variantMetadataId,
                languageTag = "es",
                relationship = "translates",
            )
        )
        coEvery { metadataService.getById(variantMetadataId) } returns null

        run()

        coVerify(exactly = 1) { collectionService.getLanguageVariants(collectionId) }
        coVerify(exactly = 0) { imageService.optimize(any<Collection>()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `does not optimize when there are no relationships`() = runTest {
        val collectionId = UUID.random()
        val col = collection(collectionId)

        coEvery { collectionService.getById(any()) } returns col
        coEvery { collectionService.getMetadataRelationships(collectionId) } returns emptyList()
        coEvery { collectionService.getLanguageVariants(collectionId) } returns emptyList()

        run()

        coVerify(exactly = 1) { collectionService.getMetadataRelationships(collectionId) }
        coVerify(exactly = 1) { collectionService.getLanguageVariants(collectionId) }
        coVerify(exactly = 0) { imageService.optimize(any<Collection>()) }
    }
}
