package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.bulk.BulkOperation
import bosca.workops.model.bulk.BulkOperationHandle

/**
 * R24 — runs the supplied [BulkOperation] against every task that
 * matches the BQL filter. The dry-run mode counts matches without
 * mutating, returning the same handle shape so the UI can preview
 * scope.
 *
 * Phase 10 ships the orchestrator + the simplest variants
 * (EditField summary, AssignTo, Delete). Other variants raise
 * [PendingPhaseImplementationException]; admins author them today
 * but the executor honors them when the dependency lands.
 */
interface BulkOperationService : Service {
    suspend fun execute(
        bql: String,
        operation: BulkOperation,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        dryRun: Boolean,
    ): BulkOperationHandle
}
