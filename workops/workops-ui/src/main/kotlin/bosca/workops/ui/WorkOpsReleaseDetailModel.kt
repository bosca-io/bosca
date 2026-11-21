package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Read-only release facts refresh independently of release and pipeline editor islands. */
@Serializable
class WorkOpsReleaseDetailModel(
    val id: String,
    var release: WorkOpsReleaseRecord? = null,
    var programName: String = "",
    var components: List<WorkOpsReleaseComponentRecord> = emptyList(),
    var componentsLoaded: Boolean = false,
    var readiness: List<String> = emptyList(),
    var readinessLoaded: Boolean = false,
    var canManage: Boolean = false,
    var accessLoaded: Boolean = false,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    val componentFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val labelFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val readinessFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val accessFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    var ci: WorkOpsReleaseCiModel? = null,
    var artifacts: WorkOpsReleaseArtifactsModel? = null,
    var notes: WorkOpsReleaseNotesModel? = null,
    var revision: Long = 0,
    var ciContextRevision: Long = 0,
    var environments: WorkOpsReleaseEnvironmentsModel? = null,
) {
    val title get() = release?.name ?: if (feedback.failed) "Could not load release" else "Release unavailable"
    val listHref get() = "/workops/releases" + release?.programId?.let { "?programId=${workOpsEncode(it)}" }.orEmpty()
    val deployedCount get() = components.count { it.status.uppercase() == "DEPLOYED" }
    val pendingCount get() = components.size - deployedCount
    val lifecycle get() = when {
        release?.released?.isNotBlank() == true -> "Released"
        release?.statusLoaded != true || !componentsLoaded -> "Status unavailable"
        release?.latestStatus == "QUEUED" -> "Queued"
        release?.latestStatus == "RUNNING" || components.any { it.status.uppercase() == "DEPLOYING" } -> "Deploying"
        release?.latestStatus == "FAILURE" -> "Failed"
        release?.latestStatus == "CANCELLED" -> "Cancelled"
        components.isNotEmpty() && deployedCount == components.size -> "Deployed"
        deployedCount > 0 -> "Deploying"
        release?.latestStatus == "SUCCESS" -> "Run succeeded"
        release?.latestStatus == "SKIPPED" -> "Skipped"
        release?.latestStatus?.isNotBlank() == true -> "Unknown status"
        else -> "Draft"
    }

    suspend fun reload(ctx: RenderContext) {
        release = ctx.gql.execute(WorkOpsReleaseDetail, WorkOpsReleaseDetail.Variables(id)).workOps.crossProject.release?.releaseRecord()
        val row = release ?: return
        programName = ctx.gql.execute(WorkOpsReleasePrograms, Unit).workOps.programs.all.firstOrNull { it.id == row.programId }?.name ?: "Program unavailable"
        val status = WorkOpsFeedback()
        status.perform {
            val run = ctx.gql.execute(WorkOpsReleaseRunStatuses, WorkOpsReleaseRunStatuses.Variables(id)).workOps.crossProject.releaseRuns.firstOrNull()
            release = row.copy(latestRunId = run?.runId.orEmpty(), latestStatus = run?.status.orEmpty(), statusLoaded = true)
        }
        if (status.failed) release = row.copy(statusError = status.message)
        reloadComponents(ctx)
        readinessLoaded = false
        readinessFeedback.perform {
            readiness = ctx.gql.execute(WorkOpsReleaseReadiness, WorkOpsReleaseReadiness.Variables(id)).workOps.crossProject.releaseReadiness
            readinessLoaded = true
        }
        canManage = false; accessLoaded = false
        accessFeedback.perform {
            canManage = ctx.gql.execute(WorkOpsReleaseAccess, WorkOpsReleaseAccess.Variables(id)).workOps.crossProject.releaseActionAccess?.canManage == true
            accessLoaded = true
        }
    }
    suspend fun reloadComponents(ctx: RenderContext) {
        componentsLoaded = false
        componentFeedback.perform {
            components = workOpsReleaseComponents(id, ctx); componentsLoaded = true
            labelFeedback.perform { components = workOpsReleaseComponentLabels(components, ctx) }
        }
    }
    suspend fun retry(ctx: RenderContext) {
        feedback.perform { reload(ctx) }
        revision++; ciContextRevision++
    }
    /** CI owns fresh run facts; invalidate its siblings without echoing another CI read. */
    suspend fun ciChanged(ctx: RenderContext) {
        feedback.perform { reload(ctx) }
        revision++
    }
}

suspend fun workOpsReleaseDetail(): WorkOpsReleaseDetailModel {
    val ctx = currentRenderContext()
    return WorkOpsReleaseDetailModel(ctx.params["id"].orEmpty()).also {
        it.retry(ctx)
        if (it.release == null && !it.feedback.failed) ctx.notFound = true
        if (it.release != null) {
            it.ci = workOpsReleaseCi(it.id)
            it.artifacts = WorkOpsReleaseArtifactsModel(it.id).also { model -> model.refresh(ctx) }
            it.notes = WorkOpsReleaseNotesModel(it.id).also { model -> model.refresh(ctx) }
            it.release?.let { release -> it.environments = WorkOpsReleaseEnvironmentsModel(it.id, release.programId).also { model -> model.refresh(ctx) } }
        }
    }
}
