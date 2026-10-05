package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextModel
import bosca.recommendations.model.RecommendationTrainingStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Exposes captured training versions and their observable lifecycle. */
@TypeController
class RecommendationContextModelController : GraphQLController<RecommendationContextModel> {
    /** Returns the persisted training snapshot, never the current editable context. */
    @Field
    fun context(model: RecommendationContextModel): RecommendationContext = model.context

    @Field
    fun version(model: RecommendationContextModel): Long = model.version

    @Field
    fun contextId(model: RecommendationContextModel): UUID = model.contextId

    @Field
    fun revision(model: RecommendationContextModel): Long = model.revision

    @Field
    fun selectionRevision(model: RecommendationContextModel): Long = model.selectionRevision

    @Field
    fun status(model: RecommendationContextModel): RecommendationTrainingStatus = model.status

    @Field
    fun exported(model: RecommendationContextModel): Boolean = model.exported

    @Field
    fun personalized(model: RecommendationContextModel): Boolean = model.personalized

    @Field
    fun pinned(model: RecommendationContextModel): Boolean = model.pinned

    @Field
    fun failure(model: RecommendationContextModel): String? = model.failure

    @Field
    fun created(model: RecommendationContextModel): OffsetDateTime = model.created

    @Field
    fun started(model: RecommendationContextModel): OffsetDateTime? = model.started

    @Field
    fun completed(model: RecommendationContextModel): OffsetDateTime? = model.completed

    @Field
    fun contentModelName(model: RecommendationContextModel): String = model.contentModelName

    @Field
    fun personalizedModelName(model: RecommendationContextModel): String = model.personalizedModelName

}
