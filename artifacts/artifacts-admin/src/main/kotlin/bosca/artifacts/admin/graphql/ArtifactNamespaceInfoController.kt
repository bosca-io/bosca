package bosca.artifacts.admin.graphql

import bosca.artifacts.model.ArtifactNamespace
import bosca.artifacts.model.ArtifactRepository
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.security.model.Permission
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Resolves nested fields on the `ArtifactNamespaceInfo` GraphQL type,
 * including the list of repositories within the namespace.
 */
@TypeController("ArtifactNamespaceInfo")
class ArtifactNamespaceInfoController(
    private val repoService: ArtifactRepositoryService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<ArtifactNamespace> {

    @Field
    fun id(ns: ArtifactNamespace) = ns.id

    @Field
    fun name(ns: ArtifactNamespace) = ns.name

    @Field
    fun public(ns: ArtifactNamespace) = ns.public

    @Field
    fun created(ns: ArtifactNamespace) = ns.created

    @Field
    suspend fun repositories(authorization: AuthenticationContext, ns: ArtifactNamespace, type: String?, limit: Int?, offset: Long?): List<ArtifactRepository> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        val artifactType = type?.let { ArtifactType.fromValue(it) }
        // Omitting limit preserves the original unpaged behavior for callers that need the
        // full list (e.g. per-type counts on the namespaces overview).
        if (limit == null && offset == null) {
            return repoService.listRepositories(ns.id, artifactType)
        }
        return repoService.listRepositories(ns.id, artifactType, limit ?: 100, offset ?: 0)
    }

    @Field
    suspend fun repositoryCount(authorization: AuthenticationContext, ns: ArtifactNamespace, type: String?): Long {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return repoService.countRepositories(ns.id, type?.let { ArtifactType.fromValue(it) })
    }

    @Field
    suspend fun permissions(authorization: AuthenticationContext?, ns: ArtifactNamespace): List<Permission> {
        if (!groupEvaluator.hasAdminGroup(authorization)) return emptyList()
        return repoService.getPermissions(ns).map { Permission(it.groupId, it.action) }
    }
}
