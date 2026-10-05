package bosca.ai.agents.service

import bosca.ai.agents.model.Agent
import bosca.ai.agents.model.AgentInput
import bosca.ai.agents.model.AgentTool
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing AI agents and their relationships to sub-agents and tools.
 *
 * An [Agent] represents a configured AI persona that combines a model, a prompt,
 * and optional configuration. Agents can be composed hierarchically through sub-agent
 * relationships and can be equipped with [AgentTool] instances that extend their capabilities.
 */
interface AgentService : Service {
    /**
     * Retrieves all registered agents.
     *
     * @return the complete list of agents
     */
    suspend fun getAll(): List<Agent>

    /**
     * Retrieves a single agent by its unique identifier.
     *
     * @param id the agent's unique identifier
     * @return the matching agent, or `null` if no agent exists with the given [id]
     */
    suspend fun get(id: UUID): Agent?

    /**
     * Retrieves a single agent by its unique key string.
     *
     * @param key the agent's unique key
     * @return the matching agent, or `null` if no agent exists with the given [key]
     */
    suspend fun getByKey(key: String): Agent?

    /**
     * Retrieves multiple agents matching any of the specified keys.
     *
     * @param keys the list of agent keys to look up
     * @return all agents whose key is contained in [keys]; agents with no match are omitted
     */
    suspend fun getByKeys(keys: List<String>): List<Agent>

    /**
     * Creates and persists a new agent from the given input.
     *
     * @param input the agent definition including key, name, model reference, and prompt reference
     * @return the newly created agent with its generated identifier
     */
    suspend fun add(input: AgentInput): Agent

    /**
     * Updates an existing agent's definition.
     *
     * @param id the unique identifier of the agent to update
     * @param input the updated agent definition
     * @return the modified agent reflecting the applied changes
     */
    suspend fun edit(id: UUID, input: AgentInput): Agent

    /**
     * Permanently removes an agent by its unique identifier.
     *
     * @param id the unique identifier of the agent to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Retrieves the ordered list of sub-agents associated with a parent agent.
     * Sub-agents enable hierarchical agent composition, where a parent agent
     * can delegate tasks to specialized child agents.
     *
     * @param agentId the unique identifier of the parent agent
     * @return the sub-agents belonging to the specified parent
     */
    suspend fun getSubAgents(agentId: UUID): List<Agent>

    /**
     * Associates a sub-agent with a parent agent at the specified ordinal position.
     *
     * @param agentId the unique identifier of the parent agent
     * @param subAgentId the unique identifier of the agent to add as a sub-agent
     * @param ordinal the position of this sub-agent within the parent's ordered list
     */
    suspend fun addSubAgent(agentId: UUID, subAgentId: UUID, ordinal: Int)

    /**
     * Removes a sub-agent association from a parent agent.
     *
     * @param agentId the unique identifier of the parent agent
     * @param subAgentId the unique identifier of the sub-agent to disassociate
     */
    suspend fun removeSubAgent(agentId: UUID, subAgentId: UUID)

    /**
     * Replaces the entire set of sub-agents for a parent agent with the provided list.
     * Existing sub-agent associations are removed and replaced by the new set,
     * with ordinal positions determined by the list order.
     *
     * @param agentId the unique identifier of the parent agent
     * @param subAgentIds the ordered list of agent identifiers to set as sub-agents
     */
    suspend fun setSubAgents(agentId: UUID, subAgentIds: List<UUID>)

    /**
     * Retrieves the tools associated with the specified agent.
     *
     * @param agentId the unique identifier of the agent
     * @return the list of tools available to the agent
     */
    suspend fun getTools(agentId: UUID): List<AgentTool>

    /**
     * Associates an existing tool with an agent, making it available for use
     * during the agent's interactions.
     *
     * @param agentId the unique identifier of the agent
     * @param toolId the unique identifier of the tool to associate
     */
    suspend fun addTool(agentId: UUID, toolId: UUID)

    /**
     * Removes a tool association from an agent.
     *
     * @param agentId the unique identifier of the agent
     * @param toolId the unique identifier of the tool to disassociate
     */
    suspend fun removeTool(agentId: UUID, toolId: UUID)

    /**
     * Replaces the entire set of tools for an agent with the provided list.
     * Existing tool associations are removed and replaced by the new set.
     *
     * @param agentId the unique identifier of the agent
     * @param toolIds the list of tool identifiers to associate with the agent
     */
    suspend fun setTools(agentId: UUID, toolIds: List<UUID>)

    /**
     * Returns a `key → id` map for all agents. Loads only the two columns (no description,
     * no configuration), so it is safe to call on every webhook even when the table grows.
     */
    suspend fun getKeyIndex(): Map<String, UUID>

    /** Find an agent by its (gitRepositoryId, gitPath) Git linkage, if any. */
    suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): Agent?

    /** Set or clear the agent's Git linkage. Pass nulls to unlink. */
    suspend fun linkToGit(id: UUID, repositoryId: UUID?, gitPath: String?)

    /** Set or clear the agent's last sync error message. */
    suspend fun setSyncError(id: UUID, error: String?)
}
