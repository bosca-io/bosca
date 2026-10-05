package bosca.storage.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemInput
import bosca.storage.model.StorageSystemModel

/**
 * Service for managing [StorageSystem] configurations.
 *
 * Storage systems represent configurable backend storage providers (e.g., S3, local filesystem,
 * search indexes) along with their associated model configurations. This service provides
 * CRUD operations for storage system definitions and their model bindings.
 */
interface StorageSystemService : Service {

    /**
     * Retrieves all registered storage systems.
     *
     * @return a list of all storage systems
     */
    suspend fun getAll(): List<StorageSystem>

    /**
     * Retrieves a storage system by its unique identifier.
     *
     * @param id the unique identifier of the storage system
     * @return the [StorageSystem], or `null` if not found
     */
    suspend fun get(id: UUID): StorageSystem?

    /**
     * Retrieves a storage system by its unique name.
     *
     * @param name the name of the storage system to look up
     * @return the [StorageSystem], or `null` if no system has that name
     */
    suspend fun getByName(name: String): StorageSystem?

    /**
     * Retrieves all model configurations associated with a specific storage system.
     *
     * @param systemId the unique identifier of the storage system
     * @return a list of [StorageSystemModel] entries bound to the specified system
     */
    suspend fun getModels(systemId: UUID): List<StorageSystemModel>

    /**
     * Creates a new storage system from the given input, including any model bindings.
     *
     * @param input the storage system definition and model configurations to persist
     * @return the newly created [StorageSystem] with its assigned ID
     */
    suspend fun add(input: StorageSystemInput): StorageSystem

    /**
     * Updates an existing storage system's definition and model configurations.
     *
     * @param id the unique identifier of the storage system to update
     * @param input the updated storage system definition and model configurations
     * @return the updated [StorageSystem]
     */
    suspend fun edit(id: UUID, input: StorageSystemInput): StorageSystem

    /**
     * Deletes a storage system and its associated model configurations.
     *
     * @param id the unique identifier of the storage system to delete
     */
    suspend fun delete(id: UUID)
}
