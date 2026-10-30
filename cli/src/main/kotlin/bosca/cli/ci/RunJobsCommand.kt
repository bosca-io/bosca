package bosca.cli.ci

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlin.uuid.Uuid

class RunJobsCommand : CiSubcommand("jobs") {
    override fun help(context: Context) = "List jobs for a pipeline run"

    private val runId by option("--run-id", help = "Pipeline run ID").required()

    override suspend fun execute(api: CiApi) {
        val run = api.getPipelineRun(Uuid.parse(runId)) ?: throw CliktError("Pipeline run not found: $runId")
        if (run.jobs.isEmpty()) {
            echo("No jobs found.")
            return
        }
        echo("JOB ID  NAME  STATUS  RUNNER  STARTED  FINISHED")
        for (job in run.jobs) {
            echo("${job.id}  ${job.name}  ${job.status}  ${job.runnerLabel}  ${job.started ?: "-"}  ${job.finished ?: "-"}")
        }
    }
}
