package bosca.ai.prompt.service

import bosca.ai.prompt.repository.PromptRepository
import bosca.ai.prompts.model.Prompt
import bosca.ai.prompts.model.PromptInput
import bosca.ai.prompts.service.PromptService
import bosca.db.transaction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class PromptServiceImpl(
    private val repository: PromptRepository,
) : PromptService {
    override suspend fun getAll(): List<Prompt> = repository.getAll().toList()

    override suspend fun get(id: UUID): Prompt? = repository.getById(id)

    override suspend fun getByKey(key: String): Prompt? = repository.getByKey(key)

    override suspend fun add(input: PromptInput): Prompt {
        val prompt = Prompt(
            key = input.key,
            name = input.name,
            description = input.description,
            systemPrompt = input.systemPrompt,
            userPrompt = input.userPrompt,
            inputType = input.inputType,
            outputType = input.outputType,
            schema = input.schema,
            gitRepositoryId = input.gitRepositoryId,
            gitPath = input.gitPath
        )
        return repository.add(prompt)
    }

    override suspend fun edit(id: UUID, input: PromptInput): Prompt {
        val existing = repository.getById(id) ?: throw NoSuchElementException("Prompt not found: $id")
        val updated = existing.copy(
            key = input.key,
            name = input.name,
            description = input.description,
            systemPrompt = input.systemPrompt,
            userPrompt = input.userPrompt,
            inputType = input.inputType,
            outputType = input.outputType,
            schema = input.schema
        )
        return repository.update(updated)
    }

    override suspend fun delete(id: UUID) {
        repository.deleteById(id)
    }

    override suspend fun getKeyIndex(): Map<String, UUID> =
        repository.getKeyIndex().associate { it.key to it.id }

    override suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): Prompt? =
        repository.getByGitRepository(repositoryId, gitPath)

    override suspend fun linkToGit(id: UUID, repositoryId: UUID?, gitPath: String?) {
        repository.linkToGit(id, repositoryId, gitPath)
    }

    override suspend fun setSyncError(id: UUID, error: String?) {
        repository.setSyncError(id, error)
    }

}
