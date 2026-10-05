package bosca.ai.models.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.ai.models.model.Model
import bosca.ai.models.model.ModelInput

/**
 * Service for managing AI model definitions that agents reference for inference.
 *
 * A [Model] represents a specific LLM configuration (e.g., a Google Gemini or OpenAI GPT variant)
 * identified by a type string and optional configuration. Models are referenced by agents
 * via [bosca.ai.agents.model.Agent.modelId] to determine which LLM to use for chat interactions.
 */
interface ModelService : Service {

    /**
     * Retrieves all registered model definitions.
     *
     * @return the complete list of models
     */
    suspend fun getAll(): List<Model>

    /**
     * Retrieves a single model by its unique identifier.
     *
     * @param id the model's unique identifier
     * @return the matching model, or `null` if no model exists with the given [id]
     */
    suspend fun get(id: UUID): Model?

    /**
     * Retrieves a single model by its unique key string.
     *
     * @param key the model's unique key
     * @return the matching model, or `null` if no model exists with the given [key]
     */
    suspend fun getByKey(key: String): Model?

    /**
     * Creates and persists a new model definition from the given input.
     *
     * @param input the model definition including key, name, type (e.g., "google.Gemini2_5Flash"),
     *   and optional configuration
     * @return the newly created model with its generated identifier
     */
    suspend fun add(input: ModelInput): Model

    /**
     * Updates an existing model's definition.
     *
     * @param id the unique identifier of the model to update
     * @param input the updated model definition
     * @return the modified model reflecting the applied changes
     */
    suspend fun edit(id: UUID, input: ModelInput): Model

    /**
     * Permanently removes a model by its unique identifier.
     *
     * @param id the unique identifier of the model to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Returns a `key → id` map for all models. Loads only the two columns — avoids pulling
     * the full configuration JSONB on every webhook-driven sync.
     */
    suspend fun getKeyIndex(): Map<String, UUID>
}
