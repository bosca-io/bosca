package bosca.cli.ci

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlin.uuid.Uuid

class RunStepsCommand : CiSubcommand("steps") {
    override fun help(context: Context) = "List steps for a run, optionally restricted to one job"

    private val runId by option("--run-id", help = "Pipeline run ID").required()
    private val jobId by option("--job-id", help = "Only list this job's steps")

    override suspend fun execute(api: CiApi) {
        val run = api.getPipelineRun(Uuid.parse(runId)) ?: throw CliktError("Pipeline run not found: $runId")
        val selectedJob = jobId?.let(Uuid::parse)
        val jobs = run.jobs.filter { selectedJob == null || it.id == selectedJob }
        if (selectedJob != null && jobs.isEmpty()) throw CliktError("Job not found in run: $selectedJob")
        if (jobs.all { it.steps.isEmpty() }) {
            echo("No steps found.")
            return
        }
        echo("STEP ID  JOB ID  JOB  ORDER  NAME  STATUS  EXIT")
        for (job in jobs) {
            for (step in job.steps.sortedBy { it.ordinal }) {
                echo("${step.id}  ${job.id}  ${job.name}  ${step.ordinal}  ${step.name}  ${step.status}  ${step.exitCode ?: "-"}")
            }
        }
    }
}
