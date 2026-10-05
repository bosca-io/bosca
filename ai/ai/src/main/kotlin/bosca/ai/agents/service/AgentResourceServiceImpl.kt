package bosca.ai.agents.service

import bosca.ai.agents.model.AgentResource
import bosca.ai.agents.model.AgentResourceInput
import bosca.ai.agents.repository.AgentResourceRepository
import bosca.ai.agents.validation.McpImplValidation
import bosca.db.transaction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class AgentResourceServiceImpl(
    private val repository: AgentResourceRepository,
) : AgentResourceService {

    override suspend fun getAll(): List<AgentResource> = repository.getAll()

    override suspend fun get(id: UUID): AgentResource? = repository.getById(id)

    override suspend fun getByKey(key: String): AgentResource? = repository.getByKey(key)

    override suspend fun add(input: AgentResourceInput): AgentResource {
        McpImplValidation.validate(input)
        val resource = AgentResource(
            key = input.key,
            name = input.name,
            description = input.description,
            configuration = input.configuration,
            staticText = input.staticText,
            metadataId = input.metadataId,
            documentMetadataId = input.documentMetadataId,
            documentVersion = input.documentVersion,
            contentMetadataId = input.contentMetadataId,
            scriptId = input.scriptId,
            graphqlOperation = input.graphqlOperation,
            graphqlInputTransform = input.graphqlInputTransform,
            graphqlOutputTransform = input.graphqlOutputTransform,
            gitRepositoryId = input.gitRepositoryId,
            gitPath = input.gitPath
        )
        return repository.add(resource)
    }

    override suspend fun edit(id: UUID, input: AgentResourceInput): AgentResource = transaction {
        McpImplValidation.validate(input)
        val existing = repository.getById(id) ?: throw NoSuchElementException("AgentResource not found: $id")
        val updated = existing.copy(
            key = input.key,
            name = input.name,
            description = input.description,
            configuration = input.configuration,
            staticText = input.staticText,
            metadataId = input.metadataId,
            documentMetadataId = input.documentMetadataId,
            documentVersion = input.documentVersion,
            contentMetadataId = input.contentMetadataId,
            scriptId = input.scriptId,
            graphqlOperation = input.graphqlOperation,
            graphqlInputTransform = input.graphqlInputTransform,
            graphqlOutputTransform = input.graphqlOutputTransform
        )
        repository.update(updated)
    }

    override suspend fun delete(id: UUID): Boolean {
        val existing = repository.getById(id) ?: return false
        repository.deleteById(existing.id)
        return true
    }

    override suspend fun getKeyIndex(): Map<String, UUID> =
        repository.getKeyIndex().associate { it.key to it.id }

    override suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): AgentResource? =
        repository.getByGitRepository(repositoryId, gitPath)

    override suspend fun linkToGit(id: UUID, repositoryId: UUID?, gitPath: String?) {
        repository.linkToGit(id, repositoryId, gitPath)
    }

    override suspend fun setSyncError(id: UUID, error: String?) {
        repository.setSyncError(id, error)
    }

}
