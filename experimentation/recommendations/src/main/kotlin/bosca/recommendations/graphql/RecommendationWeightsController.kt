package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.RecommendationWeights
import bosca.recommendations.model.RecommendationSimilarityWeights
import bosca.recommendations.model.RecommendationTypePreference

/** Exposes the importance settings saved for a context revision. */
@TypeController
class RecommendationWeightsController : GraphQLController<RecommendationWeights> {
    @Field
    fun similarity(weights: RecommendationWeights): RecommendationSimilarityWeights = weights.similarity

    @Field
    fun typePreferences(weights: RecommendationWeights): List<RecommendationTypePreference> = weights.typePreferences

    @Field
    fun defaultTypePreference(weights: RecommendationWeights): Double = weights.defaultTypePreference

    @Field
    fun content(weights: RecommendationWeights): Double = weights.content

    @Field
    fun coEngagement(weights: RecommendationWeights): Double = weights.coEngagement

    @Field
    fun cohortCoEngagement(weights: RecommendationWeights): Double = weights.cohortCoEngagement

    @Field
    fun learnedNeighbor(weights: RecommendationWeights): Double = weights.learnedNeighbor

    @Field
    fun personalization(weights: RecommendationWeights): Double = weights.personalization

    @Field
    fun rating(weights: RecommendationWeights): Double = weights.rating

}

/** Exposes the competing content similarity settings. */
@TypeController
class RecommendationSimilarityWeightsController : GraphQLController<RecommendationSimilarityWeights> {
    @Field
    fun semantic(weights: RecommendationSimilarityWeights): Double = weights.semantic

    @Field
    fun categories(weights: RecommendationSimilarityWeights): Double = weights.categories

    @Field
    fun labels(weights: RecommendationSimilarityWeights): Double = weights.labels

    @Field
    fun language(weights: RecommendationSimilarityWeights): Double = weights.language

    @Field
    fun mime(weights: RecommendationSimilarityWeights): Double = weights.mime

    @Field
    fun type(weights: RecommendationSimilarityWeights): Double = weights.type

    @Field
    fun collections(weights: RecommendationSimilarityWeights): Double = weights.collections

}

/** Exposes an editorial type and its configured preference. */
@TypeController
class RecommendationTypePreferenceController : GraphQLController<RecommendationTypePreference> {
    @Field
    fun type(preference: RecommendationTypePreference): String = preference.type

    @Field
    fun weight(preference: RecommendationTypePreference): Double = preference.weight
}
