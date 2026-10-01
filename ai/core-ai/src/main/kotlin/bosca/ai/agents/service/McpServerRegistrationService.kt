package bosca.ai.agents.service

import bosca.ai.agents.model.McpServerRegistration
import bosca.ai.agents.model.McpServerRegistrationInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing Model Context Protocol (MCP) server registrations.
 *
 * An [McpServerRegistration] defines a connection to an external MCP server,
 * specifying its transport type (SSE, STDIO, or Streamable HTTP) and configuration.
 * Registered MCP servers provide additional tool capabilities that agents can
 * access during chat interactions.
 */
interface McpServerRegistrationService : Service {
    /**
     * Retrieves all registered MCP server configurations, regardless of their enabled state.
     *
     * @return the complete list of MCP server registrations
     */
    suspend fun getAll(): List<McpServerRegistration>

    /**
     * Retrieves only the MCP server registrations that are currently enabled.
     * This is typically used at startup or reconnection time to determine
     * which MCP servers should have active connections.
     *
     * @return the list of enabled MCP server registrations
     */
    suspend fun getAllEnabled(): List<McpServerRegistration>

    /**
     * Retrieves a single MCP server registration by its unique identifier.
     *
     * @param id the registration's unique identifier
     * @return the matching registration, or `null` if none exists with the given [id]
     */
    suspend fun get(id: UUID): McpServerRegistration?

    /**
     * Retrieves a single MCP server registration by its unique key string.
     *
     * @param key the registration's unique key
     * @return the matching registration, or `null` if none exists with the given [key]
     */
    suspend fun getByKey(key: String): McpServerRegistration?

    /**
     * Creates and persists a new MCP server registration from the given input.
     *
     * @param input the registration definition including key, transport type, and connection configuration
     * @return the newly created registration with its generated identifier
     */
    suspend fun add(input: McpServerRegistrationInput): McpServerRegistration

    /**
     * Updates an existing MCP server registration's definition.
     *
     * @param id the unique identifier of the registration to update
     * @param input the updated registration definition
     * @return the modified registration reflecting the applied changes
     */
    suspend fun edit(id: UUID, input: McpServerRegistrationInput): McpServerRegistration

    /**
     * Permanently removes an MCP server registration by its unique identifier.
     *
     * @param id the unique identifier of the registration to delete
     * @return `true` if the registration was found and deleted, `false` if none existed with the given [id]
     */
    suspend fun delete(id: UUID): Boolean

    /**
     * Returns a `key → id` map for all registrations. Loads only the two columns,
     * suitable for high-frequency lookup paths.
     */
    suspend fun getKeyIndex(): Map<String, UUID>

    /** Find a registration by its (gitRepositoryId, gitPath) Git linkage, if any. */
    suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): McpServerRegistration?

    /** Set or clear the registration's Git linkage. Pass nulls to unlink. */
    suspend fun linkToGit(id: UUID, repositoryId: UUID?, gitPath: String?)

    /** Set or clear the registration's last sync error message. */
    suspend fun setSyncError(id: UUID, error: String?)
}
