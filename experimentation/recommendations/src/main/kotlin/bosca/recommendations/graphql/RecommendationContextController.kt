package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.RecommendationCollectionFilter
import bosca.recommendations.model.RecommendationContentFilter
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationMetadataFilter
import bosca.recommendations.model.RecommendationWeights
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Exposes saved recommendation context fields and its typed content filter. */
@TypeController
class RecommendationContextController : GraphQLController<RecommendationContext> {

    @Field
    fun id(context: RecommendationContext): UUID = context.id

    @Field
    fun type(context: RecommendationContext): String = context.type

    @Field
    fun name(context: RecommendationContext): String = context.name

    @Field
    fun description(context: RecommendationContext): String = context.description

    @Field
    fun contentFilter(context: RecommendationContext): RecommendationContentFilter = context.contentFilter

    @Field
    fun weights(context: RecommendationContext): RecommendationWeights = context.weights

    @Field
    fun revision(context: RecommendationContext): Long = context.revision

    @Field
    fun selectionRevision(context: RecommendationContext): Long = context.selectionRevision

    @Field
    fun activeModelVersion(context: RecommendationContext): Long? = context.activeModelVersion

    @Field
    fun requestedModelVersion(context: RecommendationContext): Long? = context.requestedModelVersion

    @Field
    fun created(context: RecommendationContext): OffsetDateTime = context.created

    @Field
    fun modified(context: RecommendationContext): OffsetDateTime = context.modified
}

/** Exposes the saved content filter attached to a recommendation context. */
@TypeController
class RecommendationContentFilterController : GraphQLController<RecommendationContentFilter> {

    @Field
    fun metadata(filter: RecommendationContentFilter): RecommendationMetadataFilter = filter.metadata

    @Field
    fun collections(filter: RecommendationContentFilter): RecommendationCollectionFilter? = filter.collections
}

/** Exposes metadata eligibility facets saved on a recommendation context. */
@TypeController
class RecommendationMetadataFilterController : GraphQLController<RecommendationMetadataFilter> {

    @Field
    fun includedContentTypePrefixes(filter: RecommendationMetadataFilter): List<String> = filter.includedContentTypePrefixes

    @Field
    fun excludedContentTypePrefixes(filter: RecommendationMetadataFilter): List<String> = filter.excludedContentTypePrefixes

    @Field
    fun includedAttributeTypes(filter: RecommendationMetadataFilter): List<String> = filter.includedAttributeTypes

    @Field
    fun excludedAttributeTypes(filter: RecommendationMetadataFilter): List<String> = filter.excludedAttributeTypes
}

/** Exposes collection eligibility facets saved on a recommendation context. */
@TypeController
class RecommendationCollectionFilterController : GraphQLController<RecommendationCollectionFilter> {

    @Field
    fun includedTypes(filter: RecommendationCollectionFilter): List<String> = filter.includedTypes

    @Field
    fun excludedTypes(filter: RecommendationCollectionFilter): List<String> = filter.excludedTypes

    @Field
    fun includedAttributeTypes(filter: RecommendationCollectionFilter): List<String> = filter.includedAttributeTypes

    @Field
    fun excludedAttributeTypes(filter: RecommendationCollectionFilter): List<String> = filter.excludedAttributeTypes
}
