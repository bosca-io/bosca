package bosca.artifacts.ui

import bosca.artifacts.ui.generated.ArtifactUploadTarget
import bosca.artifacts.ui.graphql.*
import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute

class ArtifactUploadTargets : ArtifactUploadTarget {
    /** Resolves an accessible raw repository, creating it through the administrator API when requested. */
    override suspend fun resolve(gql: GraphQLClient, repositoryId: String, namespaceId: String, name: String): Map<String, String> {
        if (repositoryId == "new") {
            val trimmed = name.trim()
            require(trimmed.length in 1..255 && trimmed.matches(Regex("[A-Za-z0-9@_.-]+")) && ".." !in trimmed) { "Enter a safe repository name of up to 255 characters." }
            require(namespaceId.isNotBlank()) { "Choose a namespace." }
            val created = gql.execute(ArtifactsCreateRepository, ArtifactsCreateRepository.Variables(namespaceId, trimmed, ArtifactRegistryType.raw)).artifactsAdmin.createRepository
            val namespace = requireNotNull(created.namespace) { "The namespace is unavailable." }
            return mapOf("id" to created.id, "namespace" to namespace.name, "name" to created.name)
        }
        val target = gql.execute(ArtifactsUploadRepository, ArtifactsUploadRepository.Variables(repositoryId)).artifactsAdmin.repository
        require(target?.type == "raw") { "Choose an accessible raw repository." }
        val namespace = requireNotNull(target.namespace) { "The namespace is unavailable." }
        return mapOf("id" to target.id, "namespace" to namespace.name, "name" to target.name)
    }
}
