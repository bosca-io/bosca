package bosca.cli.ci

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlin.uuid.Uuid

class RunTriggerCommand : CiSubcommand("trigger") {
    override fun help(context: Context) = "Manually trigger a pipeline run"

    private val pipelineId by option("--pipeline-id", help = "Pipeline ID").required()
    private val ref by option("--ref", help = "Git ref to run against").required()

    override suspend fun execute(api: CiApi) {
        val run = api.triggerPipeline(Uuid.parse(pipelineId), ref)
        echo("Pipeline triggered.")
        echo("  Run ID:  ${run.id}")
        echo("  Number:  #${run.number}")
        echo("  Status:  ${run.status}")
        echo("  Ref:     ${run.ref}")
        echo("  Commit:  ${run.commitSha}")
    }
}

class RunListCommand : CiSubcommand("list") {
    override fun help(context: Context) = "List runs for a repository or one pipeline"

    private val repoId by option("--repo-id", help = "Repository ID").required()
    private val pipelineId by option("--pipeline-id", help = "Only list runs for this pipeline")
    private val offset by option("--offset", help = "Number of runs to skip").long().restrictTo(min = 0).default(0)
    private val limit by option("--limit", help = "Max results").int().restrictTo(1, 100).default(20)

    override suspend fun execute(api: CiApi) {
        val selectedPipeline = pipelineId
        val rows = if (selectedPipeline == null) {
            api.listPipelineRuns(Uuid.parse(repoId), offset = offset, limit = limit).map {
                "${it.id}  #${it.number}  ${it.status}  ${it.pipelineId}  ${it.ref}  ${it.triggerType}  ${it.created}  ${it.commitSha}"
            }
        } else {
            val runs = api.listPipelineRunsForPipeline(Uuid.parse(repoId), Uuid.parse(selectedPipeline), offset, limit)
                ?: throw CliktError("Pipeline not found in repository: $selectedPipeline")
            runs.map {
                "${it.id}  #${it.number}  ${it.status}  ${it.pipelineId}  ${it.ref}  ${it.triggerType}  ${it.created}  ${it.commitSha}"
            }
        }
        if (rows.isEmpty()) {
            echo("No pipeline runs found.")
            return
        }
        echo("RUN ID  #  STATUS  PIPELINE ID  REF  TRIGGER  CREATED  COMMIT")
        for (row in rows) {
            echo(row)
        }
    }
}

class RunCancelCommand : CiSubcommand("cancel") {
    override fun help(context: Context) = "Cancel a pipeline run or one active job"

    private val id by option("--id", help = "Pipeline run ID")
    private val jobId by option("--job-id", help = "Pipeline job ID")

    override suspend fun execute(api: CiApi) {
        when {
            id != null && jobId == null -> {
                val run = api.cancelPipelineRun(Uuid.parse(id!!))
                echo("Pipeline run cancelled. Status: ${run.status}")
            }
            jobId != null && id == null -> {
                val job = api.cancelPipelineJob(Uuid.parse(jobId!!))
                echo("Pipeline job cancelled.")
                echo("  Job ID:  ${job.id}")
                echo("  Name:    ${job.name}")
                echo("  Status:  ${job.status}")
            }
            else -> throw CliktError("Exactly one of --id or --job-id is required.")
        }
    }
}

class RunRerunCommand : CiSubcommand("rerun") {
    override fun help(context: Context) = "Re-run a pipeline or one failed job"

    private val id by option("--id", help = "Pipeline run ID to re-run")
    private val jobId by option("--job-id", help = "Failed or cancelled pipeline job ID to re-run")

    override suspend fun execute(api: CiApi) {
        when {
            id != null && jobId == null -> {
                val run = api.rerunPipeline(Uuid.parse(id!!))
                echo("Pipeline re-triggered.")
                echo("  Run ID:  ${run.id}")
                echo("  Number:  #${run.number}")
                echo("  Status:  ${run.status}")
            }
            jobId != null && id == null -> {
                val job = api.rerunPipelineJob(Uuid.parse(jobId!!))
                echo("Pipeline job re-queued.")
                echo("  Job ID:  ${job.id}")
                echo("  Name:    ${job.name}")
                echo("  Status:  ${job.status}")
            }
            else -> throw CliktError("Exactly one of --id or --job-id is required.")
        }
    }
}
