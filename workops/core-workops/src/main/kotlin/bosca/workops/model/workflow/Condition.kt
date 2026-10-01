package bosca.workops.model.workflow

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Predicate evaluated by the workflow engine before a transition is
 * allowed (R4). Conditions are pure — no side effects, no I/O beyond
 * loading the row they need to inspect — so the engine can short-
 * circuit on the first failure and the same evaluator can answer
 * "what transitions does this principal currently see?" without
 * mutating state.
 *
 * The sealed hierarchy is `@Serializable` with a `@SerialName` discriminator
 * so it round-trips through the `workflow_transition.conditions` JSONB
 * column without a custom registry. Variants whose owning phase hasn't
 * shipped yet (e.g. [HasFieldValue] depends on Phase 4 custom fields,
 * [HasProjectRole] / [IsInProjectRoles] / [HasGlobalPermission] depend
 * on Phase 7) evaluate to `false` until those phases ship — this is the
 * "deny-by-default until the dependency lands" path that keeps Phase 3
 * runtime honest while preserving the persisted shape across phases.
 */
@Serializable
sealed class Condition {

    /** Always passes. Useful as a default for transitions with no gating. */
    @Serializable
    @kotlinx.serialization.SerialName("Always")
    data object Always : Condition()

    /** Always fails. Mostly useful for testing; admins typically remove the transition instead. */
    @Serializable
    @kotlinx.serialization.SerialName("Never")
    data object Never : Condition()

    /** The acting principal is the task's assignee. */
    @Serializable
    @kotlinx.serialization.SerialName("IsAssignee")
    data object IsAssignee : Condition()

    /** The acting principal is the task's reporter. */
    @Serializable
    @kotlinx.serialization.SerialName("IsReporter")
    data object IsReporter : Condition()

    /**
     * The acting principal sits in a specific project role (R11).
     * Phase 7 wires the project-role evaluator; until then this
     * condition denies.
     */
    @Serializable
    @kotlinx.serialization.SerialName("HasProjectRole")
    data class HasProjectRole(@Contextual val roleId: UUID) : Condition()

    /** The acting principal sits in any of the listed project roles. Phase 7 dependency. */
    @Serializable
    @kotlinx.serialization.SerialName("IsInProjectRoles")
    data class IsInProjectRoles(val roleIds: List<@Contextual UUID>) : Condition() {
        init { require(roleIds.isNotEmpty()) { "IsInProjectRoles requires at least one roleId" } }
    }

    /**
     * The acting principal holds a specific global Bosca permission.
     * Phase 7 wires the resolver against `core-security`; until then
     * this condition denies.
     */
    @Serializable
    @kotlinx.serialization.SerialName("HasGlobalPermission")
    data class HasGlobalPermission(val permission: String) : Condition() {
        init { require(permission.isNotBlank()) { "HasGlobalPermission requires a non-blank permission" } }
    }

    /**
     * The task's named field equals / passes a value test (R5 custom
     * fields). Phase 4 wires the field reader; until then this
     * condition denies.
     */
    @Serializable
    @kotlinx.serialization.SerialName("HasFieldValue")
    data class HasFieldValue(
        val fieldKey: String,
        val operator: ComparisonOperator,
        val value: JsonElement,
    ) : Condition() {
        init { require(fieldKey.isNotBlank()) { "HasFieldValue requires a non-blank fieldKey" } }
    }

    /** All of the listed conditions must pass. Empty list passes (vacuous AND). */
    @Serializable
    @kotlinx.serialization.SerialName("AllOf")
    data class AllOf(val conditions: List<Condition>) : Condition()

    /** Any one of the listed conditions must pass. Empty list fails (vacuous OR). */
    @Serializable
    @kotlinx.serialization.SerialName("AnyOf")
    data class AnyOf(val conditions: List<Condition>) : Condition()

    /** Negates the wrapped condition. */
    @Serializable
    @kotlinx.serialization.SerialName("Not")
    data class Not(val condition: Condition) : Condition()
}

/** Comparison operators used by [Condition.HasFieldValue]. */
@Serializable
enum class ComparisonOperator {
    EQ, NEQ, LT, LTE, GT, GTE, IN, NOT_IN, CONTAINS, EXISTS,
}
