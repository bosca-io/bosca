package bosca.workops.model.guidance

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
enum class WorkOpsGuidancePhase { INVESTIGATION, SPECIFICATION, INTERFACES, IMPLEMENTATION, VERIFICATION }

/** Executable requirements are independent of the editable guide text. */
@Serializable
data class WorkOpsGuidancePolicy(
    val requireInterfaceReview: Boolean = true,
    val requireTdd: Boolean = true,
    val requireTestQuality: Boolean = true,
    val minimumLineCoverage: Double = 99.0,
    val minimumBranchCoverage: Double = 97.0,
)

@Serializable
data class WorkOpsGuideInput(val phase: WorkOpsGuidancePhase, val name: String, val description: String, val markdown: String)

/** Null policy and omitted guides inherit the platform defaults. Saving replaces the scope's overrides. */
@Serializable
data class WorkOpsGuidanceInput(val policy: WorkOpsGuidancePolicy? = null, val guides: List<WorkOpsGuideInput> = emptyList())

@Serializable
data class WorkOpsGuide(
    val phase: WorkOpsGuidancePhase,
    val name: String,
    val description: String,
    val markdown: String,
    val inherited: Boolean,
    @Contextual val metadataId: UUID? = null,
    val documentVersion: Int? = null,
    @Contextual val resourceId: UUID? = null,
)

/** Revision fingerprints effective content and override choices; hidden upstream edits and no-op saves do not change it. */
@Serializable
data class WorkOpsGuidance(
    val revision: String,
    val policy: WorkOpsGuidancePolicy,
    val policyInherited: Boolean,
    val guides: List<WorkOpsGuide>,
)
