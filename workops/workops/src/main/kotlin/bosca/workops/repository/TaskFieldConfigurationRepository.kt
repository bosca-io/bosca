package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.fields.TaskFieldConfiguration
import bosca.workops.model.fields.TaskFieldConfigurationScheme
import kotlinx.serialization.json.JsonElement

/**
 * Reads and writes [TaskFieldConfigurationScheme] and
 * [TaskFieldConfiguration] rows. The scheme is the project-level
 * binding; configurations within a scheme declare per-(taskType,
 * fieldKey) behavior.
 */
@Repository
interface TaskFieldConfigurationRepository {

    @Query("select * from workops.task_field_configuration_scheme order by name")
    suspend fun listSchemes(): List<TaskFieldConfigurationScheme>

    @Query("select * from workops.task_field_configuration_scheme where id = :id")
    suspend fun getSchemeById(id: UUID): TaskFieldConfigurationScheme?

    @Query("select * from workops.task_field_configuration_scheme where id = any(:ids)")
    suspend fun getSchemesByIds(ids: List<UUID>): List<TaskFieldConfigurationScheme>

    @Query(
        """
        insert into workops.task_field_configuration_scheme (name, description)
        values (:name, :description)
        returning *
        """
    )
    suspend fun addScheme(name: String, description: String?): TaskFieldConfigurationScheme

    /**
     * Configurations applicable to a (scheme, taskType) pair —
     * includes both task-type-specific rows and the scheme-wide
     * defaults (rows with `task_type_id is null`). The service
     * collapses overlap (a task-type-specific row overrides the
     * scheme-wide default for the same field key).
     */
    @Query(
        """
        select * from workops.task_field_configuration
        where scheme_id = :schemeId
          and (task_type_id = :taskTypeId or task_type_id is null)
        """
    )
    suspend fun listConfigurationsForTaskType(schemeId: UUID, taskTypeId: UUID): List<TaskFieldConfiguration>

    @Query(
        """
        select * from workops.task_field_configuration
        where scheme_id = :schemeId
        """
    )
    suspend fun listConfigurationsByScheme(schemeId: UUID): List<TaskFieldConfiguration>

    @Query(
        """
        insert into workops.task_field_configuration
            (scheme_id, task_type_id, field_key, required, hidden, default_value_expression, help_text)
        values
            (:schemeId, :taskTypeId, :fieldKey, :required, :hidden, :defaultValueExpression::jsonb, :helpText)
        returning *
        """
    )
    suspend fun addConfiguration(
        schemeId: UUID,
        taskTypeId: UUID?,
        fieldKey: String,
        required: Boolean,
        hidden: Boolean,
        defaultValueExpression: JsonElement?,
        helpText: String?,
    ): TaskFieldConfiguration

    @Query("delete from workops.task_field_configuration where id = :id")
    suspend fun deleteConfiguration(id: UUID)
}
