package bosca.artifacts.admin.graphql

import bosca.artifacts.model.ArtifactNamespace
import bosca.artifacts.model.ArtifactRepository
import bosca.artifacts.model.ArtifactTag
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.model.ArtifactVersion
import bosca.artifacts.service.ArtifactNamespacePermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

/**
 * GraphQL query controller for artifact registry administration.
 * All operations require administrator access.
 */
@TypeController
class ArtifactsAdminController(
    private val repoService: ArtifactRepositoryService,
    private val namespaceEvaluator: ArtifactNamespacePermissionEvaluator,
) : GraphQLController<ArtifactsAdmin> {

    @Field
    suspend fun namespaces(authorization: AuthenticationContext): List<ArtifactNamespace> {
        val namespaces = repoService.getNamespaces()
        return namespaceEvaluator.filterAllowed(authorization, namespaces, PermissionAction.LIST)
    }

    @Field
    suspend fun namespace(authorization: AuthenticationContext, id: UUID): ArtifactNamespace? {
        val namespace = repoService.getNamespace(id) ?: return null
        namespaceEvaluator.verifyAllowed(authorization, namespace, PermissionAction.VIEW)
        return namespace
    }

    @Field
    suspend fun repositories(
        authentication: AuthenticationContext,
        namespaceId: UUID?,
        type: String?,
        limit: Int?,
        offset: Long?,
    ): List<ArtifactRepository> {
        val repositories = if (namespaceId != null) {
            val repos = repoService.listRepositories(namespaceId, type?.let { ArtifactType.fromValue(it) })
            repos.drop((offset ?: 0).toInt()).take(limit ?: 100)
        } else if (type != null) {
            repoService.listRepositoriesByType(ArtifactType.fromValue(type), limit ?: 100, offset ?: 0)
        } else {
            // When no type filter is specified, fetch from all types and apply
            // limit/offset to the combined result to preserve pagination semantics.
            val effectiveLimit = limit ?: 100
            val effectiveOffset = (offset ?: 0).toInt()
            val allRepos = ArtifactType.entries.flatMap { t ->
                repoService.listRepositoriesByType(t, effectiveLimit + effectiveOffset, 0)
            }
            allRepos.drop(effectiveOffset).take(effectiveLimit)
        }
        val repositoryToNamespace = repositories.groupBy { repoService.getNamespace(it.namespaceId) ?: throw IllegalArgumentException("Namespace not found for repository ${it.namespaceId}") }
        val allowed = namespaceEvaluator.filterAllowed(authentication, repositoryToNamespace.keys.toList(), PermissionAction.VIEW)
        return allowed.flatMap { repositoryToNamespace[it] ?: emptyList() }
    }

    @Field
    suspend fun repository(authentication: AuthenticationContext, id: UUID): ArtifactRepository? {
        val repository = repoService.getRepository(id) ?: return null
        val namespace = repoService.getNamespace(repository.namespaceId) ?: throw IllegalArgumentException("Namespace not found for repository ${repository.namespaceId}")
        namespaceEvaluator.verifyAllowed(authentication, namespace, PermissionAction.VIEW)
        return repository
    }

    @Field
    suspend fun versions(authentication: AuthenticationContext, repositoryId: UUID): List<ArtifactVersion> {
        val repository = repoService.getRepository(repositoryId) ?: return emptyList()
        val namespace = repoService.getNamespace(repository.namespaceId) ?: throw IllegalArgumentException("Namespace not found for repository ${repository.namespaceId}")
        namespaceEvaluator.verifyAllowed(authentication, namespace, PermissionAction.VIEW)
        return repoService.listVersions(repositoryId)
    }

    @Field
    suspend fun tags(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        limit: Int?,
        last: String?,
    ): List<ArtifactTag> {
        val repository = repoService.getRepository(repositoryId) ?: return emptyList()
        val namespace = repoService.getNamespace(repository.namespaceId) ?: throw IllegalArgumentException("Namespace not found for repository ${repository.namespaceId}")
        namespaceEvaluator.verifyAllowed(authentication, namespace, PermissionAction.VIEW)
        return repoService.listTags(repositoryId, limit ?: 100, last)
    }
}
