package bosca.cli.workops

import bosca.cli.api.WorkOpsApi
import bosca.graphql.gen.WorkOpsProjectInput
import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlin.uuid.Uuid

class ProjectCommand : BoscaCliCommand(name = "project") {
    override fun help(context: Context) = "Manage projects"
    override fun run() = Unit
}

class ProjectListCommand : WorkOpsSubcommand("list") {
    override fun help(context: Context) = "List all projects"
    private val programId by option("--program-id", help = "Filter by program UUID")

    override suspend fun execute(api: WorkOpsApi) {
        val projects = if (programId != null) {
            api.getProjectsByProgram(Uuid.parse(programId!!))
        } else {
            api.listProjects()
        }
        if (projects.isEmpty()) { echo("No projects found."); return }
        echo("%-36s  %-10s  %-30s  %s".format("ID", "KEY", "NAME", "ARCHIVED"))
        echo("-".repeat(90))
        for (p in projects) {
            echo("%-36s  %-10s  %-30s  %s".format(
                p.id, p.key, p.name.take(30), p.archivedAt?.toString()?.take(10) ?: "-"
            ))
        }
    }
}

class ProjectGetCommand : WorkOpsSubcommand("get") {
    override fun help(context: Context) = "Get a project by ID or key"
    private val id by option("--id", help = "Project UUID")
    private val key by option("--key", "-k", help = "Project key")

    override suspend fun execute(api: WorkOpsApi) {
        val project = when {
            id != null -> api.getProject(Uuid.parse(id!!))
            key != null -> api.getProjectByKey(key!!)
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        if (project == null) { echo("Project not found."); return }
        echo("ID:          ${project.id}")
        echo("Key:         ${project.key}")
        echo("Name:        ${project.name}")
        echo("Description: ${project.description ?: "-"}")
        echo("Owner:       ${project.ownerProfileId}")
        echo("Archived:    ${project.archivedAt ?: "no"}")
        echo("Version:     ${project.version}")
    }
}

class ProjectCreateCommand : WorkOpsSubcommand("create") {
    override fun help(context: Context) = "Create a new project"
    private val programId by option("--program-id", help = "Parent program UUID").required()
    private val key by option("--key", "-k", help = "Unique uppercase key").required()
    private val name by option("--name", "-n", help = "Project name").required()
    private val description by option("--description", "-d", help = "Description")
    private val ownerProfileId by option("--owner", help = "Owner profile UUID").required()

    override suspend fun execute(api: WorkOpsApi) {
        val result = api.createProject(WorkOpsProjectInput(
            programId = Uuid.parse(programId),
            key = key,
            name = name,
            description = description,
            ownerProfileId = Uuid.parse(ownerProfileId),
        ))
        echo("Created project: ${result.key} (${result.id})")
    }
}
