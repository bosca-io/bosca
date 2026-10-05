package bosca.content.metadata.graphql

import bosca.content.metadata.model.MetadataSupplementaryContentUrls
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.SignedUrl


@TypeController
class MetadataSupplementaryContentUrlsController(
    private val securityService: SecurityService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
    private val storage: ObjectStorageService
) : GraphQLController<MetadataSupplementaryContentUrls> {

    @Field
    suspend fun download(authorization: AuthenticationContext?, urls: MetadataSupplementaryContentUrls, filename: Boolean?): SignedUrl? {
        if (!permissionEvaluator.isSupplementaryAllowed(authorization, urls.metadata, PermissionAction.VIEW)) {
            return null
        }
        val principal = authorization?.principal() ?: return null
        val path = storage.getPath(urls.metadata, urls.supplementary.id)
        return storage.getSignedDownloadUrl(path, principal, urls.metadata, urls.supplementary.id, filename ?: true)
    }

    @Field
    suspend fun upload(authorization: AuthenticationContext?, urls: MetadataSupplementaryContentUrls): SignedUrl? {
        if (!permissionEvaluator.isSupplementaryAllowed(authorization, urls.metadata, PermissionAction.EDIT)) {
            return null
        }
        val principal = authorization?.principal() ?: return null
        val path = storage.getPath(urls.metadata, urls.supplementary.id)
        return storage.getSignedUploadUrl(path, principal, urls.metadata, urls.supplementary.id)
    }
}