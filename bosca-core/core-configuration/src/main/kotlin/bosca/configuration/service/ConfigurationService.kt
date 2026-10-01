package bosca.configuration.service

import bosca.configuration.model.Configuration
import bosca.configuration.model.ConfigurationInput
import bosca.security.model.PermissionService
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement


/**
 * Service for managing key-value configuration entries with access control.
 *
 * Each configuration entry has a unique string key, a description, a public/private
 * visibility flag, and an associated JSON value stored separately from the configuration
 * metadata. Extends [PermissionService] to enforce access control on [Configuration] entities.
 */
interface ConfigurationService : PermissionService<Configuration, UUID> {

    /**
     * Retrieves all configuration entries in the system.
     *
     * @return a list of all [Configuration] metadata records (values are not included)
     */
    suspend fun getAll(): List<Configuration>

    /**
     * Retrieves a configuration entry by its unique string key.
     *
     * @param key the configuration key to look up
     * @return the [Configuration] metadata if found, or `null` if no entry exists with the given key
     */
    suspend fun getByKey(key: String): Configuration?

    /**
     * Retrieves the JSON value associated with a configuration entry.
     *
     * @param id the unique identifier of the configuration entry
     * @return the stored [JsonElement] value, or `null` if no value is set
     */
    suspend fun getValue(id: UUID): JsonElement?

    /**
     * Sets or replaces the JSON value for an existing configuration entry.
     *
     * @param id the unique identifier of the configuration entry
     * @param value the JSON value to store
     */
    suspend fun setValue(id: UUID, value: JsonElement)

    /**
     * Creates or updates a configuration entry from the provided input, including
     * its key, description, value, public flag, and associated permissions.
     *
     * @param input the complete configuration specification including permissions
     * @return the created or updated [Configuration] metadata record
     */
    suspend fun setConfiguration(input: ConfigurationInput): Configuration

    /**
     * Deletes a configuration entry identified by its string key, removing both
     * the metadata and any stored value.
     *
     * @param key the configuration key to delete
     * @return the UUID of the deleted configuration entry
     */
    suspend fun deleteConfiguration(key: String): UUID
}

/**
 * Convenience extension that retrieves a configuration value by key and deserializes
 * it to the specified type [T] using the provided [Json] instance.
 *
 * @param T the target type to deserialize into
 * @param key the configuration key to look up
 * @param json the [Json] instance to use for deserialization
 * @return the deserialized value of type [T], or `null` if the key does not exist or has no value
 */
suspend inline fun <reified T> ConfigurationService.getValueAs(key: String, json: Json): T? {
    val configuration = getByKey(key) ?: return null
    val value = getValue(configuration.id) ?: return null
    return json.decodeFromJsonElement<T>(value)
}