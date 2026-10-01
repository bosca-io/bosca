package bosca.cli.workops

import bosca.cli.api.WorkOpsApi
import bosca.graphql.gen.WorkOpsWorkLogInput
import bosca.graphql.gen.WorklogVisibility
import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.time.ZonedDateTime
import kotlin.uuid.Uuid

class WorklogCommand : BoscaCliCommand(name = "worklog") {
    override fun help(context: Context) = "Manage work logs"
    override fun run() = Unit
}

class WorklogListCommand : WorkOpsSubcommand("list") {
    override fun help(context: Context) = "List worklogs for a task"
    private val taskId by option("--task-id", help = "Task UUID")
    private val taskKey by option("--task-key", "-k", help = "Task key")

    override suspend fun execute(api: WorkOpsApi) {
        val resolvedTaskId = when {
            taskId != null -> Uuid.parse(taskId!!)
            taskKey != null -> api.getTaskByKey(taskKey!!)?.id
                ?: run { echo("Task not found."); return }
            else -> { echo("Error: --task-id or --task-key required", err = true); return }
        }
        val logs = api.getWorklogs(resolvedTaskId)
        if (logs.isEmpty()) { echo("No worklogs found."); return }
        echo("%-36s  %-15s  %-20s  %s".format("ID", "TIME", "STARTED", "COMMENT"))
        echo("-".repeat(100))
        for (l in logs) {
            echo("%-36s  %-15s  %-20s  %s".format(
                l.id, l.timeSpentShort, l.startedAt.toString().take(20), (l.comment ?: "-").take(30)
            ))
        }
    }
}

class WorklogAddCommand : WorkOpsSubcommand("add") {
    override fun help(context: Context) = "Log work on a task"
    private val taskId by option("--task-id", help = "Task UUID")
    private val taskKey by option("--task-key", "-k", help = "Task key")
    private val timeSpent by option("--time", help = "Duration (e.g. 1h 30m, 2d)").required()
    private val comment by option("--comment", "-c", help = "Work log comment")
    private val startedAt by option("--started-at", help = "Start time (ISO-8601, defaults to now)")

    override suspend fun execute(api: WorkOpsApi) {
        val resolvedTaskId = when {
            taskId != null -> Uuid.parse(taskId!!)
            taskKey != null -> api.getTaskByKey(taskKey!!)?.id
                ?: run { echo("Task not found."); return }
            else -> { echo("Error: --task-id or --task-key required", err = true); return }
        }
        val started = startedAt?.let { ZonedDateTime.parse(it) } ?: ZonedDateTime.now()
        val result = api.logWork(resolvedTaskId, WorkOpsWorkLogInput(
            timeSpent = timeSpent,
            startedAt = started,
            comment = comment,
            visibility = WorklogVisibility.ALL,
        ))
        echo("Logged ${result.timeSpentShort} on task (worklog id: ${result.id})")
    }
}
