package bosca.workops.model.task

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * The set of [TaskType]s that a project is allowed to use, plus a
 * default for new tasks (R3). Schemes let an organization run an
 * editorial team and an engineering team on different vocabularies
 * without granting either team admin rights to the global type list.
 *
 * R3 is explicit about migration semantics: when a project's scheme is
 * changed, existing tasks whose type is no longer in the new scheme
 * remain readable and transitionable but cannot be cloned with the
 * removed type. The bulk re-typing tooling that re-homes those tasks
 * onto valid types lives in R24 (Phase 10).
 *
 * @property taskTypeIds ordered list of allowed type ids. Order
 *                       drives the UI's "create" dropdown ordering.
 * @property defaultTaskTypeId the type assigned when a caller omits
 *                             the `taskTypeId` argument on
 *                             `createTask`. Must be a member of
 *                             [taskTypeIds].
 */
@BatchKey("id")
@Serializable
data class TaskTypeScheme(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    @ColumnName("task_type_ids")
    val taskTypeIds: List<@Contextual UUID>,
    @ColumnName("default_task_type_id")
    @Contextual
    val defaultTaskTypeId: UUID,
    val version: Long = 0,
)

/** Input for [TaskTypeScheme] create / update. */
@Serializable
data class TaskTypeSchemeInput(
    val name: String,
    val description: String? = null,
    val taskTypeIds: List<@Contextual UUID>,
    @Contextual
    val defaultTaskTypeId: UUID,
)
