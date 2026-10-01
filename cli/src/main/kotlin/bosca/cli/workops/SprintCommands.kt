package bosca.cli.workops

import bosca.cli.api.WorkOpsApi
import bosca.graphql.gen.CreateWorkOpsSprintInput
import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlin.uuid.Uuid

class SprintCommand : BoscaCliCommand(name = "sprint") {
    override fun help(context: Context) = "Manage sprints"
    override fun run() = Unit
}

class SprintListCommand : WorkOpsSubcommand("list") {
    override fun help(context: Context) = "List sprints for a board"
    private val boardId by option("--board-id", help = "Board UUID").required()

    override suspend fun execute(api: WorkOpsApi) {
        val sprints = api.getSprintsByBoard(Uuid.parse(boardId))
        if (sprints.isEmpty()) { echo("No sprints found."); return }
        echo("%-36s  %-20s  %-8s  %s".format("ID", "NAME", "STATE", "DATES"))
        echo("-".repeat(90))
        for (s in sprints) {
            val dates = listOfNotNull(s.startDate?.toString()?.take(10), s.endDate?.toString()?.take(10)).joinToString(" → ")
            echo("%-36s  %-20s  %-8s  %s".format(s.id, s.name.take(20), s.state, dates))
        }
    }
}

class SprintGetCommand : WorkOpsSubcommand("get") {
    override fun help(context: Context) = "Get a sprint by ID"
    private val id by option("--id", help = "Sprint UUID").required()

    override suspend fun execute(api: WorkOpsApi) {
        val sprint = api.getSprint(Uuid.parse(id)) ?: run { echo("Sprint not found."); return }
        echo("ID:        ${sprint.id}")
        echo("Name:      ${sprint.name}")
        echo("Goal:      ${sprint.goal ?: "-"}")
        echo("State:     ${sprint.state}")
        echo("Start:     ${sprint.startDate ?: "-"}")
        echo("End:       ${sprint.endDate ?: "-"}")
        echo("Committed: ${sprint.committedTaskIds.size} tasks")
        echo("Added:     ${sprint.addedDuringSprintTaskIds.size} tasks")
        echo("Velocity:  ${sprint.velocityPoints ?: "-"}")
        echo("Version:   ${sprint.version}")
    }
}

class SprintCreateCommand : WorkOpsSubcommand("create") {
    override fun help(context: Context) = "Create a new sprint"
    private val boardId by option("--board-id", help = "Board UUID").required()
    private val name by option("--name", "-n", help = "Sprint name").required()
    private val goal by option("--goal", help = "Sprint goal")

    override suspend fun execute(api: WorkOpsApi) {
        val result = api.createSprint(CreateWorkOpsSprintInput(
            boardId = Uuid.parse(boardId),
            name = name,
            goal = goal,
        ))
        echo("Created sprint: ${result.name} (${result.id})")
    }
}
