package bosca.ai.agents.service

import bosca.ai.agents.model.AgentResource
import bosca.ai.agents.model.AgentResourceInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing [AgentResource] definitions — reference resources tied to agents.
 *
 * Each [AgentResource] has exactly one implementation variant (static text, a metadata
 * object, a metadata document body, metadata file/blob content, a Bosca script's output,
 * or a pinned GraphQL operation's output). This service handles the CRUD lifecycle.
 */
interface AgentResourceService : Service {
    /**
     * Retrieves all agent resource definitions.
     *
     * @return the complete list of agent resources
     */
    suspend fun getAll(): List<AgentResource>

    /**
     * Retrieves a single agent resource by its unique identifier.
     *
     * @param id the resource's unique identifier
     * @return the matching resource, or `null` if no resource exists with the given [id]
     */
    suspend fun get(id: UUID): AgentResource?

    /**
     * Retrieves a single agent resource by its unique key string.
     *
     * @param key the resource's unique key
     * @return the matching resource, or `null` if no resource exists with the given [key]
     */
    suspend fun getByKey(key: String): AgentResource?

    /**
     * Creates and persists a new agent resource from the given input.
     *
     * @param input the resource definition; exactly one implementation variant should be set
     * @return the newly created resource with its generated identifier
     */
    suspend fun add(input: AgentResourceInput): AgentResource

    /**
     * Updates an existing agent resource's definition.
     *
     * @param id the unique identifier of the resource to update
     * @param input the updated resource definition
     * @return the modified resource reflecting the applied changes
     */
    suspend fun edit(id: UUID, input: AgentResourceInput): AgentResource

    /**
     * Permanently removes an agent resource by its unique identifier.
     *
     * @param id the unique identifier of the resource to delete
     * @return `true` if the resource was found and deleted, `false` if none existed with the given [id]
     */
    suspend fun delete(id: UUID): Boolean

    /**
     * Returns a `key → id` map for all resources. Loads only the two columns, suitable for
     * high-frequency lookup paths.
     */
    suspend fun getKeyIndex(): Map<String, UUID>

    /** Find a resource by its (gitRepositoryId, gitPath) Git linkage, if any. */
    suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): AgentResource?

    /** Set or clear the resource's Git linkage. Pass nulls to unlink. */
    suspend fun linkToGit(id: UUID, repositoryId: UUID?, gitPath: String?)

    /** Set or clear the resource's last sync error message. */
    suspend fun setSyncError(id: UUID, error: String?)
}
