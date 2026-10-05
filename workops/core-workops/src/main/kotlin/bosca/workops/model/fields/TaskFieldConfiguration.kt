package bosca.workops.model.fields

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One row in the per-(scheme, taskType, fieldKey) configuration
 * table (R5). Declares whether the field is required, whether it is
 * hidden from non-manager reads, an optional default value, and
 * help text the UI surfaces.
 *
 * `taskTypeId == null` means "applies to every task type within the
 * scheme" — the row is the scheme-wide default for that key.
 *
 * The `defaultValueExpression` column is a literal `JsonElement` in
 * Phase 4. Phase 6 (BQL) extends it with expression evaluation
 * (`currentUser()`, `now()`, etc.) but the persisted shape stays
 * the same.
 */
@BatchKey("id")
@Serializable
data class TaskFieldConfiguration(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("scheme_id")
    @Contextual
    val schemeId: UUID,
    @ColumnName("task_type_id")
    @Contextual
    val taskTypeId: UUID? = null,
    @ColumnName("field_key")
    val fieldKey: String,
    val required: Boolean = false,
    val hidden: Boolean = false,
    @ColumnName("default_value_expression")
    val defaultValueExpression: JsonElement? = null,
    @ColumnName("help_text")
    val helpText: String? = null,
)

/**
 * Aggregates [TaskFieldConfiguration] rows under one named scheme
 * (R5). A project's `default_field_configuration_scheme_id` points
 * here; the service composes the effective configuration for a task
 * by joining the scheme's rows against the task's task-type id.
 */
@BatchKey("id")
@Serializable
data class TaskFieldConfigurationScheme(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    val version: Long = 0,
)

/** Input for creating a [TaskFieldConfiguration]. */
@Serializable
data class TaskFieldConfigurationInput(
    @Contextual
    val schemeId: UUID,
    @Contextual
    val taskTypeId: UUID? = null,
    val fieldKey: String,
    val required: Boolean = false,
    val hidden: Boolean = false,
    val defaultValueExpression: JsonElement? = null,
    val helpText: String? = null,
)
