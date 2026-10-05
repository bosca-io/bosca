package bosca.cli.workops

import bosca.cli.api.WorkOpsApi
import bosca.graphql.gen.CreateWorkOpsTaskInput
import bosca.graphql.gen.UpdateWorkOpsTaskInput
import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlin.uuid.Uuid

class TaskCommand : BoscaCliCommand(name = "task") {
    override fun help(context: Context) = "Manage tasks"
    override fun run() = Unit
}

class TaskListCommand : WorkOpsSubcommand("list") {
    override fun help(context: Context) = "List tasks in a project"
    private val projectId by option("--project-id", help = "Project UUID (required unless --project-key given)")
    private val projectKey by option("--project-key", "-p", help = "Project key (e.g. BOS)")
    private val limit by option("--limit").int().default(50)

    override suspend fun execute(api: WorkOpsApi) {
        val resolvedProjectId = when {
            projectId != null -> Uuid.parse(projectId!!)
            projectKey != null -> api.getProjectByKey(projectKey!!)?.id
                ?: run { echo("Error: project '${projectKey}' not found", err = true); return }
            else -> { echo("Error: --project-id or --project-key required", err = true); return }
        }
        val tasks = api.getTasksByProject(resolvedProjectId, limit = limit)
        if (tasks.isEmpty()) { echo("No tasks found."); return }
        echo("%-12s  %-10s  %-15s  %-10s  %s".format("KEY", "TYPE", "STATUS", "PRIORITY", "SUMMARY"))
        echo("-".repeat(100))
        for (t in tasks) {
            echo("%-12s  %-10s  %-15s  %-10s  %s".format(
                t.key, t.taskType.name.take(10), t.status.name.take(15), t.priority.name.take(10), t.summary.take(50)
            ))
        }
    }
}

class TaskGetCommand : WorkOpsSubcommand("get") {
    override fun help(context: Context) = "Get a task by ID or key"
    private val id by option("--id", help = "Task UUID")
    private val key by option("--key", "-k", help = "Task key (e.g. BOS-42)")

    override suspend fun execute(api: WorkOpsApi) {
        val task = when {
            id != null -> api.getTask(Uuid.parse(id!!))
            key != null -> api.getTaskByKey(key!!)
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        if (task == null) { echo("Task not found."); return }
        echo("Key:         ${task.key}")
        echo("Summary:     ${task.summary}")
        echo("Type:        ${task.taskType.name}")
        echo("Status:      ${task.status.name} (${task.status.category})")
        echo("Priority:    ${task.priority.name}")
        echo("Assignee:    ${task.assigneeProfileId ?: "unassigned"}")
        echo("Reporter:    ${task.reporterProfileId}")
        echo("Project:     ${task.project.key} — ${task.project.name}")
        if (task.affectedProjects.isNotEmpty()) {
            echo("Affects:     ${task.affectedProjects.joinToString(", ") { "${it.key} — ${it.name}" }}")
        }
        if (task.descriptionMarkdown != null) {
            echo("Description:")
            echo(task.descriptionMarkdown)
        }
        if (task.dueDate != null) echo("Due:         ${task.dueDate}")
        if (task.startDate != null) echo("Start:       ${task.startDate}")
        if (task.resolution != null) echo("Resolution:  ${task.resolution!!.name}")
        if (task.epicTask != null) echo("Epic:        ${task.epicTask!!.key} — ${task.epicTask!!.summary}")
        if (task.parentTask != null) echo("Parent:      ${task.parentTask!!.key} — ${task.parentTask!!.summary}")
        echo("Created:     ${task.createdAt}")
        echo("Modified:    ${task.modifiedAt}")
        echo("Version:     ${task.version}")
        echo("ID:          ${task.id}")
    }
}

class TaskCreateCommand : WorkOpsSubcommand("create") {
    override fun help(context: Context) = "Create a new task"
    private val projectId by option("--project-id", help = "Project UUID (required unless --project-key given)")
    private val projectKey by option("--project-key", "-p", help = "Project key")
    private val summary by option("--summary", "-s", help = "Task summary").required()
    private val description by option("--description", "-d", help = "Description (Markdown)")
    private val assignee by option("--assignee", help = "Assignee profile UUID")
    private val priorityId by option("--priority-id", help = "Priority UUID")
    private val taskTypeId by option("--type-id", help = "Task type UUID")
    private val sprintId by option("--sprint-id", help = "Sprint UUID")

