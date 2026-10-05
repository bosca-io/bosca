package bosca.ai.prompts.service

import bosca.ai.prompts.model.Prompt
import bosca.ai.prompts.model.PromptInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing prompt templates that define the system and user instructions
 * used by AI agents during chat interactions.
 *
 * A [Prompt] consists of a system prompt (setting the agent's behavior and persona),
 * a user prompt (template for formatting user input), and metadata describing the
 * expected input/output types and an optional JSON schema for structured output.
 * Prompts are referenced by agents via [bosca.ai.agents.model.Agent.promptId].
 */
interface PromptService : Service {
    /**
     * Retrieves all registered prompt definitions.
     *
     * @return the complete list of prompts
     */
    suspend fun getAll(): List<Prompt>

    /**
     * Retrieves a single prompt by its unique identifier.
     *
     * @param id the prompt's unique identifier
     * @return the matching prompt, or `null` if no prompt exists with the given [id]
     */
    suspend fun get(id: UUID): Prompt?

    /**
     * Retrieves a single prompt by its unique key string.
     *
     * @param key the prompt's unique key
     * @return the matching prompt, or `null` if no prompt exists with the given [key]
     */
    suspend fun getByKey(key: String): Prompt?

    /**
     * Creates and persists a new prompt from the given input.
     *
     * @param input the prompt definition including key, name, system/user prompts,
     *   input/output types, and optional schema
     * @return the newly created prompt with its generated identifier
     */
    suspend fun add(input: PromptInput): Prompt

    /**
     * Updates an existing prompt's definition.
     *
     * @param id the unique identifier of the prompt to update
     * @param input the updated prompt definition
     * @return the modified prompt reflecting the applied changes
     */
    suspend fun edit(id: UUID, input: PromptInput): Prompt

    /**
     * Permanently removes a prompt by its unique identifier.
     *
     * @param id the unique identifier of the prompt to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Returns a `key → id` map for all prompts. Loads only the two columns — avoids
     * pulling potentially large `system_prompt` and `user_prompt` text bodies.
     */
    suspend fun getKeyIndex(): Map<String, UUID>

    /** Find a prompt by its (gitRepositoryId, gitPath) Git linkage, if any. */
    suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): Prompt?

    /** Set or clear the prompt's Git linkage. Pass nulls to unlink. */
    suspend fun linkToGit(id: UUID, repositoryId: UUID?, gitPath: String?)

    /** Set or clear the prompt's last sync error message. */
    suspend fun setSyncError(id: UUID, error: String?)
}
