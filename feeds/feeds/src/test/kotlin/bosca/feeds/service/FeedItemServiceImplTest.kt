package bosca.feeds.service

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.feeds.model.FeedItem
import bosca.feeds.repository.FeedItemRepository
import bosca.recommendations.model.Recommendation
import bosca.recommendations.service.RecommendationService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**the items read surface resolves a source's dedup mappings to content Metadata, newest-first. */
@OptIn(ExperimentalUuidApi::class)
class FeedItemServiceImplTest {

    private val repository = mockk<FeedItemRepository>()
    private val metadataService = mockk<MetadataService>()
    private val recommendationService = mockk<RecommendationService>()
    private val service = FeedItemServiceImpl(repository, metadataService, recommendationService)

    @Test
    fun `resolves mappings to metadata, restoring the repository (newest-first) order`() = runTest {
        val sourceId = UUID.random()
        val id1 = UUID.random()
        val id2 = UUID.random()
        coEvery { repository.getBySource(sourceId, 0, 25) } returns listOf(
            FeedItem(sourceId = sourceId, guid = "g2", metadataId = id2),
            FeedItem(sourceId = sourceId, guid = "g1", metadataId = id1),
        )
        // getByIds is a batch fetch with no order guarantee — return it reversed on purpose.
        coEvery { metadataService.getByIds(listOf(id2, id1)) } returns listOf(
            mockk<Metadata> { every { id } returns id1 },
            mockk<Metadata> { every { id } returns id2 },
        )

        val result = service.getBySource(sourceId, 0, 25)

        assertEquals(listOf(id2, id1), result.map { it.id })
    }

    @Test
    fun `returns empty without hitting metadata when there are no mappings`() = runTest {
        val sourceId = UUID.random()
        coEvery { repository.getBySource(sourceId, 0, 25) } returns emptyList()

        assertEquals(emptyList(), service.getBySource(sourceId, 0, 25))

        coVerify(exactly = 0) { metadataService.getByIds(any()) }
    }

    @Test
    fun `getForProfile resolves the assembled feed, restoring newest-first order`() = runTest {
        val profileId = UUID.random()
        val id1 = UUID.random()
        val id2 = UUID.random()
        coEvery { repository.getForProfile(profileId, 0, 25) } returns listOf(
            FeedItem(sourceId = UUID.random(), guid = "g2", metadataId = id2),
            FeedItem(sourceId = UUID.random(), guid = "g1", metadataId = id1),
        )
        coEvery { metadataService.getByIds(listOf(id2, id1)) } returns listOf(
            mockk<Metadata> { every { id } returns id1 },
            mockk<Metadata> { every { id } returns id2 },
        )

        val result = service.getForProfile(profileId, 0, 25)

        assertEquals(listOf(id2, id1), result.map { it.id })
    }

    @Test
    fun `getForProfile returns empty without hitting metadata when the feed is empty`() = runTest {
        val profileId = UUID.random()
        coEvery { repository.getForProfile(profileId, 0, 25) } returns emptyList()

        assertEquals(emptyList(), service.getForProfile(profileId, 0, 25))

        coVerify(exactly = 0) { metadataService.getByIds(any()) }
    }

    @Test
    fun `getForYou delegates ranking to recommendations and resolves Metadata in order`() = runTest {
        val profileId = UUID.random()
        val id1 = UUID.random()
        val id2 = UUID.random()
        coEvery { recommendationService.getForProfile(profileId, 0L, 25) } returns listOf(
            Recommendation(metadataId = id2, strategyId = UUID.random(), score = 2.0),
            Recommendation(metadataId = id1, strategyId = UUID.random(), score = 1.0),
        )
        coEvery { metadataService.getByIds(listOf(id2, id1)) } returns listOf(
            mockk<Metadata> { every { id } returns id1 },
            mockk<Metadata> { every { id } returns id2 },
        )

        val result = service.getForYou(profileId, 0, 25)

        assertEquals(listOf(id2, id1), result.map { it.id })
    }

    @Test
    fun `getForYou passes an explicit recommendation context`() = runTest {
        val profileId = UUID.random()
        val imageId = UUID.random()
        coEvery {
            recommendationService.getForProfile(profileId, 0L, 25, true, null, "image_picker")
        } returns listOf(Recommendation(metadataId = imageId, strategyId = UUID.random(), score = 1.0))
        coEvery { metadataService.getByIds(listOf(imageId)) } returns listOf(
            mockk<Metadata> { every { id } returns imageId },
        )

        val result = service.getForYou(profileId, 0, 25, "image_picker")

        assertEquals(listOf(imageId), result.map { it.id })
    }

    @Test
    fun `getRecommended delegates to the recommendation blend and resolves Metadata`() = runTest {
        val metadataId = UUID.random()
        val profileId = UUID.random()
        val coEngagedId = UUID.random()
        coEvery { recommendationService.getRecommended(metadataId, profileId, 10) } returns listOf(
            Recommendation(metadataId = coEngagedId, strategyId = UUID.NIL, score = 1.0),
        )
        coEvery { metadataService.getByIds(listOf(coEngagedId)) } returns listOf(
            mockk<Metadata> { every { id } returns coEngagedId },
        )

        val result = service.getRecommended(metadataId, profileId, 10)

        assertEquals(listOf(coEngagedId), result.map { it.id })
    }

    @Test
    fun `getRecommended passes an explicit recommendation context`() = runTest {
        val metadataId = UUID.random()
        val profileId = UUID.random()
        val videoId = UUID.random()
        coEvery {
            recommendationService.getRecommended(metadataId, profileId, 10, true, null, "video_picker")
        } returns listOf(Recommendation(metadataId = videoId, strategyId = UUID.NIL, score = 1.0))
        coEvery { metadataService.getByIds(listOf(videoId)) } returns listOf(
            mockk<Metadata> { every { id } returns videoId },
        )

        val result = service.getRecommended(metadataId, profileId, 10, "video_picker")

        assertEquals(listOf(videoId), result.map { it.id })
    }
}
