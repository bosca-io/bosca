package bosca.experimentation.model

import kotlinx.serialization.Serializable

/**
 * Current feature-flag assignment count for one variation.
 *
 * Computed from one operational assignment row per flag and installation.
 * A principal may own the row, but ownership never creates another assignment.
 * Repeated evaluation of the same variation does not change that row or its
 * assignment time. Historical transitions are emitted to analytics instead of
 * being retained in the operational table.
 *
 * @property variationKey variation currently assigned to the counted subjects
 * @property assignmentCount number of current assignments to the variation
 */
@Serializable
data class VariationAssignmentCount(
    val variationKey: String,
    val assignmentCount: Long,
)
