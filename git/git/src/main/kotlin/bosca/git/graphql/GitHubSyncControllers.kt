package bosca.git.graphql

import bosca.git.model.GitHubUser
import bosca.git.model.GitHubDelivery
import bosca.git.model.GitHubRepositoryPair
import bosca.git.model.GitHubRepositoryPairInput
import bosca.git.service.GitHubSyncService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

object GitHub
object GitHubMutation

/** Pairing and identity mappings are privileged configuration, independently of repository access. */
@TypeController(type = "GitHub")
class GitHubSyncQuery(private val service: GitHubSyncService, private val groups: GroupEvaluator) : GraphQLController<GitHub> {
    @Field
    suspend fun pair(authentication: AuthenticationContext, repositoryId: UUID): GitHubRepositoryPair? {
        groups.verifyHasAdminGroup(authentication)
        return service.findPair(repositoryId)
    }

    @Field
    suspend fun users(authentication: AuthenticationContext, offset: Long?, limit: Int?): List<GitHubUser> {
        groups.verifyHasAdminGroup(authentication)
        return service.findUsers(offset ?: 0, limit?.coerceIn(1, 100) ?: 25)
    }

    @Field
    suspend fun deliveries(authentication: AuthenticationContext, repositoryId: UUID, offset: Long?, limit: Int?): List<GitHubDelivery> {
        groups.verifyHasAdminGroup(authentication)
        return service.findDeliveries(repositoryId, offset ?: 0, limit?.coerceIn(1, 100) ?: 25)
    }
}

@TypeController(type = "GitHubMutation")
class GitHubSyncMutation(private val service: GitHubSyncService, private val groups: GroupEvaluator) : GraphQLController<GitHubMutation> {
    @Field
    suspend fun savePair(authentication: AuthenticationContext, input: GitHubRepositoryPairInput): GitHubRepositoryPair {
        groups.verifyHasAdminGroup(authentication)
        return service.savePair(input)
    }

    @Field
    suspend fun mapUser(authentication: AuthenticationContext, githubUserId: Long, principalId: UUID): GitHubUser {
        groups.verifyHasAdminGroup(authentication)
        return service.mapUser(githubUserId, principalId)
    }

    @Field
    suspend fun unmapUser(authentication: AuthenticationContext, githubUserId: Long): Boolean {
        groups.verifyHasAdminGroup(authentication)
        service.unmapUser(githubUserId)
        return true
    }
}

@TypeController(type = "GitHubRepositoryPair")
class GitHubRepositoryPairController : GraphQLController<GitHubRepositoryPair> {
    @Field fun repositoryId(source: GitHubRepositoryPair): UUID = source.repositoryId
    @Field fun githubRepositoryId(source: GitHubRepositoryPair): Long = source.githubRepositoryId
    @Field fun owner(source: GitHubRepositoryPair): String = source.owner
    @Field fun name(source: GitHubRepositoryPair): String = source.name
    @Field fun webhookSecretName(source: GitHubRepositoryPair): String = source.webhookSecretName
    @Field fun tokenSecretName(source: GitHubRepositoryPair): String = source.tokenSecretName
    @Field fun enabled(source: GitHubRepositoryPair): Boolean = source.enabled
    @Field fun version(source: GitHubRepositoryPair): Long = source.version
    @Field fun created(source: GitHubRepositoryPair): OffsetDateTime = source.created
    @Field fun modified(source: GitHubRepositoryPair): OffsetDateTime = source.modified
}

@TypeController(type = "GitHubUser")
class GitHubUserController : GraphQLController<GitHubUser> {
    @Field fun githubUserId(source: GitHubUser): Long = source.githubUserId
    @Field fun principalId(source: GitHubUser): UUID = source.principalId
    @Field fun created(source: GitHubUser): OffsetDateTime = source.created
    @Field fun modified(source: GitHubUser): OffsetDateTime = source.modified
}

@TypeController(type = "GitHubDelivery")
class GitHubDeliveryController : GraphQLController<GitHubDelivery> {
    @Field fun deliveryId(source: GitHubDelivery): String = source.deliveryId
    @Field fun repositoryId(source: GitHubDelivery): UUID = source.repositoryId
    @Field fun event(source: GitHubDelivery): String = source.event
    @Field fun payload(source: GitHubDelivery): JsonElement = source.payload
    @Field fun githubUserId(source: GitHubDelivery): Long? = source.githubUserId
    @Field fun principalId(source: GitHubDelivery): UUID? = source.principalId
    @Field fun ignored(source: GitHubDelivery): Boolean = source.ignored
    @Field fun created(source: GitHubDelivery): OffsetDateTime = source.created
}
