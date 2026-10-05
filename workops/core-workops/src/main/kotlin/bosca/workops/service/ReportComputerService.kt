package bosca.workops.service

import bosca.service.Service
import bosca.workops.model.report.ReportInput
import bosca.workops.model.report.ReportKind
import bosca.workops.model.report.ReportResult

/**
 * R32 — single entry point that fans out to per-kind computers.
 * Phase 18 ships in-process implementations for IssueType,
 * Priority, CreatedVsResolved, and SLACompliance distributions
 * (the cheapest aggregations) plus a placeholder Velocity reading
 * derived from the existing sprint-aggregate query. The
 * data-heavy time-series computers (BurnDown, BurnUp, Cycle/Lead
 * time, ControlChart) raise PendingPhaseImplementationException
 * until the Iceberg/Trino integration lands per spec.
 */
interface ReportComputerService : Service {
    suspend fun compute(kind: ReportKind, input: ReportInput): ReportResult
}