    override suspend fun execute(api: WorkOpsApi) {
        val resolvedProjectId = when {
            projectId != null -> Uuid.parse(projectId!!)
            projectKey != null -> api.getProjectByKey(projectKey!!)?.id
                ?: run { echo("Error: project '${projectKey}' not found", err = true); return }
            else -> { echo("Error: --project-id or --project-key required", err = true); return }
        }
        val result = api.createTask(CreateWorkOpsTaskInput(
            projectId = resolvedProjectId,
            summary = summary,
            descriptionMarkdown = description,
            assigneeProfileId = assignee?.let { Uuid.parse(it) },
            priorityId = priorityId?.let { Uuid.parse(it) },
            taskTypeId = taskTypeId?.let { Uuid.parse(it) },
            sprintId = sprintId?.let { Uuid.parse(it) },
        ))
        echo("Created: ${result.key} — ${result.summary}")
    }
}

class TaskUpdateCommand : WorkOpsSubcommand("update") {
    override fun help(context: Context) = "Update a task"
    private val id by option("--id", help = "Task UUID")
    private val key by option("--key", "-k", help = "Task key (e.g. BOS-42)")
    private val summary by option("--summary", "-s", help = "New summary")
    private val description by option("--description", "-d", help = "New description (Markdown)")
    private val assignee by option("--assignee", help = "Assignee profile UUID")
    private val clearAssignee by option("--clear-assignee", help = "Unassign the task").flag()
    private val priorityId by option("--priority-id", help = "Priority UUID")
    private val sprintId by option("--sprint-id", help = "Sprint UUID")
    private val clearSprintId by option("--clear-sprint", help = "Remove task from sprint").flag()
    private val expectedVersion by option("--version", help = "Expected version for optimistic lock").long()

    override suspend fun execute(api: WorkOpsApi) {
        val task = when {
            id != null -> api.getTask(Uuid.parse(id!!))
            key != null -> api.getTaskByKey(key!!)
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        if (task == null) { echo("Task not found."); return }
        val version = expectedVersion ?: task.version
        val result = api.updateTask(task.id, UpdateWorkOpsTaskInput(
            summary = summary,
            descriptionMarkdown = description,
            assigneeProfileId = assignee?.let { Uuid.parse(it) },
            clearAssignee = if (clearAssignee) true else null,
            priorityId = priorityId?.let { Uuid.parse(it) },
            sprintId = sprintId?.let { Uuid.parse(it) },
            clearSprintId = if (clearSprintId) true else null,
            expectedVersion = version,
        ))
        echo("Updated: ${result.key} — ${result.summary}")
    }
}

class TaskTransitionCommand : WorkOpsSubcommand("transition") {
    override fun help(context: Context) = "Transition a task to a new status"
    private val id by option("--id", help = "Task UUID")
    private val key by option("--key", "-k", help = "Task key (e.g. BOS-42)")
    private val transitionId by option("--transition-id", help = "Transition UUID").required()
    private val resolutionId by option("--resolution-id", help = "Resolution UUID (for closing transitions)")
    private val comment by option("--comment", help = "Transition comment")
    private val expectedVersion by option("--version", help = "Expected version").long()

    override suspend fun execute(api: WorkOpsApi) {
        val task = when {
            id != null -> api.getTask(Uuid.parse(id!!))
            key != null -> api.getTaskByKey(key!!)
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        if (task == null) { echo("Task not found."); return }
        val version = expectedVersion ?: task.version
        val result = api.transitionTask(
            task.id,
            Uuid.parse(transitionId),
            version,
            resolutionId?.let { Uuid.parse(it) },
            comment,
        )
        echo("Transitioned: ${result.key} → ${result.status.name}")
    }
}

class TaskTransitionsCommand : WorkOpsSubcommand("transitions") {
    override fun help(context: Context) = "List available transitions for a task"
    private val id by option("--id", help = "Task UUID")
    private val key by option("--key", "-k", help = "Task key")

    override suspend fun execute(api: WorkOpsApi) {
        val taskId = when {
            id != null -> Uuid.parse(id!!)
            key != null -> api.getTaskByKey(key!!)?.id
                ?: run { echo("Task not found."); return }
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        val result = api.getTaskTransitions(taskId) ?: run { echo("Task not found."); return }
        val transitions = result.transitions
        if (transitions.isEmpty()) { echo("No transitions available."); return }
        echo("Available transitions for ${result.key}:")
        echo("%-36s  %-20s  %s".format("TRANSITION ID", "NAME", "TO STATUS"))
        echo("-".repeat(80))
        for (t in transitions) {
            echo("%-36s  %-20s  %s".format(t.id, t.name, t.toState.status.name))
        }
    }
}

class TaskHistoryCommand : WorkOpsSubcommand("history") {
    override fun help(context: Context) = "Show audit history for a task"
    private val id by option("--id", help = "Task UUID")
    private val key by option("--key", "-k", help = "Task key")
    private val limit by option("--limit").int().default(20)

