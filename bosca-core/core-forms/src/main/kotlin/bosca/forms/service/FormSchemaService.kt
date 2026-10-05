package bosca.forms.service

import bosca.forms.model.FormSchema
import bosca.forms.model.FormSchemaInput
import bosca.forms.model.FormSchemaType
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.serialization.UUID

/**
 * Service for managing form schema definitions that pair a standard
 * JSON Schema with a Bosca UI Schema for cross-platform form rendering.
 * Extends [PermissionService] for group-based access control.
 */
interface FormSchemaService : PermissionService<FormSchema, UUID> {

    /**
     * Retrieves all form schemas in the system, ordered by name.
     *
     * @return the complete list of form schemas
     */
    suspend fun getAll(): List<FormSchema>

    /**
     * Retrieves all form schemas of a specific type, ordered by name.
     *
     * @param type the schema type to filter by
     * @return form schemas matching the type
     */
    suspend fun getByType(type: FormSchemaType): List<FormSchema>

    /**
     * Looks up a form schema by its unique string key.
     *
     * @param key the schema key (e.g. "org-profile")
     * @return the form schema, or null if no schema exists for the key
     */
    suspend fun getByKey(key: String): FormSchema?

    /**
     * Looks up a form schema by its unique identifier.
     *
     * @param id the schema UUID
     * @return the form schema, or null if not found
     */
    suspend fun getById(id: UUID): FormSchema?

    /**
     * Creates or updates a form schema. If a schema with the same key
     * already exists, it is updated and its version incremented.
     *
     * @param input the form schema definition to save
     * @return the saved form schema with updated version and timestamps
     */
    suspend fun save(input: FormSchemaInput): FormSchema

    /**
     * Sets the published state of a form schema, controlling whether
     * it is available for public rendering and submission.
     *
     * @param id the schema UUID
     * @param published true to publish, false to unpublish
     * @return the updated form schema
     */
    suspend fun setPublished(id: UUID, published: Boolean): FormSchema

    /**
     * Deletes a form schema by its unique identifier.
     *
     * @param id the schema UUID to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Grants a permission on a form schema to a security group.
     *
     * @param formSchemaId the form schema identifier
     * @param groupId the security group identifier
     * @param action the permission action to grant
     */
    suspend fun addPermission(formSchemaId: UUID, groupId: UUID, action: PermissionAction): EntityPermission

    /**
     * Revokes a permission on a form schema from a security group.
     *
     * @param formSchemaId the form schema identifier
     * @param groupId the security group identifier
     * @param action the permission action to revoke
     */
    suspend fun deletePermission(formSchemaId: UUID, groupId: UUID, action: PermissionAction): EntityPermission
}
