package bosca.ai.prompt.repository

import bosca.ai.agents.git.KeyIdEntry
import bosca.ai.prompts.model.Prompt
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface PromptRepository {

    @Query("select * from prompts order by name")
    suspend fun getAll(): List<Prompt>

    @Query("select * from prompts where id = :id")
    suspend fun getById(id: UUID): Prompt?

    @Query("select * from prompts where key = :key")
    suspend fun getByKey(key: String): Prompt?

    @Query("select * from prompts where git_repository_id = :repositoryId and git_path = :gitPath")
    suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): Prompt?

    @Query("select key, id from prompts")
    suspend fun getKeyIndex(): List<KeyIdEntry>

    @Query("insert into prompts (key, name, description, system_prompt, user_prompt, input_type, output_type, schema, git_repository_id, git_path) values (:key, :name, :description, :systemPrompt, :userPrompt, :inputType, :outputType, :schema, :gitRepositoryId, :gitPath) returning *")
    suspend fun add(prompt: Prompt): Prompt

    @Query("update prompts set key = :key, name = :name, description = :description, system_prompt = :systemPrompt, user_prompt = :userPrompt, input_type = :inputType, output_type = :outputType, schema = :schema where id = :id returning *")
    suspend fun update(prompt: Prompt): Prompt

    @Query("update prompts set git_repository_id = :gitRepositoryId, git_path = :gitPath where id = :id")
    suspend fun linkToGit(id: UUID, gitRepositoryId: UUID?, gitPath: String?)

    @Query("update prompts set last_sync_error = :lastSyncError where id = :id")
    suspend fun setSyncError(id: UUID, lastSyncError: String?)

    @Query("delete from prompts where id = :id")
    suspend fun deleteById(id: UUID)
}
