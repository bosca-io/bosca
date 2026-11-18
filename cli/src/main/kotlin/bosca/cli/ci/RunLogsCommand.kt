package bosca.cli.ci

import bosca.graphql.gen.GitPipelineRunStatus
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.coroutines.delay
import kotlin.uuid.Uuid

class RunLogsCommand : CiSubcommand("logs") {
    override fun help(context: Context) = "View or follow logs for a run, job, or step"

    private val stepId by option("--step-id", help = "Only show this step's logs")
    private val runId by option("--run-id", help = "Pipeline run ID; required for job logs and --follow")
    private val jobId by option("--job-id", help = "Only show this job's logs; requires --run-id")
    private val follow by option("--follow", "-f", help = "Poll logs until the selected run, job, or step completes").flag()
    private val offset by option("--offset", help = "Number of log lines to skip per step").int().restrictTo(min = 0).default(0)

    override suspend fun execute(api: CiApi) {
        val selectedRun = runId?.let(Uuid::parse)
        val selectedJob = jobId?.let(Uuid::parse)
        val selectedStep = stepId?.let(Uuid::parse)
        if (selectedRun == null && selectedStep == null) throw CliktError("Provide --run-id or --step-id.")
        if (selectedRun == null && (follow || selectedJob != null)) {
            throw CliktError("--run-id is required for --follow and --job-id.")
        }
        if (selectedJob != null && selectedStep != null) throw CliktError("Use either --job-id or --step-id.")
        val offsets = mutableMapOf<Uuid, Int>()

        suspend fun printLogs(id: Uuid, label: String?) {
            var next = offsets[id] ?: offset
            while (true) {
                val logs = api.getPipelineLogs(id, offset = next, limit = 200)
                for (log in logs) {
                    val timestamp = log.timestamp.toString().take(19)
                    val stream = if (log.stream.equals("stderr", ignoreCase = true)) " [ERR]" else ""
                    val prefix = label?.let { " [$it]" }.orEmpty()
                    echo("$timestamp$prefix$stream ${log.content}")
                }
                next += logs.size
                offsets[id] = next
                if (logs.isEmpty()) break
            }
        }

        if (selectedRun == null) {
            printLogs(requireNotNull(selectedStep), null)
            return
        }

        while (true) {
            val run = api.getPipelineRun(selectedRun) ?: throw CliktError("Pipeline run not found: $selectedRun")
            val jobs = run.jobs.filter { selectedJob == null || it.id == selectedJob }
            if (selectedJob != null && jobs.isEmpty()) throw CliktError("Job not found in run: $selectedJob")
            val steps = jobs.flatMap { job ->
                job.steps.sortedBy { it.ordinal }.map { job to it }
            }.filter { (_, step) -> selectedStep == null || step.id == selectedStep }
            if (selectedStep != null && steps.isEmpty()) throw CliktError("Step not found in run: $selectedStep")
            for ((job, step) in steps) {
                printLogs(step.id, if (selectedStep == null) "${job.name}/${step.name}" else null)
            }

            val status = when {
                selectedStep != null -> steps.single().second.status
                selectedJob != null -> jobs.single().status
                else -> run.status
            }
            if (!follow || status.isTerminal()) return
            delay(2000)
        }
    }

    private fun GitPipelineRunStatus.isTerminal() = when (this) {
        GitPipelineRunStatus.SUCCESS, GitPipelineRunStatus.FAILURE,
        GitPipelineRunStatus.CANCELLED, GitPipelineRunStatus.SKIPPED -> true
        else -> false
    }
}
