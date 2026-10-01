package bosca.cli.workops

import bosca.cli.api.WorkOpsApi
import bosca.graphql.gen.WorkOpsProgramInput
import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import kotlin.uuid.Uuid

class ProgramCommand : BoscaCliCommand(name = "program") {
    override fun help(context: Context) = "Manage programs"
    override fun run() = Unit
}

class ProgramListCommand : WorkOpsSubcommand("list") {
    override fun help(context: Context) = "List all programs"
    private val portfolioId by option("--portfolio-id", help = "Filter by portfolio UUID")

    override suspend fun execute(api: WorkOpsApi) {
        val programs = if (portfolioId != null) {
            api.getProgramsByPortfolio(Uuid.parse(portfolioId!!))
        } else {
            api.listPrograms()
        }
        if (programs.isEmpty()) { echo("No programs found."); return }
        echo("%-36s  %-10s  %-30s  %s".format("ID", "KEY", "NAME", "ARCHIVED"))
        echo("-".repeat(90))
        for (p in programs) {
            echo("%-36s  %-10s  %-30s  %s".format(
                p.id, p.key, p.name.take(30), p.archivedAt?.toString()?.take(10) ?: "-"
            ))
        }
    }
}

class ProgramGetCommand : WorkOpsSubcommand("get") {
    override fun help(context: Context) = "Get a program by ID"
    private val id by option("--id", help = "Program UUID").required()

    override suspend fun execute(api: WorkOpsApi) {
        val program = api.getProgram(Uuid.parse(id)) ?: run { echo("Program not found."); return }
        echo("ID:          ${program.id}")
        echo("Key:         ${program.key}")
        echo("Name:        ${program.name}")
        echo("Description: ${program.description ?: "-"}")
        echo("Owner:       ${program.ownerProfileId}")
        echo("Archived:    ${program.archivedAt ?: "no"}")
        echo("Version:     ${program.version}")
    }
}

class ProgramCreateCommand : WorkOpsSubcommand("create") {
    override fun help(context: Context) = "Create a new program"
    private val portfolioId by option("--portfolio-id", help = "Parent portfolio UUID").required()
    private val key by option("--key", "-k", help = "Unique uppercase key").required()
    private val name by option("--name", "-n", help = "Program name").required()
    private val description by option("--description", "-d", help = "Description")
    private val ownerProfileId by option("--owner", help = "Owner profile UUID").required()

    override suspend fun execute(api: WorkOpsApi) {
        val result = api.createProgram(WorkOpsProgramInput(
            portfolioId = Uuid.parse(portfolioId),
            key = key,
            name = name,
            description = description,
            ownerProfileId = Uuid.parse(ownerProfileId),
        ))
        echo("Created program: ${result.key} (${result.id})")
    }
}
