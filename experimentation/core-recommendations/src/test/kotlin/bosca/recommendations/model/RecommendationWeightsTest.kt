package bosca.recommendations.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json

class RecommendationWeightsTest {
    @Test
    fun `defaults deserialize persisted empty settings`() {
        val weights = Json.decodeFromString(RecommendationWeights.serializer(), "{}").normalized()
        assertEquals(RecommendationWeights(), weights)
        assertEquals(0.5, weights.defaultTypePreference)
    }

    @Test
    fun `type preference normalization preserves zero independently of matching`() {
        val weights = RecommendationWeightsInput(
            similarity = RecommendationSimilarityWeightsInput(type = 0.9),
            typePreferences = listOf(RecommendationTypePreferenceInput(" Devotional ", 0.0)),
            defaultTypePreference = 0.0,
            personalization = 0.0,
        ).toModel()
        assertEquals(listOf(RecommendationTypePreference("devotional", 0.0)), weights.typePreferences)
        assertEquals(0.9, weights.similarity.type)
        assertEquals(0.0, weights.defaultTypePreference)
        assertEquals(0.0, weights.personalization)
        assertEquals(weights, Json.decodeFromString(
            RecommendationWeights.serializer(), Json.encodeToString(RecommendationWeights.serializer(), weights),
        ))
    }

    @Test
    fun `duplicate normalized or blank type preferences fail`() {
        for (preferences in listOf(
            listOf(RecommendationTypePreference(" GUIDE ", 0.2), RecommendationTypePreference("guide", 0.8)),
            listOf(RecommendationTypePreference(" ", 0.5)),
        )) {
            assertFailsWith<IllegalArgumentException> { RecommendationWeights(typePreferences = preferences).normalized() }
        }
    }

    @Test
    fun `every weight rejects nonfinite and out of range values`() {
        for (invalid in listOf(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { RecommendationWeights(defaultTypePreference = invalid).normalized() }
            assertFailsWith<IllegalArgumentException> { RecommendationWeights(content = invalid).normalized() }
            assertFailsWith<IllegalArgumentException> { RecommendationWeights(coEngagement = invalid).normalized() }
            assertFailsWith<IllegalArgumentException> { RecommendationWeights(cohortCoEngagement = invalid).normalized() }
            assertFailsWith<IllegalArgumentException> { RecommendationWeights(learnedNeighbor = invalid).normalized() }
            assertFailsWith<IllegalArgumentException> { RecommendationWeights(personalization = invalid).normalized() }
            assertFailsWith<IllegalArgumentException> { RecommendationWeights(rating = invalid).normalized() }
            assertFailsWith<IllegalArgumentException> {
                RecommendationWeights(similarity = RecommendationSimilarityWeights(semantic = invalid)).normalized()
            }
            assertFailsWith<IllegalArgumentException> {
                RecommendationWeights(similarity = RecommendationSimilarityWeights(categories = invalid)).normalized()
            }
            assertFailsWith<IllegalArgumentException> {
                RecommendationWeights(similarity = RecommendationSimilarityWeights(labels = invalid)).normalized()
            }
            assertFailsWith<IllegalArgumentException> {
                RecommendationWeights(similarity = RecommendationSimilarityWeights(language = invalid)).normalized()
            }
            assertFailsWith<IllegalArgumentException> {
                RecommendationWeights(similarity = RecommendationSimilarityWeights(mime = invalid)).normalized()
            }
            assertFailsWith<IllegalArgumentException> {
                RecommendationWeights(similarity = RecommendationSimilarityWeights(type = invalid)).normalized()
            }
            assertFailsWith<IllegalArgumentException> {
                RecommendationWeights(similarity = RecommendationSimilarityWeights(collections = invalid)).normalized()
            }
            assertFailsWith<IllegalArgumentException> {
                RecommendationWeights(typePreferences = listOf(RecommendationTypePreference("guide", invalid))).normalized()
            }
        }
    }

    @Test
    fun `similarity requires one positive signal and retains chosen relative scale`() {
        val disabled = RecommendationSimilarityWeights(semantic = 0.0, categories = 0.0, labels = 0.0, language = 0.0, mime = 0.0, type = 0.0, collections = 0.0)
        assertFailsWith<IllegalArgumentException> { disabled.validate() }
        disabled.copy(collections = 1.0).validate()
        assertEquals(0.2, RecommendationWeightsInput().toModel().similarity.semantic)
    }
}
