@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.service

import bosca.category.model.Category
import bosca.content.metadata.service.MetadataService
import bosca.profile.rating.model.ProfileRating
import bosca.recommendations.model.Recommendation
import bosca.recommendations.model.RecommendationSource
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

class RecommendationAssemblerTest {

    private val metadataService = mockk<MetadataService>()
    private val assembler = RecommendationAssembler(metadataService)

    private val strategyId = UUID.random()

    private fun rec(
        metadataId: UUID = UUID.random(),
        score: Double = 1.0,
        collectionId: UUID? = null,
        sources: Set<RecommendationSource> = emptySet(),
    ): Recommendation {
        return Recommendation(
            metadataId = if (collectionId != null) null else metadataId,
            collectionId = collectionId,
            strategyId = strategyId,
            score = score,
            sources = sources,
        )
    }

    @Test
    fun `empty input returns empty output`() = runTest {
        val result = assembler.assemble(emptyList(), emptySet(), emptySet(), 10)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `filters dismissed metadata`() = runTest {
        val dismissedId = UUID.random()
        val keptId = UUID.random()
        val recs = listOf(rec(metadataId = dismissedId, score = 5.0), rec(metadataId = keptId, score = 3.0))
        coEvery { metadataService.getCategories(any()) } returns emptyList()
        val result = assembler.assemble(recs, setOf(dismissedId), emptySet(), 10)
        assertEquals(1, result.size)
        assertEquals(keptId, result[0].metadataId)
    }

    @Test
    fun `filters dismissed collections`() = runTest {
        val dismissedCollId = UUID.random()
        val keptCollId = UUID.random()
        val recs = listOf(
            rec(collectionId = dismissedCollId, score = 5.0),
            rec(collectionId = keptCollId, score = 3.0),
        )
        val result = assembler.assemble(recs, emptySet(), setOf(dismissedCollId), 10)
        assertEquals(1, result.size)
        assertEquals(keptCollId, result[0].collectionId)
    }

    @Test
    fun `deduplicates by metadata ID keeping highest score`() = runTest {
        val sharedId = UUID.random()
        val recs = listOf(
            rec(metadataId = sharedId, score = 3.0, sources = setOf(RecommendationSource.TRENDING)),
            rec(metadataId = sharedId, score = 7.0, sources = setOf(RecommendationSource.CONTENT_MODEL)),
            rec(metadataId = sharedId, score = 5.0, sources = setOf(RecommendationSource.PERSONALIZED_MODEL)),
        )
        coEvery { metadataService.getCategories(any()) } returns emptyList()
        val result = assembler.assemble(recs, emptySet(), emptySet(), 10)
        assertEquals(1, result.size)
        assertTrue(result[0].score > 0)
        assertEquals(
            setOf(
                RecommendationSource.TRENDING,
                RecommendationSource.CONTENT_MODEL,
                RecommendationSource.PERSONALIZED_MODEL,
            ),
            result.single().sources,
        )
    }

    @Test
    fun `respects limit`() = runTest {
        val recs = (1..20).map { rec(score = it.toDouble()) }
        coEvery { metadataService.getCategories(any()) } returns emptyList()
        val result = assembler.assemble(recs, emptySet(), emptySet(), 5)
        assertEquals(5, result.size)
    }

    @Test
    fun `sorts by score descending`() = runTest {
        val recs = listOf(rec(score = 1.0), rec(score = 5.0), rec(score = 3.0))
        coEvery { metadataService.getCategories(any()) } returns emptyList()
        val result = assembler.assemble(recs, emptySet(), emptySet(), 10)
        for (i in 0 until result.size - 1) {
            assertTrue(result[i].score >= result[i + 1].score)
        }
    }

    @Test
    fun `category diversity cap limits items per category`() = runTest {
        val categoryId = UUID.random()
        val category = Category(id = categoryId, name = "Sports")
        val recs = (1..10).map { rec(score = it.toDouble()) }
        coEvery { metadataService.getCategories(any()) } returns listOf(category)
        val result = assembler.assemble(recs, emptySet(), emptySet(), 10, maxPerCategory = 2)
        assertEquals(2, result.size)
    }

    @Test
    fun `items without categories bypass diversity cap`() = runTest {
        val recs = (1..5).map { rec(score = it.toDouble()) }
        coEvery { metadataService.getCategories(any()) } returns emptyList()
        val result = assembler.assemble(recs, emptySet(), emptySet(), 10, maxPerCategory = 1)
        assertEquals(5, result.size)
    }

    @Test
    fun `collection recommendations bypass category diversity`() = runTest {
        val recs = (1..5).map { rec(collectionId = UUID.random(), score = it.toDouble()) }
        val result = assembler.assemble(recs, emptySet(), emptySet(), 10, maxPerCategory = 1)
        assertEquals(5, result.size)
    }

    @Test
    fun `rating re-rank boosts liked-category candidates over disliked-category ones`() = runTest {
        val likedCat = Category(id = UUID.random(), name = "Tech")
        val dislikedCat = Category(id = UUID.random(), name = "Sports")
        val likedItem = UUID.random()
        val dislikedItem = UUID.random()
        val ratedLiked = UUID.random()
        val ratedDisliked = UUID.random()
        coEvery { metadataService.getCategories(likedItem) } returns listOf(likedCat)
        coEvery { metadataService.getCategories(dislikedItem) } returns listOf(dislikedCat)
        coEvery { metadataService.getCategories(ratedLiked) } returns listOf(likedCat)
        coEvery { metadataService.getCategories(ratedDisliked) } returns listOf(dislikedCat)

        // Equal base scores: only the rating-driven category affinity should separate them.
        val recs = listOf(rec(metadataId = likedItem, score = 1.0), rec(metadataId = dislikedItem, score = 1.0))
        val ratings = listOf(
            ProfileRating(profileId = UUID.random(), metadataId = ratedLiked, rating = 5),
            ProfileRating(profileId = UUID.random(), metadataId = ratedDisliked, rating = 1),
        )

        val result = assembler.assemble(recs, emptySet(), emptySet(), 10, ratings = ratings)

        assertEquals(likedItem, result[0].metadataId)
        assertTrue(result[0].score > result[1].score)
    }

    @Test
    fun `care floor drops sub-threshold candidates`() = runTest {
        val high = UUID.random()
        val low = UUID.random()
        coEvery { metadataService.getCategories(any()) } returns emptyList()
        val recs = listOf(rec(metadataId = high, score = 5.0), rec(metadataId = low, score = 0.1))

        val result = assembler.assemble(recs, emptySet(), emptySet(), 10, careFloor = 1.0)

        assertEquals(1, result.size)
        assertEquals(high, result[0].metadataId)
    }

    @Test
    fun `rating re-rank is a no-op when the rated items carry no categories`() = runTest {
        val ratedItem = UUID.random()
        coEvery { metadataService.getCategories(any()) } returns emptyList() // no categories → empty affinity
        val recs = listOf(rec(score = 1.0), rec(score = 2.0))
        val ratings = listOf(ProfileRating(profileId = UUID.random(), metadataId = ratedItem, rating = 5))
        val result = assembler.assemble(recs, emptySet(), emptySet(), 10, ratings = ratings)
        assertEquals(2, result.size)
        assertTrue(result.all { it.score == 1.0 || it.score == 2.0 })
    }

    @Test
    fun `category affinity accumulates across multiple ratings in the same category`() = runTest {
        val cat = Category(id = UUID.random(), name = "Tech")
        val candidate = UUID.random()
        val rated1 = UUID.random()
        val rated2 = UUID.random()
        coEvery { metadataService.getCategories(candidate) } returns listOf(cat)
        coEvery { metadataService.getCategories(rated1) } returns listOf(cat)
        coEvery { metadataService.getCategories(rated2) } returns listOf(cat)
        val ratings = listOf(
            ProfileRating(profileId = UUID.random(), metadataId = rated1, rating = 5),
            ProfileRating(profileId = UUID.random(), metadataId = rated2, rating = 5), // second hit on the same category's sums/counts
        )
        val result = assembler.assemble(listOf(rec(metadataId = candidate, score = 1.0)), emptySet(), emptySet(), 10, ratings = ratings)
        assertTrue(result.single().score > 1.0) // liked category boosts the candidate
    }

    @Test
    fun `deduplicates by collection id keeping the highest score`() = runTest {
        val collId = UUID.random()
        val recs = listOf(rec(collectionId = collId, score = 0.3), rec(collectionId = collId, score = 0.8))
        val result = assembler.assemble(recs, emptySet(), emptySet(), 10)
        assertEquals(1, result.size)
        assertEquals(0.8, result.single().score)
    }

    @Test
    fun `drops recommendations without a content identity`() = runTest {
        val recommendation = Recommendation(strategyId = strategyId, score = 1.0)

        val result = assembler.assemble(listOf(recommendation), emptySet(), emptySet(), 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `rating re-rank leaves collection recs and unrated-category items unchanged`() = runTest {
        val ratedItem = UUID.random()
        val likedCat = Category(id = UUID.random(), name = "Liked")
        val plainItem = UUID.random()
        val otherCat = Category(id = UUID.random(), name = "Other") // never rated → not in affinity
        val collId = UUID.random()
        coEvery { metadataService.getCategories(ratedItem) } returns listOf(likedCat)
        coEvery { metadataService.getCategories(plainItem) } returns listOf(otherCat)

        val recs = listOf(rec(metadataId = plainItem, score = 1.0), rec(collectionId = collId, score = 1.0))
        val ratings = listOf(
            ProfileRating(profileId = UUID.random(), metadataId = ratedItem, rating = 5), // builds affinity for likedCat
            ProfileRating(profileId = UUID.random(), metadataId = null, collectionId = UUID.random(), rating = 5), // collection rating → skipped in affinity
        )

        val result = assembler.assemble(recs, emptySet(), emptySet(), 10, ratings = ratings)

        // Neither candidate is tilted: the collection rec has no metadata id, and the metadata rec's
        // category was never rated (empty preferences) — both keep their base score.
        assertEquals(2, result.size)
        assertTrue(result.all { it.score == 1.0 })
    }
}
