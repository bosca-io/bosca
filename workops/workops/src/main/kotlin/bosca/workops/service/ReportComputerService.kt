package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.report.ReportInput
import bosca.workops.model.report.ReportKind
import bosca.workops.model.report.ReportPoint
import bosca.workops.model.report.ReportResult
import bosca.workops.repository.ReportComputerRepository

@ServiceImplementation
class ReportComputerServiceImpl(
    private val repository: ReportComputerRepository,
) : ReportComputerService {

    override suspend fun compute(kind: ReportKind, input: ReportInput): ReportResult = when (kind) {
        ReportKind.ISSUE_TYPE_DISTRIBUTION -> issueTypeDistribution(input)
        ReportKind.PRIORITY_DISTRIBUTION -> priorityDistribution(input)
        ReportKind.CREATED_VS_RESOLVED -> createdVsResolved(input)
        ReportKind.SLA_COMPLIANCE -> slaCompliance(input)
        ReportKind.VELOCITY -> velocity(input)
        else -> throw PendingPhaseImplementationException(
            variant = "ReportComputer.$kind", owningPhase = 18,
        )
    }

    private suspend fun issueTypeDistribution(input: ReportInput): ReportResult {
        val rows = when (input) {
            is ReportInput.ScopeOnly -> repository.countByTaskType(input.projectId)
            else -> repository.countByTaskType(null)
        }
        return ReportResult(
            kind = ReportKind.ISSUE_TYPE_DISTRIBUTION,
            series = mapOf("count" to rows.map { ReportPoint(it.label, it.count.toDouble()) }),
            labels = rows.map { it.label },
        )
    }

    private suspend fun priorityDistribution(input: ReportInput): ReportResult {
        val rows = when (input) {
            is ReportInput.ScopeOnly -> repository.countByPriority(input.projectId)
            else -> repository.countByPriority(null)
        }
        return ReportResult(
            kind = ReportKind.PRIORITY_DISTRIBUTION,
            series = mapOf("count" to rows.map { ReportPoint(it.label, it.count.toDouble()) }),
            labels = rows.map { it.label },
        )
    }

    private suspend fun createdVsResolved(input: ReportInput): ReportResult {
        val (from, to, projectId) = when (input) {
            is ReportInput.DateRange -> Triple(input.from, input.to, input.projectId)
            else -> throw PendingPhaseImplementationException(
                variant = "CreatedVsResolved without a DateRange input", owningPhase = 18,
            )
        }
        val rows = repository.createdVsResolvedByDay(from, to, projectId)
        val created = rows.map { ReportPoint(it.day, it.created.toDouble()) }
        val resolved = rows.map { ReportPoint(it.day, it.resolved.toDouble()) }
        return ReportResult(
            kind = ReportKind.CREATED_VS_RESOLVED,
            series = mapOf("created" to created, "resolved" to resolved),
            labels = rows.map { it.day },
        )
    }

    private suspend fun slaCompliance(input: ReportInput): ReportResult {
        val projectId = (input as? ReportInput.ScopeOnly)?.projectId
        val row = repository.slaCompliance(projectId)
        return ReportResult(
            kind = ReportKind.SLA_COMPLIANCE,
            series = mapOf(
                "MET" to listOf(ReportPoint("MET", row.met.toDouble())),
                "BREACHED" to listOf(ReportPoint("BREACHED", row.breached.toDouble())),
                "STOPPED_EARLY" to listOf(ReportPoint("STOPPED_EARLY", row.stoppedEarly.toDouble())),
                "OPEN" to listOf(ReportPoint("OPEN", row.open.toDouble())),
            ),
            labels = listOf("MET", "BREACHED", "STOPPED_EARLY", "OPEN"),
        )
    }

    private suspend fun velocity(input: ReportInput): ReportResult {
        val sprintId = (input as? ReportInput.Sprint)?.sprintId
            ?: throw PendingPhaseImplementationException(
                variant = "Velocity without a Sprint input", owningPhase = 18,
            )
        val row = repository.sprintVelocity(sprintId)
        return ReportResult(
            kind = ReportKind.VELOCITY,
            series = mapOf(
                "committed" to listOf(ReportPoint("committed", row.committed.toDouble())),
                "completed" to listOf(ReportPoint("completed", row.completed.toDouble())),
            ),
            labels = listOf("committed", "completed"),
        )
    }
}
