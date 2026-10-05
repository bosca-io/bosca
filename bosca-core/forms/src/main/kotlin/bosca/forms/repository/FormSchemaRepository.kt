package bosca.forms.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.forms.model.FormSchema
import bosca.forms.model.FormSchemaType
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Data access for form schema definitions stored in the form_schemas table.
 * Supports upsert semantics keyed by the schema's unique string key.
 */
@Repository
interface FormSchemaRepository {

    @Query("select * from form_schemas where deleted = false order by name")
    suspend fun getAll(): List<FormSchema>

    @Query("select * from form_schemas where type = (:type)::form_schema_type and deleted = false order by name")
    suspend fun getByType(type: FormSchemaType): List<FormSchema>

    @Query("select * from form_schemas where key = :key and deleted = false")
    suspend fun getByKey(key: String): FormSchema?

    @Query("select * from form_schemas where id = :id and deleted = false")
    suspend fun getById(id: UUID): FormSchema?

    @Query(
        """
        insert into form_schemas (type, key, name, description, schema, ui_schema, configuration, profile_mapping, public)
        values ((:type)::form_schema_type, :key, :name, :description, :schema, :uiSchema, :configuration, :profileMapping, :public)
        on conflict (key) do update set
            type = (:type)::form_schema_type,
            name = :name,
            description = :description,
            schema = :schema,
            ui_schema = :uiSchema,
            configuration = :configuration,
            profile_mapping = :profileMapping,
            public = :public,
            version = form_schemas.version + 1,
            modified = now()
        returning *
        """
    )
    suspend fun upsert(
        type: FormSchemaType,
        key: String,
        name: String,
        description: String,
        schema: JsonElement,
        uiSchema: JsonElement,
        configuration: JsonElement?,
        profileMapping: JsonElement?,
        public: Boolean
    ): FormSchema

    @Query("update form_schemas set published = :published, modified = now() where id = :id returning *")
    suspend fun setPublished(id: UUID, published: Boolean): FormSchema

    @Query("update form_schemas set deleted = true, modified = now() where id = :id")
    suspend fun delete(id: UUID)
}
