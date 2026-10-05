package bosca.cli.ci

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.delay
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
    override fun help(context: Context) = "List pipeline runs for a repository"

    private val repoId by option("--repo-id", help = "Repository ID").required()
    private val limit by option("--limit", help = "Max results").int().default(20)

    override suspend fun execute(api: CiApi) {
        val runs = api.listPipelineRuns(Uuid.parse(repoId), limit = limit)
        if (runs.isEmpty()) {
            echo("No pipeline runs found.")
            return
        }
        echo("%-5s  %-10s  %-12s  %-20s  %s".format("#", "STATUS", "TRIGGER", "CREATED", "COMMIT"))
        for (r in runs) {
            echo("%-5d  %-10s  %-12s  %-20s  %s".format(
                r.number, r.status, r.triggerType,
                r.created.toString().take(19), r.commitSha.take(8)
            ))
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

class RunLogsCommand : CiSubcommand("logs") {
    override fun help(context: Context) = "View or stream step logs"

    private val stepId by option("--step-id", help = "Step ID").required()
    private val runId by option("--run-id", help = "Pipeline run ID (required for --follow to detect completion)")
    private val follow by option("--follow", "-f", help = "Stream logs continuously until step completes").flag()

    override suspend fun execute(api: CiApi) {
        val id = Uuid.parse(stepId)
        var offset = 0

        while (true) {
            val logs = api.getPipelineLogs(id, offset = offset, limit = 200)
            for (log in logs) {
                val ts = log.timestamp.toString().take(19)
                val stream = if (log.stream == "stderr") " [ERR]" else ""
                echo("$ts$stream ${log.content}")
            }
            offset += logs.size

            if (!follow) break

            if (logs.isEmpty()) {
                if (isStepFinished(api)) break
                delay(2000)
            }
        }
    }

    private suspend fun isStepFinished(api: CiApi): Boolean {
        if (runId == null) return false
        val run = api.getPipelineRun(Uuid.parse(runId!!)) ?: return true
        for (job in run.jobs) {
            for (step in job.steps) {
                if (step.id.toString() == stepId) {
                    val status = step.status.name
                    return status == "SUCCESS" || status == "FAILURE" || status == "CANCELLED" || status == "SKIPPED"
                }
            }
        }
        return true
    }
}
