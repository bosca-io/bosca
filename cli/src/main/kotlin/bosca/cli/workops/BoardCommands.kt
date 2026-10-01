package bosca.cli.workops

import bosca.cli.api.WorkOpsApi
import bosca.graphql.gen.CreateWorkOpsBoardInput
import bosca.graphql.gen.WorkOpsBoardType
import bosca.graphql.gen.WorkOpsSwimlaneStrategy
import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlin.uuid.Uuid

class BoardCommand : BoscaCliCommand(name = "board") {
    override fun help(context: Context) = "Manage boards"
    override fun run() = Unit
}

class BoardListCommand : WorkOpsSubcommand("list") {
    override fun help(context: Context) = "List boards for a project"
    private val projectId by option("--project-id", help = "Project UUID").required()

    override suspend fun execute(api: WorkOpsApi) {
        val boards = api.getBoardsByProject(Uuid.parse(projectId))
        if (boards.isEmpty()) { echo("No boards found."); return }
        echo("%-36s  %-20s  %-8s  %s".format("ID", "NAME", "TYPE", "COLUMNS"))
        echo("-".repeat(80))
        for (b in boards) {
            echo("%-36s  %-20s  %-8s  %d".format(b.id, b.name.take(20), b.type, b.columns.size))
        }
    }
}

class BoardGetCommand : WorkOpsSubcommand("get") {
    override fun help(context: Context) = "Get a board by ID"
    private val id by option("--id", help = "Board UUID").required()

    override suspend fun execute(api: WorkOpsApi) {
        val board = api.getBoard(Uuid.parse(id)) ?: run { echo("Board not found."); return }
        echo("ID:        ${board.id}")
        echo("Name:      ${board.name}")
        echo("Type:      ${board.type}")
        echo("Swimlane:  ${board.swimlaneStrategy}")
        echo("Columns:")
        for (c in board.columns.sortedBy { it.displayOrder }) {
            echo("  [${c.displayOrder}] ${c.name} (${c.statusIds.size} statuses, WIP: ${c.wipLimit ?: "∞"})")
        }
    }
}

class BoardCreateCommand : WorkOpsSubcommand("create") {
    override fun help(context: Context) = "Create a new board"
    private val projectId by option("--project-id", help = "Project UUID").required()
    private val name by option("--name", "-n", help = "Board name").required()
    private val type by option("--type", help = "KANBAN or SCRUM").required()

    override suspend fun execute(api: WorkOpsApi) {
        val result = api.createBoard(CreateWorkOpsBoardInput(
            projectId = Uuid.parse(projectId),
            name = name,
            type = WorkOpsBoardType.valueOf(type.uppercase()),
        ))
        echo("Created board: ${result.name} (${result.id})")
    }
}
