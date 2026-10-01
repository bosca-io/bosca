package bosca.profile.guide.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.guide.model.ProfileGuideHistory
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class ProfileGuideHistoryController(
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator
) : GraphQLController<ProfileGuideHistory> {

    @Field
    fun attributes(history: ProfileGuideHistory) = history.attributes

    @Field
    fun completed(history: ProfileGuideHistory) = history.completed

    @Field
    suspend fun metadata(
        authentication: AuthenticationContext,
        history: ProfileGuideHistory
    ): Metadata? {
        val metadata = metadataService.getById(history.metadataId, history.version) ?: return null
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        return metadata
    }
}