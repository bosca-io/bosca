package bosca.workops.model.workflow

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A post-function runs **after** validators pass and **inside the
 * same transaction** as the status change (R4). On failure the
 * transition rolls back atomically — half-applied transitions are
 * forbidden.
 *
 * Sync-safe variants ([SetField], [SetResolution], [ClearResolution],
 * [AssignTo*], [AddComment]) execute inline. Variants whose owning
 * phase isn't shipped (Phase 8 webhooks, Phase 11 scripts) are still
 * defined here so the persisted JSONB shape is stable; their runtime
 * raises a clear error until the dependency lands.
 */
@Serializable
sealed class PostFunction {

    /** Set a built-in or custom field on the task. */
    @Serializable
    @kotlinx.serialization.SerialName("SetField")
    data class SetField(val fieldKey: String, val value: JsonElement) : PostFunction() {
        init { require(fieldKey.isNotBlank()) { "SetField requires a non-blank fieldKey" } }
    }

    /** Stamp the task with the named resolution and `resolutionAt = now()`. */
    @Serializable
    @kotlinx.serialization.SerialName("SetResolution")
    data class SetResolution(@Contextual val resolutionId: UUID) : PostFunction()

    /** Clear the resolution and `resolutionAt`. */
    @Serializable
    @kotlinx.serialization.SerialName("ClearResolution")
    data object ClearResolution : PostFunction()

    /** Assign the task to its reporter. */
    @Serializable
    @kotlinx.serialization.SerialName("AssignToReporter")
    data object AssignToReporter : PostFunction()

    /** Assign the task to the principal who triggered the transition. */
    @Serializable
    @kotlinx.serialization.SerialName("AssignToCurrentUser")
    data object AssignToCurrentUser : PostFunction()

    /** Clear the assignee. */
    @Serializable
    @kotlinx.serialization.SerialName("Unassign")
    data object Unassign : PostFunction()

    /**
     * Append a rendered comment to the task as the transitioning
     * principal. Phase 4 wires the comment service; until then this
     * post-function rejects with `PendingPhase`.
     */
    @Serializable
    @kotlinx.serialization.SerialName("AddComment")
    data class AddComment(val template: String) : PostFunction() {
        init { require(template.isNotBlank()) { "AddComment requires a non-blank template" } }
    }

    /** Fire an automation webhook. Phase 8 wires this. */
    @Serializable
    @kotlinx.serialization.SerialName("EmitWebhook")
    data class EmitWebhook(@Contextual val automationRuleId: UUID) : PostFunction()

    /** Run a registered Bosca script. Phase 11 wires this. */
    @Serializable
    @kotlinx.serialization.SerialName("RunScript")
    data class RunScript(val scriptKey: String) : PostFunction() {
        init { require(scriptKey.isNotBlank()) { "RunScript requires a non-blank scriptKey" } }
    }
}
