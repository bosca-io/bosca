package bosca.cli.workops

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context

/**
 * Parent command grouping all workops subcommands under `bosca workops`.
 */
class WorkOpsCommand : BoscaCliCommand(name = "workops") {
    override fun help(context: Context) = "Work operations — portfolios, programs, projects, tasks, boards, sprints, and more"
    override fun run() = Unit
}