    override suspend fun execute(api: WorkOpsApi) {
        val taskId = when {
            id != null -> Uuid.parse(id!!)
            key != null -> api.getTaskByKey(key!!)?.id
                ?: run { echo("Task not found."); return }
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        val history = api.getTaskHistory(taskId, limit = limit)
        if (history.isEmpty()) { echo("No history found."); return }
        for (entry in history) {
            echo("${entry.changedAt}  by ${entry.changedByProfileId ?: entry.changedByPrincipalId}")
            for (c in entry.changes) {
                echo("  ${c.fieldName}: ${c.fromValue} → ${c.toValue}")
            }
        }
    }
}

class TaskSearchCommand : WorkOpsSubcommand("search") {
    override fun help(context: Context) = "Search tasks using BQL (Bosca Query Language)"
    private val query by option("--query", "-q", help = "BQL query string").required()
    private val limit by option("--limit").int().default(50)

    override suspend fun execute(api: WorkOpsApi) {
        val result = api.searchTasks(query, limit = limit)
        val tasks = result.rows
        if (tasks.isEmpty()) { echo("No tasks found."); return }
        echo("%-12s  %-10s  %-15s  %-10s  %s".format("KEY", "TYPE", "STATUS", "PRIORITY", "SUMMARY"))
        echo("-".repeat(100))
        for (row in tasks) {
            val t = row
            echo("%-12s  %-10s  %-15s  %-10s  %s".format(
                t.key, t.taskType.name.take(10), t.status.name.take(15), t.priority.name.take(10), t.summary.take(50)
            ))
        }
    }
}

class TaskDeleteCommand : WorkOpsSubcommand("delete") {
    override fun help(context: Context) = "Soft-delete a task"
    private val id by option("--id", help = "Task UUID")
    private val key by option("--key", "-k", help = "Task key")
    private val expectedVersion by option("--version", help = "Expected version").long()

    override suspend fun execute(api: WorkOpsApi) {
        val task = when {
            id != null -> api.getTask(Uuid.parse(id!!))
            key != null -> api.getTaskByKey(key!!)
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        if (task == null) { echo("Task not found."); return }
        val version = expectedVersion ?: task.version
        val result = api.softDeleteTask(task.id, version)
        echo("Deleted: ${result.key}")
    }
}

class TaskCommentCommand : WorkOpsSubcommand("comment") {
    override fun help(context: Context) = "Add a comment to a task"
    private val id by option("--id", help = "Task UUID")
    private val key by option("--key", "-k", help = "Task key")
    private val content by option("--content", "-c", help = "Comment text").required()

    override suspend fun execute(api: WorkOpsApi) {
        val taskId = when {
            id != null -> Uuid.parse(id!!)
            key != null -> api.getTaskByKey(key!!)?.id
                ?: run { echo("Task not found."); return }
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        val input = bosca.graphql.gen.WorkOpsTaskCommentInput(content = content)
        val result = api.addTaskComment(taskId, input)
        echo("Comment added (id: ${result.id})")
    }
}

class TaskCommentsListCommand : WorkOpsSubcommand("comments") {
    override fun help(context: Context) = "List comments on a task"
    private val id by option("--id", help = "Task UUID")
    private val key by option("--key", "-k", help = "Task key")

    override suspend fun execute(api: WorkOpsApi) {
        val taskId = when {
            id != null -> Uuid.parse(id!!)
            key != null -> api.getTaskByKey(key!!)?.id
                ?: run { echo("Task not found."); return }
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        val comments = api.getTaskComments(taskId)
        if (comments.isEmpty()) { echo("No comments."); return }
        for (c in comments) {
            echo("[${c.created}] ${c.profileId}: ${c.content}")
        }
    }
}

class TaskAddAffectedProjectCommand : WorkOpsSubcommand("add-affected-project") {
    override fun help(context: Context) = "Associate an additional project with a task"
    private val id by option("--id", help = "Task UUID")
    private val key by option("--key", "-k", help = "Task key")
    private val projectId by option("--project-id", help = "Project UUID to associate").required()

    override suspend fun execute(api: WorkOpsApi) {
        val task = when {
            id != null -> api.getTask(Uuid.parse(id!!))
            key != null -> api.getTaskByKey(key!!)
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        if (task == null) { echo("Task not found."); return }
        api.addTaskAffectedProject(task.id, Uuid.parse(projectId))
        echo("Added affected project $projectId to ${task.key}")
    }
}

class TaskRemoveAffectedProjectCommand : WorkOpsSubcommand("remove-affected-project") {
    override fun help(context: Context) = "Remove an affected project association from a task"
    private val id by option("--id", help = "Task UUID")
    private val key by option("--key", "-k", help = "Task key")
    private val projectId by option("--project-id", help = "Project UUID to remove").required()

    override suspend fun execute(api: WorkOpsApi) {
        val task = when {
            id != null -> api.getTask(Uuid.parse(id!!))
            key != null -> api.getTaskByKey(key!!)
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        if (task == null) { echo("Task not found."); return }
        api.removeTaskAffectedProject(task.id, Uuid.parse(projectId))
        echo("Removed affected project $projectId from ${task.key}")
    }
}
