@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.service

import bosca.category.model.Category
import bosca.category.model.CategoryInput
import bosca.category.service.CategoryService
import bosca.content.collection.model.Collection
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataAIService
import bosca.content.metadata.service.MetadataService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * classify matches the curated topic catalog and records the result as normalized content
 * categories on the item (find-or-create), with no recommendations-owned storage.
 */
class ClassificationServiceImplTest {

    private val metadataService = mockk<MetadataService>()
    private val metadataAIService = mockk<MetadataAIService>()
    private val categoryService = mockk<CategoryService>()
    private val service = ClassificationServiceImpl(metadataService, metadataAIService, categoryService)

    @Test
    fun `classify matches topics and assigns matched + newly-created categories to the item`() = runTest {
        val metadataId = UUID.random()
        val techId = UUID.random()
        val scienceId = UUID.random()
        val metadata = mockk<Metadata>()
        coEvery { metadataService.getById(metadataId) } returns metadata
        coEvery { metadataAIService.topics(metadata, null) } returns listOf(
            mockk<Collection> { every { name } returns "Tech" },
            mockk<Collection> { every { name } returns "Science" },
        )
        coEvery { categoryService.getAll() } returns listOf(Category(id = techId, name = "Tech"))
        coEvery { categoryService.add(CategoryInput(name = "Science")) } returns Category(id = scienceId, name = "Science")
        val assigned = slot<List<UUID>>()
        coJustRun { metadataService.setCategories(metadataId, capture(assigned)) }

        val result = service.classify(metadataId)

        assertEquals(listOf(techId, scienceId), result)
        assertEquals(listOf(techId, scienceId), assigned.captured)
        coVerify(exactly = 1) { categoryService.add(CategoryInput(name = "Science")) }
        coVerify(exactly = 0) { categoryService.add(CategoryInput(name = "Tech")) }
    }

    @Test
    fun `classify assigns nothing when there are no topic matches`() = runTest {
        val metadataId = UUID.random()
        val metadata = mockk<Metadata>()
        coEvery { metadataService.getById(metadataId) } returns metadata
        coEvery { metadataAIService.topics(metadata, null) } returns emptyList()

        assertEquals(emptyList(), service.classify(metadataId))
        coVerify(exactly = 0) { metadataService.setCategories(any(), any()) }
    }

    @Test
    fun `classify fails when the metadata does not exist`() = runTest {
        val metadataId = UUID.random()
        coEvery { metadataService.getById(metadataId) } returns null

        assertFailsWith<IllegalStateException> { service.classify(metadataId) }
    }
}
