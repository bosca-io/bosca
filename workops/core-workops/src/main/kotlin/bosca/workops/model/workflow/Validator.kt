package bosca.workops.model.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A validator runs *after* every condition passes (R4) and rejects
 * the transition with a user-visible message if its invariant is
 * violated. Validators are pure — like conditions, they may inspect
 * state but cannot mutate it.
 *
 * Whereas a [Condition] gates *who* can run a transition,
 * a [Validator] gates *what shape* the task must be in to run it.
 * "Reporter must be set" is a condition; "Resolution must be set
 * before Done" is a validator.
 */
@Serializable
sealed class Validator {

    /**
     * The named field must be non-null. Phase 4 wires the custom-field
     * reader; for built-in columns (`assignee`, `description`, etc.)
     * Phase 3 already evaluates against the `Task` row.
     */
    @Serializable
    @kotlinx.serialization.SerialName("RequireField")
    data class RequireField(val fieldKey: String) : Validator()

    /** The named field's value must match a JSON-encoded predicate. */
    @Serializable
    @kotlinx.serialization.SerialName("RequireFieldValue")
    data class RequireFieldValue(
        val fieldKey: String,
        val operator: ComparisonOperator,
        val value: JsonElement,
    ) : Validator()

    /** A resolution must be set on the task — typical of "Resolve" / "Close" transitions. */
    @Serializable
    @kotlinx.serialization.SerialName("RequireResolution")
    data object RequireResolution : Validator()

    /**
     * A non-blank comment must be supplied on the transition — typical
     * of "Won't Fix" or "Reject" actions.
     */
    @Serializable
    @kotlinx.serialization.SerialName("RequireComment")
    data object RequireComment : Validator()

    /** Every sub-task of the current task must be in a `DONE` or `CANCELLED` status. */
    @Serializable
    @kotlinx.serialization.SerialName("RequireSubtasksResolved")
    data object RequireSubtasksResolved : Validator()

    /**
     * Run a registered Bosca script as a validator (Phase 11). Until
     * scripting is wired, this validator denies all transitions that
     * reference it.
     */
    @Serializable
    @kotlinx.serialization.SerialName("Custom")
    data class Custom(val scriptKey: String) : Validator()
}
