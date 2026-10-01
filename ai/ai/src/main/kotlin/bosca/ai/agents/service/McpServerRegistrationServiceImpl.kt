package bosca.ai.agents.service

import bosca.ai.agents.model.McpServerRegistration
import bosca.ai.agents.model.McpServerRegistrationInput
import bosca.ai.agents.repository.McpServerRegistrationRepository
import bosca.db.transaction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class McpServerRegistrationServiceImpl(private val repository: McpServerRegistrationRepository) : McpServerRegistrationService {

    override suspend fun getAll(): List<McpServerRegistration> = repository.getAll()

    override suspend fun getAllEnabled(): List<McpServerRegistration> = repository.getAllEnabled()

    override suspend fun get(id: UUID): McpServerRegistration? = repository.getById(id)

    override suspend fun getByKey(key: String): McpServerRegistration? = repository.getByKey(key)

    override suspend fun add(input: McpServerRegistrationInput): McpServerRegistration {
        val registration = McpServerRegistration(
            key = input.key,
            name = input.name,
            description = input.description,
            transportType = input.transportType,
            configuration = input.configuration,
            enabled = input.enabled,
            gitRepositoryId = input.gitRepositoryId,
            gitPath = input.gitPath
        )
        return repository.add(registration)
    }

    override suspend fun edit(id: UUID, input: McpServerRegistrationInput): McpServerRegistration = transaction {
        val existing = repository.getById(id) ?: throw NoSuchElementException("McpServerRegistration not found: $id")
        val updated = existing.copy(
            key = input.key,
            name = input.name,
            description = input.description,
            transportType = input.transportType,
            configuration = input.configuration,
            enabled = input.enabled
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

    override suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): McpServerRegistration? =
        repository.getByGitRepository(repositoryId, gitPath)

    override suspend fun linkToGit(id: UUID, repositoryId: UUID?, gitPath: String?) {
        repository.linkToGit(id, repositoryId, gitPath)
    }

    override suspend fun setSyncError(id: UUID, error: String?) {
        repository.setSyncError(id, error)
    }
}
