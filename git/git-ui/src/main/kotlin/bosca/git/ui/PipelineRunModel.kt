package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
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
) {
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
                nextHref = "/pipelines?repo=${encode(run.repositoryId)}"
            }
            "cancelJob" -> ctx.gql.execute(GitUiCancelJob, GitUiCancelJob.Variables(job))
            "rerunJob" -> ctx.gql.execute(GitUiRerunJob, GitUiRerunJob.Variables(job))
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
}
