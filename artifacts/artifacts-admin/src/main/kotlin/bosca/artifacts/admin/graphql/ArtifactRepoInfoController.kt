package bosca.artifacts.admin.graphql

import bosca.artifacts.model.ArtifactNamespace
import bosca.artifacts.model.ArtifactRepository
import bosca.artifacts.model.ArtifactTag
import bosca.artifacts.model.ArtifactVersion
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Resolves nested fields on the `ArtifactRepoInfo` GraphQL type,
 * including the owning namespace, versions, and tags.
 */
@TypeController("ArtifactRepoInfo")
class ArtifactRepoInfoController(
    private val repoService: ArtifactRepositoryService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<ArtifactRepository> {

    @Field
    fun id(repo: ArtifactRepository) = repo.id

    @Field
    fun namespaceId(repo: ArtifactRepository) = repo.namespaceId

    @Field
    fun name(repo: ArtifactRepository) = repo.name

    @Field
    fun type(repo: ArtifactRepository) = repo.type

    @Field
    fun created(repo: ArtifactRepository) = repo.created

    @Field
    fun modified(repo: ArtifactRepository) = repo.modified

    @Field
    suspend fun namespace(authorization: AuthenticationContext, repo: ArtifactRepository): ArtifactNamespace? {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return repoService.getNamespace(repo.namespaceId)
    }

    @Field
    suspend fun versions(authorization: AuthenticationContext, repo: ArtifactRepository, limit: Int?, offset: Long?): List<ArtifactVersion> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return repoService.listVersions(repo.id, limit ?: 100, offset ?: 0)
    }

    @Field
    suspend fun versionCount(authorization: AuthenticationContext, repo: ArtifactRepository): Long {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return repoService.countVersions(repo.id)
    }

    @Field
    suspend fun tags(authorization: AuthenticationContext, repo: ArtifactRepository, limit: Int?, offset: Long?): List<ArtifactTag> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return repoService.listTagsPaged(repo.id, limit ?: 100, offset ?: 0)
    }

    @Field
    suspend fun tagCount(authorization: AuthenticationContext, repo: ArtifactRepository): Long {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return repoService.countTags(repo.id)
    }
}
