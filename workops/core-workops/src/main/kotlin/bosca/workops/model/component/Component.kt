package bosca.workops.model.component

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@DbMapper(ComponentAssigneeModeMapper::class)
@Serializable
enum class ComponentAssigneeMode {
    /** Leave the task unassigned. */
    UNASSIGNED,

    /** Use the component's `leadProfileId`. */
    COMPONENT_LEAD,

    /** Use the project's `ownerProfileId`. */
    PROJECT_DEFAULT,

    /** Component lead first, project owner second. */
    COMPONENT_LEAD_OR_PROJECT_DEFAULT,
}

object ComponentAssigneeModeMapper : EnumMapper<ComponentAssigneeMode>({ ComponentAssigneeMode.valueOf(it.uppercase()) })

/**
 * A team / codebase ownership tag inside a project (R9). Tasks
 * reference components via `componentIds` (already a column on
 * `workops.task` from V2).
 */
@BatchKey("id")
@Serializable
data class Component(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    val name: String,
    val description: String? = null,
    @ColumnName("default_assignee_profile_id")
    @Contextual
    val defaultAssigneeProfileId: UUID? = null,
    @ColumnName("lead_profile_id")
    @Contextual
    val leadProfileId: UUID? = null,
    @ColumnName("assignee_mode")
    val assigneeMode: ComponentAssigneeMode = ComponentAssigneeMode.UNASSIGNED,
    val version: Long = 0,
)

/** Input for creating a [Component]. */
@Serializable
data class CreateComponentInput(
    @Contextual
    val projectId: UUID,
    val name: String,
    val description: String? = null,
    @Contextual
    val defaultAssigneeProfileId: UUID? = null,
    @Contextual
    val leadProfileId: UUID? = null,
    val assigneeMode: ComponentAssigneeMode = ComponentAssigneeMode.UNASSIGNED,
)
