package bosca.content.collection.graphql

import bosca.content.collection.model.CollectionSupplementaryContentUrls
import bosca.content.security.CollectionPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.SignedUrl


@TypeController
class CollectionSupplementaryContentUrlsController(
    private val permissionEvaluator: CollectionPermissionEvaluator,
    private val storage: ObjectStorageService
) : GraphQLController<CollectionSupplementaryContentUrls> {

    @Field
    suspend fun download(authorization: AuthenticationContext?, urls: CollectionSupplementaryContentUrls): SignedUrl? {
        if (!permissionEvaluator.isSupplementaryAllowed(authorization, urls.collection, PermissionAction.VIEW)) {
            return null
        }
        val principal = authorization?.principal()
        val path = storage.getPath(urls.collection, urls.supplementary.id)
        return storage.getSignedDownloadUrl(path, principal, urls.collection, urls.supplementary.id)
    }

    @Field
    suspend fun upload(authorization: AuthenticationContext?, urls: CollectionSupplementaryContentUrls): SignedUrl? {
        if (!permissionEvaluator.isSupplementaryAllowed(authorization, urls.collection, PermissionAction.EDIT)) {
            return null
        }
        val principal = authorization?.principal() ?: return null
        val path = storage.getPath(urls.collection, urls.supplementary.id)
        return storage.getSignedUploadUrl(path, principal, urls.collection, urls.supplementary.id)
    }
}