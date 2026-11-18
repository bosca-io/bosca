package bosca.cli.ci

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlin.uuid.Uuid

class RunGetCommand : CiSubcommand("get") {
    override fun help(context: Context) = "Show a pipeline run with its jobs and steps"

    private val id by option("--id", "--run-id", help = "Pipeline run ID").required()

    override suspend fun execute(api: CiApi) {
        val run = api.getPipelineRun(Uuid.parse(id)) ?: throw CliktError("Pipeline run not found: $id")
        echo("Run:        ${run.id} (#${run.number})")
        echo("Pipeline:   ${run.pipelineId}")
        echo("Repository: ${run.repositoryId}")
        echo("Status:     ${run.status}")
        echo("Trigger:    ${run.triggerType}")
        echo("Ref:        ${run.ref}")
        echo("Commit:     ${run.commitSha}")
        echo("Created:    ${run.created}")
        echo("Started:    ${run.started ?: "-"}")
        echo("Finished:   ${run.finished ?: "-"}")
        echo("Duration:   ${run.durationSeconds?.let { "${it}s" } ?: "-"}")
        if (run.jobs.isEmpty()) echo("No jobs found.")
        for (job in run.jobs) {
            echo("Job: ${job.id}  ${job.name}  ${job.status}  runner=${job.runnerLabel}")
            for (step in job.steps.sortedBy { it.ordinal }) {
                echo("  Step: ${step.id}  ${step.name}  ${step.status}  exit=${step.exitCode ?: "-"}")
            }
        }
    }
}
