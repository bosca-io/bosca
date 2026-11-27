package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.workops.ui.graphql.*
import bosca.workops.ui.graphql.WorkOpsReleasePlansData.WorkOps.CrossProject.ReleasePlans as ReleasePlan
import bosca.workops.ui.graphql.WorkOpsReleaseNativeRunData.Git.PipelineRun as NativeRun
import bosca.workops.ui.graphql.WorkOpsReleaseExecutionAccessData.WorkOps.CrossProject.ReleaseActionAccess as ReleaseAccess
import bosca.workops.ui.graphql.WorkOpsReleaseRunSummariesData.WorkOps.CrossProject.ReleaseRuns as RunSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Independent read outcomes keep failed plan, capability and run reads out of readiness decisions. */
@Serializable
class WorkOpsReleaseCiReadModel(
    val id: String,
    var release: WorkOpsReleaseRecord? = null,
    var components: List<WorkOpsReleaseComponentRecord> = emptyList(),
    var componentsLoaded: Boolean = false,
    var plans: List<ReleasePlan> = emptyList(),
    var plansLoaded: Boolean = false,
    var summaries: List<RunSummary> = emptyList(),
    var summariesLoaded: Boolean = false,
    var runs: List<NativeRun> = emptyList(),
    var runErrors: Map<String, String> = emptyMap(),
    var access: ReleaseAccess? = null,
    var accessLoaded: Boolean = false,
    var readiness: List<String> = emptyList(),
    var readinessLoaded: Boolean = false,
    var selectedRunId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    val componentFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val plansFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val runsFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val accessFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val readinessFeedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val releasePlans get() = plans.filter { it.triggerType == "RELEASE" }
    val promotionPlans get() = plans.filter { it.triggerType == "PROMOTION" }
    val promotionEnvironments get() = promotionPlans.mapNotNull { it.environment }.distinct()
    val chosenRunId get() = selectedRunId.ifBlank { summaries.firstOrNull()?.runId.orEmpty() }
    val selectedRun get() = runs.firstOrNull { it.id == chosenRunId }
    val needsPolling get() = release != null && (!summariesLoaded || summaries.any { it.status in setOf("QUEUED", "RUNNING") })
    val canManage get() = accessLoaded && access?.canManage == true
    val repositoriesMissing get() = accessLoaded && access?.repositories?.isEmpty() == true
    fun canEdit(repository: String) = accessLoaded && access?.repositories?.any { it.repositoryId == repository && it.canEdit } == true
    fun canExecute(repository: String) = accessLoaded && access?.repositories?.any { it.repositoryId == repository && it.canExecute } == true
    fun canExecuteEnvironment(environment: String?) = environment == null ||
        (accessLoaded && access?.environments?.any { it.key == environment && it.canExecute } == true)

    private val repositoryReason get() = when {
        !accessLoaded -> "Action permissions are unavailable."
        access?.repositories?.any { !it.canEdit } == true -> "Edit permission on every release repository is required."
        access?.repositories?.any { !it.canExecute } == true -> "Execute permission on every release repository is required."
        else -> null
    }
    val startReason get() = when {
        release == null -> "This release is unavailable."
        !accessLoaded -> "Action permissions are unavailable."
        !canManage -> "Manage permission on the release program is required."
        !componentsLoaded -> "Bundled versions are unavailable."
        components.isEmpty() -> "Add at least one project version before starting the release."
        repositoriesMissing -> "Attach a Git repository to a bundled project before starting the release."
        !plansLoaded -> "Release plans are unavailable."
        releasePlans.isEmpty() -> "No attached repository declares a release trigger."
        !readinessLoaded -> "Release readiness is unavailable."
        readiness.isNotEmpty() -> "Resolve the release dependency blockers first."
        else -> repositoryReason
    }
    fun sourceReached(plan: ReleasePlan): Boolean {
        val source = plan.promotesFrom ?: return true
        return runs.any { run -> run.pipelineId == plan.pipelineId && run.status == GitPipelineRunStatus.SUCCESS &&
            when (run.triggerType) {
                GitPipelineTriggerType.PROMOTION -> run.parameter("promotion.environment") == source
                GitPipelineTriggerType.RELEASE -> releasePlans.any { it.pipelineId == run.pipelineId && it.jobs.any { job -> job.environment == source } }
                else -> false
            }
        }
    }
    fun promotionReason(environment: String): String? {
        if (!accessLoaded) return "Action permissions are unavailable."
        if (!canManage) return "Manage permission on the release program is required."
        if (!plansLoaded) return "Release plans are unavailable."
        val selected = promotionPlans.filter { it.environment == environment }
        if (selected.isEmpty()) return "Choose an available promotion environment."
        repositoryReason?.let { return it }
        if (!canExecuteEnvironment(environment)) return "Execute permission on $environment is required."
        if (!summariesLoaded) return "Release run status is unavailable."
        val blocked = selected.firstOrNull { !sourceReached(it) }
        if (blocked != null) return if (runErrors.isNotEmpty()) "Source run details are unavailable." else "Promote through ${blocked.promotesFrom} first."
        return null
    }
    fun environmentReached(environment: String) = runs.any { run -> run.status == GitPipelineRunStatus.SUCCESS &&
        ((run.triggerType == GitPipelineTriggerType.PROMOTION && run.parameter("promotion.environment") == environment) ||
            (run.triggerType == GitPipelineTriggerType.RELEASE && releasePlans.any { it.pipelineId == run.pipelineId && it.jobs.any { job -> job.environment == environment } })) }
    fun rollbackEnvironmentReason(environment: String): String? {
        if (!canManage) return if (accessLoaded) "Manage permission on the release program is required." else "Action permissions are unavailable."
        if (!plansLoaded) return "Release plans are unavailable."
        repositoryReason?.let { return it }
        if (!canExecuteEnvironment(environment)) return "Execute permission on $environment is required."
        if (!summariesLoaded) return "Release run details are unavailable."
        if (environmentReached(environment)) return null
        return if (runErrors.isNotEmpty()) "Release run details are unavailable." else "This release has not reached $environment."
    }
    val rollbackAttemptReason get() = when {
        !canManage -> if (accessLoaded) "Manage permission on the release program is required." else "Action permissions are unavailable."
        release == null -> "This release is unavailable."
        release?.released?.isNotBlank() == true -> "This release is already marked released."
        !summariesLoaded -> "Release run status is unavailable."
        summaries.isEmpty() -> "This release has no native runs yet."
        summaries.any { it.status in setOf("QUEUED", "RUNNING") } -> "Cancel live runs before rolling back the release attempt."
        else -> null
    }

    suspend fun reload(ctx: RenderContext) {
        componentsLoaded = false; plansLoaded = false; summariesLoaded = false; accessLoaded = false; readinessLoaded = false
        runs = emptyList(); runErrors = emptyMap(); access = null
        release = ctx.gql.execute(WorkOpsReleaseDetail, WorkOpsReleaseDetail.Variables(id)).workOps.crossProject.release?.releaseRecord()
        if (release == null) return
        componentFeedback.perform {
            components = workOpsReleaseComponents(id, ctx); componentsLoaded = true
            components = workOpsReleaseComponentLabels(components, ctx)
        }
        accessFeedback.perform {
            access = ctx.gql.execute(WorkOpsReleaseExecutionAccess, WorkOpsReleaseExecutionAccess.Variables(id)).workOps.crossProject.releaseActionAccess
            accessLoaded = access != null
        }
        plansFeedback.perform {
            plans = if (componentsLoaded && (components.isEmpty() || repositoriesMissing)) emptyList() else
                ctx.gql.execute(WorkOpsReleasePlans, WorkOpsReleasePlans.Variables(id)).workOps.crossProject.releasePlans
            plansLoaded = true
        }
        readinessFeedback.perform {
            readiness = ctx.gql.execute(WorkOpsReleaseReadiness, WorkOpsReleaseReadiness.Variables(id)).workOps.crossProject.releaseReadiness
            readinessLoaded = true
        }
        runsFeedback.perform {
            summaries = ctx.gql.execute(WorkOpsReleaseRunSummaries, WorkOpsReleaseRunSummaries.Variables(id)).workOps.crossProject.releaseRuns
            summariesLoaded = true
            for (summary in summaries) {
                val result = WorkOpsFeedback()
                result.perform {
                    val run = requireNotNull(ctx.gql.execute(WorkOpsReleaseNativeRun, WorkOpsReleaseNativeRun.Variables(summary.runId)).git.pipelineRun) { "Run details are unavailable." }
                    runs = runs + run
                }
                if (result.failed) runErrors = runErrors + (summary.runId to result.message)
            }
        }
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
}

internal fun NativeRun.parameter(name: String): String? = ((parameters as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull
