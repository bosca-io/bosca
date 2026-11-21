package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.PipelineRunStatus
import bosca.workops.ui.graphql.WorkOpsPipelineApproval
import bosca.workops.ui.graphql.WorkOpsResolvePipelineApproval
import kotlinx.serialization.Serializable

/** Adapts WorkOps approval notifications to the existing platform run and decision APIs. */
@Serializable
class WorkOpsApprovalModel(
    val id: String,
    var run: WorkOpsApprovalRunRecord? = null,
    var loaded: Boolean = false,
    var editing: Boolean = false,
    var draft: WorkOpsApprovalDraft = WorkOpsApprovalDraft(),
    var acknowledgement: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    var acknowledgementSequence: Int = 0,
) {
    val gates get() = run?.gates.orEmpty().takeIf { loaded && run?.status == "SUSPENDED" }.orEmpty()
    val statusLabel get() = if (run?.status == "OK") "Completed" else workOpsNotificationLabel(run?.status.orEmpty())
    val decisionLabel get() = if (draft.approved) "Approve" else "Reject"

    private suspend fun read(ctx: RenderContext) {
        loaded = false
        val result = ctx.gql.execute(WorkOpsPipelineApproval, WorkOpsPipelineApproval.Variables(id)).pipelines.run
        run = result?.let { value ->
            require(value.id == id) { "The returned approval run does not match this request." }
            WorkOpsApprovalRunRecord(value.id, value.status.name, value.error, value.modifiedAt,
                value.awaitingNodes.filter { it.type == "gate.approval" }.map { node ->
                    WorkOpsApprovalGateRecord(node.runId ?: value.id, node.nodeId, node.name.ifBlank { "Pipeline approval" })
                }.takeIf { value.status == PipelineRunStatus.SUSPENDED }.orEmpty())
        }
        loaded = true
    }

    suspend fun refresh(ctx: RenderContext) = feedback.perform { read(ctx) }

    fun begin(runId: String, nodeId: String, approved: Boolean) {
        val gate = requireNotNull(gates.firstOrNull { it.runId == runId && it.nodeId == nodeId }) { "Reload this approval request before deciding." }
        draft = WorkOpsApprovalDraft(gate.runId, gate.nodeId, gate.prompt, approved)
        acknowledgement = ""
        editing = true
    }

    fun cancel() { editing = false }

    suspend fun decide(note: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        draft = draft.copy(note = note)
        require(editing) { "Choose an approval decision first." }
        require(confirmed) { "Confirm this decision." }
        read(ctx)
        require(gates.any { it.runId == draft.runId && it.nodeId == draft.nodeId }) { "This approval is no longer waiting. Review the current run status." }
        check(ctx.gql.execute(WorkOpsResolvePipelineApproval, WorkOpsResolvePipelineApproval.Variables(
            draft.runId, draft.nodeId, draft.approved, draft.note.trim().takeIf(String::isNotBlank),
        )).pipelines.resolveGate) { "The decision was not recorded." }
        editing = false
        acknowledgement = if (draft.approved) "Approval recorded." else "Rejection recorded."
        acknowledgementSequence++
        read(ctx)
    }
}

suspend fun workOpsApproval(): WorkOpsApprovalModel {
    val ctx = currentRenderContext()
    return WorkOpsApprovalModel(ctx.params["id"].orEmpty()).also { it.refresh(ctx) }
}
