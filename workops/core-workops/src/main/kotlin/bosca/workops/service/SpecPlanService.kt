package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.spec.CreateSpecPlanInput
import bosca.workops.model.spec.RequirementContentInput
import bosca.workops.model.spec.Spec

/** Coordinates atomic creation of plans and their populated requirement tasks. */
interface SpecPlanService : Service {
    /** Replays an identical principal-scoped request, rejecting reuse with different input. */
    suspend fun create(input: CreateSpecPlanInput, actingPrincipalId: UUID, actingProfileId: UUID): Spec

    /** Creates one complete requirement atomically, with the same retry contract as [create]. */
    suspend fun addRequirement(specId: UUID, requestId: UUID, input: RequirementContentInput, actingPrincipalId: UUID, actingProfileId: UUID): Requirement
}
