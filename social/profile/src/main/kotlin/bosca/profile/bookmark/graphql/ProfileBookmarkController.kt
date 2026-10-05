package bosca.profile.bookmark.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.bookmark.model.ProfileBookmark
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class ProfileBookmarkController(
    private val metadataService: MetadataService,
    private val collectionService: CollectionService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator
) : GraphQLController<ProfileBookmark> {

    @Field
    fun id(bookmark: ProfileBookmark) = bookmark.id

    @Field
    fun created(bookmark: ProfileBookmark) = bookmark.created

    @Field
    fun attributes(bookmark: ProfileBookmark) = bookmark.attributes

    @Field
    suspend fun collection(
        authentication: AuthenticationContext,
        bookmark: ProfileBookmark
    ): Collection? {
        return bookmark.collectionId?.let { collectionId ->
            val collection = collectionService.getById(collectionId) ?: return null
            if (collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.VIEW)) {
                collection
            } else {
                null
            }
        }
    }

    @Field
    suspend fun metadata(
        authentication: AuthenticationContext,
        bookmark: ProfileBookmark
    ): Metadata? {
        return if (bookmark.metadataId != null && bookmark.metadataVersion != null) {
            val metadata = metadataService.getById(bookmark.metadataId ?: error("missing id"), bookmark.metadataVersion ?: error("missing version"))
            if (metadata != null && metadataPermissionEvaluator.isAllowed(
                    authentication,
                    metadata,
                    PermissionAction.VIEW
                )
            ) {
                metadata
            } else {
                null
            }
        } else {
            null
        }
    }
}