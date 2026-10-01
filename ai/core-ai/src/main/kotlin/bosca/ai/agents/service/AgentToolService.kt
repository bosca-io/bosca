package bosca.ai.agents.service

import bosca.ai.agents.model.AgentTool
import bosca.ai.agents.model.AgentToolInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing [AgentTool] definitions that can be associated with agents.
 *
 * An [AgentTool] describes a callable capability (backed by a script, an MCP server,
 * or both) that an agent can invoke during chat interactions. This service handles
 * the CRUD lifecycle of tool definitions; the association between tools and agents
 * is managed by [AgentService].
 */
interface AgentToolService : Service {
    /**
     * Retrieves all registered agent tool definitions.
     *
     * @return the complete list of agent tools
     */
    suspend fun getAll(): List<AgentTool>

    /**
     * Retrieves a single agent tool by its unique identifier.
     *
     * @param id the tool's unique identifier
     * @return the matching tool, or `null` if no tool exists with the given [id]
     */
    suspend fun get(id: UUID): AgentTool?

    /**
     * Retrieves a single agent tool by its unique key string.
     *
     * @param key the tool's unique key
     * @return the matching tool, or `null` if no tool exists with the given [key]
     */
    suspend fun getByKey(key: String): AgentTool?

    /**
     * Retrieves a single agent tool by its unique id UUID.
     *
     * @param scriptId the tool's unique id
     * @return the matching tool, or `null` if no tool exists with the given [scriptId]
     */
    suspend fun getByScriptId(scriptId: UUID): List<AgentTool>

    /**
     * Creates and persists a new agent tool from the given input.
     *
     * @param input the tool definition including key, name, description, and optional
     *   script or MCP server references
     * @return the newly created tool with its generated identifier
     */
    suspend fun add(input: AgentToolInput): AgentTool

    /**
     * Updates an existing agent tool's definition.
     *
     * @param id the unique identifier of the tool to update
     * @param input the updated tool definition
     * @return the modified tool reflecting the applied changes
     */
    suspend fun edit(id: UUID, input: AgentToolInput): AgentTool

    /**
     * Permanently removes an agent tool by its unique identifier.
     *
     * @param id the unique identifier of the tool to delete
     * @return `true` if the tool was found and deleted, `false` if no tool existed with the given [id]
     */
    suspend fun delete(id: UUID): Boolean

    /**
     * Returns a `key → id` map for all tools. Loads only the two columns (no description,
     * no configuration JSONB), suitable for high-frequency lookup paths.
     */
    suspend fun getKeyIndex(): Map<String, UUID>

    /** Find a tool by its (gitRepositoryId, gitPath) Git linkage, if any. */
    suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): AgentTool?

    /** Set or clear the tool's Git linkage. Pass nulls to unlink. */
    suspend fun linkToGit(id: UUID, repositoryId: UUID?, gitPath: String?)

    /** Set or clear the tool's last sync error message. */
    suspend fun setSyncError(id: UUID, error: String?)
}
