package bosca.workops.model.workflow

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A reachable state in a [Workflow] (R4). The state binds a [Status]
 * to an optional SLA policy and to its position in the workflow; the
 * transition graph above sits on top.
 *
 * @property slaPolicyId optional SLA policy that pauses / starts on
 *                       entry into this state. Phase 8 wires the SLA
 *                       runtime; until then the field is informational.
 */
@Serializable
data class WorkflowState(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("workflow_id")
    @Contextual
    val workflowId: UUID,
    @ColumnName("status_id")
    @Contextual
    val statusId: UUID,
    @ColumnName("display_order")
    val displayOrder: Int,
    @ColumnName("sla_policy_id")
    @Contextual
    val slaPolicyId: UUID? = null,
)

/**
 * The wildcard "any state" reference used in [WorkflowTransition.fromStateIds]
 * to model transitions like "Reopen" or "Cancel" reachable from
 * everywhere without enumerating every source state.
 */
const val WILDCARD_FROM_STATE: String = "*"

/**
 * One edge in the state machine (R4). The engine evaluates conditions
 * left-to-right with short-circuit AND; if every condition passes,
 * validators run; on validator success, the status flips and post-
 * functions run inside the same transaction as the status change.
 *
 * The Conditions / Validators / PostFunctions trees ride as raw
 * [JsonElement] on the entity because Bosca's repository binder
 * lacks a `List<@Polymorphic-sealed>` ↔ jsonb mapper; the typed
 * view is decoded by the workflow service before invoking the
 * evaluator. The persisted JSON shape is the kotlinx-serialization
 * `@SerialName`-discriminated form of [Condition], [Validator],
 * [PostFunction] respectively.
 *
 * @property fromStateIds source states. A list of `WorkflowState.id`
 *                        UUIDs OR exactly the single sentinel string
 *                        [WILDCARD_FROM_STATE] to mean "any state".
 *                        The database column is `text[]` so the
 *                        sentinel and UUIDs share a slot.
 * @property screenId optional screen (Phase 4) prompted before the
 *                    transition runs — typical use: "Resolve" prompts
 *                    for resolution + fix version.
 */
@Serializable
data class WorkflowTransition(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("workflow_id")
    @Contextual
    val workflowId: UUID,
    val name: String,
    val description: String? = null,
    @ColumnName("from_state_ids")
    val fromStateIds: List<String>,
    @ColumnName("to_state_id")
    @Contextual
    val toStateId: UUID,
    val conditions: JsonElement = kotlinx.serialization.json.JsonArray(emptyList()),
    val validators: JsonElement = kotlinx.serialization.json.JsonArray(emptyList()),
    @ColumnName("post_functions")
    val postFunctions: JsonElement = kotlinx.serialization.json.JsonArray(emptyList()),
    @ColumnName("screen_id")
    @Contextual
    val screenId: UUID? = null,
) {
    init {
        require(name.isNotBlank()) { "transition name must not be blank" }
        require(fromStateIds.isNotEmpty()) { "transition must have at least one source state" }
        require(
            !fromStateIds.contains(WILDCARD_FROM_STATE) || fromStateIds == listOf(WILDCARD_FROM_STATE)
        ) { "wildcard '*' must be the sole entry in fromStateIds, not mixed with specific states" }
    }
}

/**
 * A reusable state-machine definition (R4). A workflow does not bind
 * to projects directly; the binding is via [WorkflowScheme] so two
 * projects can share the same workflow.
 */
@BatchKey("id")
@Serializable
data class Workflow(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    @ColumnName("initial_state_id")
    @Contextual
    val initialStateId: UUID? = null,
    val version: Long = 0,
)

/**
 * The mapping between a project and the [Workflow] that runs its
 * tasks (R4). The `default` workflow applies to every task type
 * unless overridden by the per-task-type override map.
 *
 * The override map is stored as a `jsonb` `{ "<taskTypeUuid>":
 * "<workflowUuid>", … }` blob (the field's [JsonElement] type) because
 * Bosca's repository binder lacks a `Map<UUID, UUID>` mapper. The
 * service layer exposes a typed [perTaskTypeWorkflowIdMap] view.
 */
@BatchKey("id")
@Serializable
data class WorkflowScheme(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    @ColumnName("default_workflow_id")
    @Contextual
    val defaultWorkflowId: UUID,
    @ColumnName("per_task_type_workflow_ids")
    val perTaskTypeWorkflowIds: JsonElement = JsonObject(emptyMap()),
    val version: Long = 0,
)
