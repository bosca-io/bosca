package bosca.scripting.service

import bosca.scripting.model.Script
import bosca.scripting.model.ScriptInput
import bosca.scripting.model.ScriptType
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.serialization.UUID

/**
 * Service for managing the lifecycle of [Script] definitions, including creation,
 * retrieval, modification, deletion, and enable/disable state transitions.
 *
 * Scripts are stored with a unique [UUID] identifier and a unique string key, and are
 * categorized by [ScriptType] (e.g., GENERAL, TRIGGER, TOOL, EPHEMERAL, or API).
 *
 * [ScriptType.EPHEMERAL] scripts support soft deletion: when deleted, they are hidden
 * from normal queries but retained in the database for diagnostic purposes.
 *
 * Extends [PermissionService] to support group-based access control on individual scripts.
 */
interface ScriptService : PermissionService<Script, UUID> {

    /**
     * Retrieves all active script definitions, excluding soft-deleted scripts.
     *
     * @return a list of all non-deleted [Script] entries, which may be empty if none exist
     */
    suspend fun getAll(): List<Script>

    /**
     * Retrieves all script definitions including soft-deleted ones, for admin diagnostics.
     *
     * @return a list of all [Script] entries regardless of deletion state
     */
    suspend fun getAllIncludingDeleted(): List<Script>

    /**
     * Retrieves all active script definitions that match a specific [ScriptType],
     * excluding soft-deleted scripts.
     *
     * @param type the script type to filter by (e.g., [ScriptType.TRIGGER], [ScriptType.TOOL])
     * @return a list of scripts matching the given type, which may be empty
     */
    suspend fun getByType(type: ScriptType): List<Script>

    /**
     * Retrieves all script definitions that match a specific [ScriptType], including
     * soft-deleted ones, for admin diagnostics.
     *
     * @param type the script type to filter by
     * @return a list of scripts matching the given type regardless of deletion state
     */
    suspend fun getByTypeIncludingDeleted(type: ScriptType): List<Script>

    /**
     * Looks up a single script by its unique identifier.
     *
     * @param id the UUID of the script to retrieve
     * @return the matching [Script], or `null` if no script with the given ID exists
     */
    suspend fun get(id: UUID): Script?

    /**
     * Retrieves multiple scripts by their unique identifiers in a single query,
     * excluding soft-deleted scripts.
     *
     * @param ids the UUIDs of the scripts to retrieve
     * @return a list of matching [Script] entries; scripts that do not exist or are soft-deleted are omitted
     */
    suspend fun getByIds(ids: Collection<UUID>): List<Script>

    /**
     * Looks up a single script by its unique string key.
     *
     * @param key the unique key assigned to the script
     * @return the matching [Script], or `null` if no script with the given key exists
     */
    suspend fun getByKey(key: String): Script?

    /**
     * Creates a new script from the provided input and persists it.
     *
     * @param input the script definition data including key, name, source code, type, and optional schemas
     * @return the newly created [Script] with its assigned ID and metadata
     */
    suspend fun add(input: ScriptInput): Script

    /**
     * Updates an existing script identified by [id] with the values from [input].
     *
     * This typically increments the script's version, which may also invalidate any
     * cached compilations in the [ScriptExecutionService].
     *
     * @param id the UUID of the script to update
     * @param input the updated script definition data
     * @return the updated [Script] reflecting the persisted changes
     */
    suspend fun edit(id: UUID, input: ScriptInput): Script

    /**
     * Deletes a script definition. For [ScriptType.EPHEMERAL] scripts, performs a soft
     * delete that hides the script from normal queries while retaining it in the database
     * for diagnostic purposes. For all other script types, performs a permanent hard delete.
     *
     * @param id the UUID of the script to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Marks a script as enabled, allowing it to be executed.
     *
     * @param id the UUID of the script to enable
     * @return the updated [Script] with its [Script.enabled] flag set to `true`
     */
    suspend fun enable(id: UUID): Script

    /**
     * Marks a script as disabled, preventing it from being executed.
     *
     * @param id the UUID of the script to disable
     * @return the updated [Script] with its [Script.enabled] flag set to `false`
     */
    suspend fun disable(id: UUID): Script

    /**
     * Grants a permission on the specified script to a security group.
     *
     * @param scriptId the UUID of the script to grant access to
     * @param groupId the UUID of the security group receiving the grant
     * @param action the permission action being granted (e.g., VIEW, EXECUTE, MANAGE)
     */
    suspend fun addPermission(scriptId: UUID, groupId: UUID, action: PermissionAction)

    /**
     * Revokes a specific permission grant from a security group on the specified script.
     *
     * @param scriptId the UUID of the script to revoke access from
     * @param groupId the UUID of the security group losing the grant
     * @param action the permission action being revoked
     */
    suspend fun deletePermission(scriptId: UUID, groupId: UUID, action: PermissionAction)

    /**
     * Returns a `key → id` map for all non-deleted scripts. Loads only the two columns —
     * avoids pulling source bodies for callers that only need to resolve keys to ids.
     */
    suspend fun getKeyIndex(): Map<String, UUID>
}
