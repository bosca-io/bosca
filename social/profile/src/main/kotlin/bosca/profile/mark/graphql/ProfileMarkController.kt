package bosca.profile.mark.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.mark.model.ProfileMark
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class ProfileMarkController(
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val collectionService: CollectionService,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator
) : GraphQLController<ProfileMark> {

    @Field
    fun id(mark: ProfileMark) = mark.id

    @Field
    fun created(mark: ProfileMark) = mark.created

    @Field
    fun attributes(mark: ProfileMark) = mark.attributes

    @Field
    suspend fun collection(
        authentication: AuthenticationContext,
        mark: ProfileMark
    ): Collection? {
        val collection = mark.collectionId?.let { collectionService.getById(it) } ?: return null
        if (!collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.VIEW)) {
            return null
        }
        return collection
    }

    @Field
    suspend fun metadata(
        authentication: AuthenticationContext,
        mark: ProfileMark
    ): Metadata? {
        val metadata = mark.metadataId?.let { metadataService.getById(it) } ?: return null
        if (!metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)) {
            return null
        }
        return metadata
    }
}