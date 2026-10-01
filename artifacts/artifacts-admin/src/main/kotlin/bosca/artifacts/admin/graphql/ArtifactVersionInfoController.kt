package bosca.artifacts.admin.graphql

import bosca.artifacts.model.ArtifactVersion
import bosca.artifacts.model.ArtifactVersionBlob
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Resolves nested fields on the `ArtifactVersionInfo` GraphQL type,
 * including the list of blobs associated with the version.
 */
@TypeController("ArtifactVersionInfo")
class ArtifactVersionInfoController(
    private val repoService: ArtifactRepositoryService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<ArtifactVersion> {

    @Field
    fun id(version: ArtifactVersion) = version.id

    @Field
    fun repositoryId(version: ArtifactVersion) = version.repositoryId

    @Field
    fun version(version: ArtifactVersion) = version.version

    @Field
    fun metadata(version: ArtifactVersion) = version.metadata

    @Field
    fun created(version: ArtifactVersion) = version.created

    @Field
    suspend fun blobs(authorization: AuthenticationContext, version: ArtifactVersion): List<ArtifactVersionBlob> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return repoService.getVersionBlobs(version.id)
    }
}
