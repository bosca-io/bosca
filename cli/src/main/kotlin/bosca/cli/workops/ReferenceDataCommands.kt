package bosca.cli.workops

import bosca.cli.api.WorkOpsApi
import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import kotlin.uuid.Uuid

class RefDataCommand : BoscaCliCommand(name = "ref") {
    override fun help(context: Context) = "Reference data — statuses, task types, priorities, resolutions, workflows, link types"
    override fun run() = Unit
}

class StatusListCommand : WorkOpsSubcommand("statuses") {
    override fun help(context: Context) = "List all statuses"

    override suspend fun execute(api: WorkOpsApi) {
        val statuses = api.getStatuses()
        if (statuses.isEmpty()) { echo("No statuses found."); return }
        echo("%-36s  %-20s  %-12s  %s".format("ID", "NAME", "CATEGORY", "COLOR"))
        echo("-".repeat(80))
        for (s in statuses) {
            echo("%-36s  %-20s  %-12s  %s".format(s.id, s.name, s.category, s.colorHex))
        }
    }
}

class TaskTypeListCommand : WorkOpsSubcommand("task-types") {
    override fun help(context: Context) = "List all task types"

    override suspend fun execute(api: WorkOpsApi) {
        val types = api.getTaskTypes()
        if (types.isEmpty()) { echo("No task types found."); return }
        echo("%-36s  %-20s  %-12s  %s".format("ID", "NAME", "HIERARCHY", "COLOR"))
        echo("-".repeat(80))
        for (t in types) {
            echo("%-36s  %-20s  %-12s  %s".format(t.id, t.name, t.hierarchyLevel, t.colorHex))
        }
    }
}

class PriorityListCommand : WorkOpsSubcommand("priorities") {
    override fun help(context: Context) = "List all priorities"

    override suspend fun execute(api: WorkOpsApi) {
        val priorities = api.getPriorities()
        if (priorities.isEmpty()) { echo("No priorities found."); return }
        echo("%-36s  %-20s  %-6s  %s".format("ID", "NAME", "ORDER", "COLOR"))
        echo("-".repeat(80))
        for (p in priorities) {
            echo("%-36s  %-20s  %-6d  %s".format(p.id, p.name, p.displayOrder, p.colorHex))
        }
    }
}

class ResolutionListCommand : WorkOpsSubcommand("resolutions") {
    override fun help(context: Context) = "List all resolutions"

    override suspend fun execute(api: WorkOpsApi) {
        val resolutions = api.getResolutions()
        if (resolutions.isEmpty()) { echo("No resolutions found."); return }
        echo("%-36s  %-20s  %s".format("ID", "NAME", "ORDER"))
        echo("-".repeat(70))
        for (r in resolutions) {
            echo("%-36s  %-20s  %d".format(r.id, r.name, r.displayOrder))
        }
    }
}

class WorkflowListCommand : WorkOpsSubcommand("workflows") {
    override fun help(context: Context) = "List all workflows"

    override suspend fun execute(api: WorkOpsApi) {
        val workflows = api.getWorkflows()
        if (workflows.isEmpty()) { echo("No workflows found."); return }
        echo("%-36s  %-30s  %-8s  %s".format("ID", "NAME", "STATES", "TRANSITIONS"))
        echo("-".repeat(90))
        for (w in workflows) {
            echo("%-36s  %-30s  %-8d  %d".format(w.id, w.name.take(30), w.states.size, w.transitions.size))
        }
    }
}

class WorkflowGetCommand : WorkOpsSubcommand("workflow") {
    override fun help(context: Context) = "Get workflow details"
    private val id by option("--id", help = "Workflow UUID")

    override suspend fun execute(api: WorkOpsApi) {
        if (id == null) { echo("Error: --id required", err = true); return }
        val workflow = api.getWorkflow(Uuid.parse(id!!)) ?: run { echo("Workflow not found."); return }
        echo("ID:          ${workflow.id}")
        echo("Name:        ${workflow.name}")
        echo("Description: ${workflow.description ?: "-"}")
        echo("States:")
        for (s in workflow.states.sortedBy { it.displayOrder }) {
            echo("  [${s.displayOrder}] ${s.name} (${s.status.category})")
        }
        echo("Transitions:")
        for (t in workflow.transitions) {
            echo("  ${t.name}: ${t.fromStateIds.joinToString(",")} → ${t.toStateId}")
        }
    }
}

class LinkTypeListCommand : WorkOpsSubcommand("link-types") {
    override fun help(context: Context) = "List all task link types"

    override suspend fun execute(api: WorkOpsApi) {
        val types = api.getLinkTypes()
        if (types.isEmpty()) { echo("No link types found."); return }
        echo("%-36s  %-20s  %-20s  %-20s  %s".format("ID", "NAME", "INWARD", "OUTWARD", "CATEGORY"))
        echo("-".repeat(110))
        for (t in types) {
            echo("%-36s  %-20s  %-20s  %-20s  %s".format(t.id, t.name, t.inwardLabel, t.outwardLabel, t.category))
        }
    }
}
