package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.components.NavigationItem
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
class PipelineRunModel(
    var run: GitUiRunData.Git.PipelineRun,
    val canExecute: Boolean,
    val canEdit: Boolean,
    val canManage: Boolean,
    val jobId: String = "",
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
    var reason: String = "",
    var selectedAction: String = "",
    val repositoryName: String = "",
    val pipelineName: String = "",
    var expandedStepId: String? = null,
    var autoExpandedFailure: Boolean = false,
) {
    val title: String
        get() = pipelineName.takeIf { it.isNotBlank() }?.let { "$it · Run #${run.number}" }
            ?: "Run #${run.number}"

    val repositoryHref: String
        get() = "/repositories/${encode(run.repositoryId)}?tab=pipelines"

    /** Breadcrumb links between the Git root and this run, as in Studio's run header. */
    val trail: List<NavigationItem>
        get() = buildList {
            add(NavigationItem("Repositories", "/repositories", false))
            add(
                NavigationItem(
                    repositoryName.ifBlank { "Repository" },
                    "/repositories/${encode(run.repositoryId)}",
                    false,
                )
            )
            add(NavigationItem("Pipelines", repositoryHref, false))
            if (pipelineName.isNotBlank())
                add(NavigationItem(pipelineName, "$repositoryHref&pipeline=${encode(run.pipelineId)}", false))
        }

    val rerunnableJobCount: Int
        get() =
            if (run.status in terminalFailures) run.jobs.count { it.status in terminalFailures }
            else 0

    val hasMultipleRerunnableJobs: Boolean
        get() = rerunnableJobCount > 1

    fun canRerunJob(job: GitUiRunData.Git.PipelineRun.Jobs): Boolean =
        canExecute && run.status in terminalFailures && job.status in terminalFailures

    fun toggleStepLogs(stepId: String) {
        require(run.jobs.any { job -> job.steps.any { it.id == stepId } }) {
            "Step does not belong to this run."
        }
        expandedStepId = stepId.takeUnless { it == expandedStepId }
        autoExpandedFailure = true
    }

    fun expandFailure() {
        if (autoExpandedFailure || expandedStepId != null || run.status != GitPipelineRunStatus.FAILURE)
            return
        expandedStepId =
            run.jobs.asSequence().flatMap { it.steps }
                .firstOrNull { it.status == GitPipelineRunStatus.FAILURE }?.id
        if (expandedStepId != null) autoExpandedFailure = true
    }

    val job: GitUiRunData.Git.PipelineRun.Jobs
        get() = run.jobs.first { it.id == jobId }

    val active: Boolean
        get() = activeRun(run.status)

    fun buildAnyway(job: GitUiRunData.Git.PipelineRun.Jobs): Boolean {
        val external =
            (job.requirements as? JsonArray)?.isNotEmpty() == true ||
                (job.pipelineRequirements as? JsonArray)?.isNotEmpty() == true
        return external &&
            job.requirementsSatisfiedAt == null &&
            ((job.status == GitPipelineRunStatus.QUEUED && active) ||
                (job.status in
                    setOf(GitPipelineRunStatus.FAILURE, GitPipelineRunStatus.CANCELLED) &&
                    run.status in
                        setOf(GitPipelineRunStatus.FAILURE, GitPipelineRunStatus.CANCELLED)))
    }

    suspend fun refresh(ctx: RenderContext) = perform {
        run =
            ctx.gql.execute(GitUiRun, GitUiRun.Variables(run.id)).git.pipelineRun
                ?: error("Pipeline run no longer exists.")
        expandFailure()
    }

    suspend fun action(
        action: String,
        job: String,
        reason: String,
        confirmed: Boolean,
        ctx: RenderContext,
    ) = perform {
        this.reason = reason
        selectedAction = action
        require(confirmed) { "Confirm this action." }
        val permitted =
            when (action) {
                "cancel",
                "cancelJob" -> canEdit
                "rerun",
                "failed",
                "rerunJob",
                "buildAnyway" -> canExecute
                "delete" -> canManage
                else -> false
            }
        require(permitted) { "The required repository permission is not granted." }
        val current =
            ctx.gql.execute(GitUiRun, GitUiRun.Variables(run.id)).git.pipelineRun
                ?: error("Pipeline run no longer exists.")
        run = current
        if (job.isNotBlank())
            require(current.jobs.any { it.id == job }) { "Job does not belong to this run." }
        when (action) {
            "cancel" -> ctx.gql.execute(GitUiCancelRun, GitUiCancelRun.Variables(run.id))
            "rerun" ->
                nextHref =
                    runHref(
                        ctx.gql
                            .execute(GitUiRerun, GitUiRerun.Variables(run.id))
                            .git
                            .rerunPipeline
                            .id
                    )
            "failed" -> ctx.gql.execute(GitUiRerunFailed, GitUiRerunFailed.Variables(run.id))
            "delete" -> {
                require(
                    ctx.gql
                        .execute(GitUiDeleteRun, GitUiDeleteRun.Variables(run.id))
                        .git
                        .deletePipelineRun
                ) {
                    "Run was not deleted."
                }
                nextHref = repositoryHref
            }
            "cancelJob" -> ctx.gql.execute(GitUiCancelJob, GitUiCancelJob.Variables(job))
            "rerunJob" -> {
                require(canRerunJob(current.jobs.first { it.id == job })) {
                    "Wait for the pipeline run to finish before rerunning this job."
                }
                ctx.gql.execute(GitUiRerunJob, GitUiRerunJob.Variables(job))
            }
            "buildAnyway" -> {
                require(reason.length <= 1000 && buildAnyway(current.jobs.first { it.id == job })) {
                    "This job is not eligible to bypass external requirements."
                }
                ctx.gql.execute(
                    GitUiBuildAnyway,
                    GitUiBuildAnyway.Variables(job, reason.trim().ifBlank { null }),
                )
            }
            else -> error("Unknown run action")
        }
        if (nextHref.isBlank()) nextHref = runHref(run.id)
    }

    private suspend fun perform(block: suspend () -> Unit) {
        message = ""
        failed = false
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Pipeline action failed."
        }
    }

    companion object {
        private val terminalFailures = setOf(GitPipelineRunStatus.FAILURE, GitPipelineRunStatus.CANCELLED)
    }
}
