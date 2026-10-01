package bosca.workops.model.permission

import bosca.db.annotation.ColumnName
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Per-entity permission row for a [bosca.workops.model.project.Portfolio].
 * Follows the same `(entity_id, group_id, action)` pattern as
 * `CollectionPermission` and `MetadataPermission`.
 */
@Serializable
data class PortfolioPermission(
    @ColumnName("portfolio_id")
    @Contextual
    val portfolioId: UUID,
    @ColumnName("group_id")
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction,
) : EntityPermission {
    @Transient
    override val entityId: UUID = portfolioId
}

/**
 * Per-entity permission row for a [bosca.workops.model.project.Program].
 */
@Serializable
data class ProgramPermission(
    @ColumnName("program_id")
    @Contextual
    val programId: UUID,
    @ColumnName("group_id")
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction,
) : EntityPermission {
    @Transient
    override val entityId: UUID = programId
}

/**
 * Per-entity permission row for a [bosca.workops.model.project.Project].
 */
@Serializable
data class ProjectPermission(
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("group_id")
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction,
) : EntityPermission {
    @Transient
    override val entityId: UUID = projectId
}

/**
 * Per-entity permission row for a [bosca.workops.model.environment.Environment]:
 * environment-targeting actions (approve, deploy, rollback) require permission on the environment
 * itself, evaluated exactly like a Metadata.
 */
@Serializable
data class EnvironmentPermission(
    @ColumnName("environment_id")
    @Contextual
    val environmentId: UUID,
    @ColumnName("group_id")
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction,
) : EntityPermission {
    @Transient
    override val entityId: UUID = environmentId
}

/**
 * Per-entity permission row for a [bosca.workops.model.task.Task].
 */
@Serializable
data class TaskPermission(
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID,
    @ColumnName("group_id")
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction,
) : EntityPermission {
    @Transient
    override val entityId: UUID = taskId
}

@Serializable
data class SpecPermission(
    @ColumnName("spec_id")
    @Contextual
    val specId: UUID,
    @ColumnName("group_id")
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction,
) : EntityPermission {
    @Transient
    override val entityId: UUID = specId
}

@Serializable
data class RequirementPermission(
    @ColumnName("requirement_id")
    @Contextual
    val requirementId: UUID,
    @ColumnName("group_id")
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction,
) : EntityPermission {
    @Transient
    override val entityId: UUID = requirementId
}
