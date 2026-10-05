package bosca.content.metadata.graphql

import bosca.content.metadata.model.MetadataContentUrls
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.SignedUrl

@TypeController
class MetadataContentUrlsController(
    private val permissionEvaluator: MetadataPermissionEvaluator,
    private val objects: ObjectStorageService
) : GraphQLController<MetadataContentUrls> {

    @Field
    suspend fun upload(authentication: AuthenticationContext?, urls: MetadataContentUrls): SignedUrl? {
        if (!permissionEvaluator.isContentAllowed(authentication, urls.metadata, PermissionAction.EDIT)) {
            return null
        }
        val principal = authentication?.principal() ?: return null
        val path = objects.getPath(urls.metadata)
        return objects.getSignedUploadUrl(path, principal, urls.metadata, null)
    }

    @Field
    suspend fun download(
        authentication: AuthenticationContext?,
        urls: MetadataContentUrls,
        filename: Boolean?
    ): SignedUrl? {
        if (!permissionEvaluator.isContentAllowed(authentication, urls.metadata, PermissionAction.VIEW)) {
            return null
        }
        val path = objects.getPath(urls.metadata)
        val principal = authentication?.principal()
        return objects.getSignedDownloadUrl(path, principal, urls.metadata, null, filename ?: true)
    }
}