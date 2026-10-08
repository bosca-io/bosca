package bosca.artifacts.admin.graphql

import bosca.artifacts.model.ArtifactNamespace
import bosca.artifacts.model.ArtifactRepository
import bosca.artifacts.model.ArtifactTag
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.ArtifactPublicationService
import bosca.artifacts.model.ArtifactPublicationDestination
import bosca.artifacts.model.ArtifactPublicationDestinationInput
import bosca.artifacts.model.ArtifactSync
import bosca.artifacts.model.ArtifactSyncDestination
import bosca.artifacts.model.ArtifactSyncDestinationInput
import bosca.artifacts.service.ArtifactSyncService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * GraphQL mutation controller for artifact registry administration.
 * All operations require administrator access.
 */
@TypeController
class ArtifactsAdminMutationController(
    private val repoService: ArtifactRepositoryService,
    private val groupEvaluator: GroupEvaluator,
    private val publications: ArtifactPublicationService,
    private val syncing: ArtifactSyncService,
) : GraphQLController<ArtifactsAdminMutation> {

    @Field
    suspend fun createSyncDestination(authorization: AuthenticationContext, input: ArtifactSyncDestinationInput): ArtifactSyncDestination {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return syncing.createDestination(input)
    }

    @Field
    suspend fun updateSyncDestination(authorization: AuthenticationContext, id: UUID, version: Long, enabled: Boolean, username: String?, tokenSecretName: String?, key: String?, remoteRepository: String?): ArtifactSyncDestination {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return syncing.updateDestination(id, version, enabled, username, tokenSecretName, key, remoteRepository)
    }

    @Field
    suspend fun deleteSyncDestination(authorization: AuthenticationContext, id: UUID, version: Long): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        syncing.deleteDestination(id, version)
        return true
    }

    @Field
    suspend fun pushImage(authorization: AuthenticationContext, destinationId: UUID, tagName: String): UUID {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return syncing.push(authorization, destinationId, tagName)
    }

    @Field
    suspend fun retrySync(authorization: AuthenticationContext, id: UUID): ArtifactSync {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return syncing.retry(id)
    }

    @Field
    suspend fun createPublicationDestination(authorization: AuthenticationContext, input: ArtifactPublicationDestinationInput): ArtifactPublicationDestination {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return publications.createDestination(input)
    }

    @Field
    suspend fun updatePublicationDestination(authorization: AuthenticationContext, id: UUID, version: Long, enabled: Boolean, tokenSecretName: String?): ArtifactPublicationDestination {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return publications.updateDestination(id, version, enabled, tokenSecretName)
    }

    @Field
    suspend fun createNamespace(
        authorization: AuthenticationContext,
        name: String,
        public: Boolean?,
    ): ArtifactNamespace {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return repoService.createNamespace(name, public ?: false)
    }

    @Field
    suspend fun updateNamespace(
        authorization: AuthenticationContext,
        id: UUID,
        public: Boolean,
    ): ArtifactNamespace {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return repoService.updateNamespacePublic(id, public)
            ?: throw NoSuchElementException("Namespace not found: $id")
    }

    @Field
    suspend fun deleteNamespace(authorization: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        repoService.deleteNamespace(id)
        return true
    }

    @Field
    suspend fun createRepository(
        authorization: AuthenticationContext,
        namespaceId: UUID,
        name: String,
        type: String,
    ): ArtifactRepository {
        groupEvaluator.verifyHasAdminGroup(authorization)
        val namespaceName = repoService.getNamespace(namespaceId)?.name
            ?: throw NoSuchElementException("namespace not found: $namespaceId")
        return repoService.findOrCreateRepository(namespaceName, name, ArtifactType.fromValue(type))
    }

    @Field
    suspend fun deleteRepository(authorization: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        repoService.deleteRepository(id)
        return true
    }

    @Field
    suspend fun deleteVersion(authorization: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        repoService.deleteVersion(id)
        return true
    }

    @Field
    suspend fun deleteTag(authorization: AuthenticationContext, repositoryId: UUID, name: String): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        repoService.deleteTag(repositoryId, name)
        return true
    }

    @Field
    suspend fun setTag(
        authorization: AuthenticationContext,
        repositoryId: UUID,
        name: String,
        manifestDigest: String,
    ): ArtifactTag {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return repoService.setTag(repositoryId, name, manifestDigest)
    }

    @Field
    suspend fun addPermission(authorization: AuthenticationContext, permission: PermissionInput): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        repoService.getNamespace(permission.entityId)
            ?: throw NoSuchElementException("Namespace not found: ${permission.entityId}")
        repoService.addPermission(permission.entityId, permission.groupId, permission.action)
        return true
    }

    @Field
    suspend fun removePermission(authorization: AuthenticationContext, permission: PermissionInput): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        repoService.getNamespace(permission.entityId)
            ?: throw NoSuchElementException("Namespace not found: ${permission.entityId}")
        repoService.removePermission(permission.entityId, permission.groupId, permission.action)
        return true
    }
}
