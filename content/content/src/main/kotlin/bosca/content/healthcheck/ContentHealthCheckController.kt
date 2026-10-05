package bosca.content.healthcheck

import bosca.content.metadata.model.ContentHealthCheckItem
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Marker object representing the ContentHealthCheck GraphQL type, used as the
 * generic parameter for the controller that resolves health check fields.
 */
object ContentHealthCheck

/**
 * Represents the count of items for a single health check category,
 * exposed as a GraphQL type for lightweight summary statistics.
 */
data class ContentHealthCheckCount(val count: Int)

private const val DEFAULT_LIMIT = 100
private const val MAX_LIMIT = 500

/**
 * GraphQL controller that resolves all fields on the ContentHealthCheck type,
 * executing diagnostic queries to identify content issues that require
 * editorial attention. All fields require editor-level authentication.
 */
@TypeController
class ContentHealthCheckController(
    private val repository: ContentHealthCheckRepository,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<ContentHealthCheck> {

    private fun clampLimit(limit: Int?): Int = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
    private fun clampOffset(offset: Int?): Int = (offset ?: 0).coerceAtLeast(0)

    @Field
    suspend fun publishedWithUnpublishedRelationships(
        authentication: AuthenticationContext,
        offset: Int?,
        limit: Int?,
    ): List<ContentHealthCheckItem> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return repository.findPublishedWithUnpublishedRelationships(clampOffset(offset), clampLimit(limit))
    }

    @Field
    suspend fun scheduledButNotPublished(
        authentication: AuthenticationContext,
        offset: Int?,
        limit: Int?,
    ): List<ContentHealthCheckItem> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return repository.findScheduledButNotPublished(clampOffset(offset), clampLimit(limit))
    }

    @Field
    suspend fun pendingNotReady(
        authentication: AuthenticationContext,
        offset: Int?,
        limit: Int?,
    ): List<ContentHealthCheckItem> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return repository.findPendingNotReady(clampOffset(offset), clampLimit(limit))
    }

    @Field
    suspend fun guidesWithUnpublishedSteps(
        authentication: AuthenticationContext,
        offset: Int?,
        limit: Int?,
    ): List<ContentHealthCheckItem> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return repository.findGuidesWithUnpublishedSteps(clampOffset(offset), clampLimit(limit))
    }

    @Field
    suspend fun publishedCollectionsWithUnpublishedMetadata(
        authentication: AuthenticationContext,
        offset: Int?,
        limit: Int?,
    ): List<ContentHealthCheckItem> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return repository.findPublishedCollectionsWithUnpublishedMetadata(clampOffset(offset), clampLimit(limit))
    }

    @Field
    suspend fun failedJobItems(
        authentication: AuthenticationContext,
        offset: Int?,
        limit: Int?,
    ): List<ContentHealthCheckItem> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return repository.findFailedJobItems(clampOffset(offset), clampLimit(limit))
    }

    @Field
    suspend fun deletedItems(
        authentication: AuthenticationContext
    ): ContentHealthCheckCount {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return ContentHealthCheckCount(repository.countDeletedItems())
    }

    @Field
    suspend fun missingContent(
        authentication: AuthenticationContext,
        offset: Int?,
        limit: Int?,
    ): List<ContentHealthCheckItem> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return repository.findMissingContent(clampOffset(offset), clampLimit(limit))
    }
}
