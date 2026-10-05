package bosca.artifacts.admin.graphql

import bosca.artifacts.model.ArtifactVersionBlob
import bosca.artifacts.service.BlobStorageService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Resolves fields on the `ArtifactVersionBlobInfo` GraphQL type,
 * including the blob size which requires a storage lookup.
 */
@TypeController("ArtifactVersionBlobInfo")
class ArtifactVersionBlobInfoController(
    private val blobService: BlobStorageService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<ArtifactVersionBlob> {

    @Field
    fun digest(blob: ArtifactVersionBlob) = blob.digest

    @Field
    fun role(blob: ArtifactVersionBlob) = blob.role

    @Field
    fun filename(blob: ArtifactVersionBlob) = blob.filename

    @Field
    fun mediaType(blob: ArtifactVersionBlob) = blob.mediaType

    @Field
    suspend fun size(authorization: AuthenticationContext, blob: ArtifactVersionBlob): Long? {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return blobService.get(blob.digest)?.size
    }
}
