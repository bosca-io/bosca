package bosca.workops.service

import bosca.service.Service
import bosca.workops.model.spec.Spec
import bosca.workops.model.requirement.Requirement

/** Completion policy shared by every specification transition caller. */
interface SpecCompletionService : Service {
    /**
     * Returns the reasons [spec] cannot enter DONE under its current effective guidance.
     * The specification service supplies the complete active [requirements] snapshot. Checks all
     * requirements and generated tasks, active child statuses (not stored rollup counters), current interface review,
     * and passing validation bound to current documents, task versions, and guidance.
     * Missing or soft-deleted specification and requirement metadata cannot supply a document.
     * Document checks use the content services' ordinary reads and cache semantics.
     * Evidence records caller observations; this method does not run tests or establish phase order.
     * Call within the transition transaction before changing the specification status. The caller must
     * guard the final write with the completion revision read before validation; a contributing
     * mutation invalidates that revision atomically at commit, including mutations through content APIs.
     */
    suspend fun issues(spec: Spec, requirements: List<Requirement>): List<String>
}
