package bosca.cli.workops

import bosca.cli.api.WorkOpsApi
import bosca.graphql.gen.CreateWorkOpsSpecContextInput
import bosca.graphql.gen.CreateWorkOpsSpecInput
import bosca.graphql.gen.UpdateWorkOpsSpecInput
import bosca.graphql.gen.WorkOpsGenerationSource
import bosca.graphql.gen.WorkOpsSpecContextType
import bosca.graphql.gen.WorkOpsSpecCommentInput
import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlin.uuid.Uuid

class SpecCommand : BoscaCliCommand(name = "spec") {
    override fun help(context: Context) = "Manage specs and requirements"
    override fun run() = Unit
}

class SpecListCommand : WorkOpsSubcommand("list") {
    override fun help(context: Context) = "List specs in a project"
    private val projectId by option("--project-id", help = "Project UUID")
    private val projectKey by option("--project-key", "-p", help = "Project key")
    private val programId by option("--program-id", help = "Program UUID")
    private val ownerId by option("--owner-id", help = "Owner profile UUID")
    private val parentId by option("--parent-id", help = "Parent spec UUID")
    private val limit by option("--limit").int().default(50)

    override suspend fun execute(api: WorkOpsApi) {
        val specs = when {
            parentId != null -> api.getSpecChildren(Uuid.parse(parentId!!), limit = limit)
            projectId != null -> api.getSpecsByProject(Uuid.parse(projectId!!), limit = limit)
            projectKey != null -> {
                val proj = api.getProjectByKey(projectKey!!)
                    ?: run { echo("Error: project '${projectKey}' not found", err = true); return }
                api.getSpecsByProject(proj.id, limit = limit)
            }
            programId != null -> api.getSpecsByProgram(Uuid.parse(programId!!), limit = limit)
            ownerId != null -> api.getSpecsByOwner(Uuid.parse(ownerId!!), limit = limit)
            else -> { echo("Error: --project-id, --project-key, --program-id, --owner-id, or --parent-id required", err = true); return }
        }
        if (specs.isEmpty()) { echo("No specs found."); return }
        echo("%-12s  %-15s  %-10s  %-36s  %s".format("KEY", "STATUS", "CHILDREN", "ID", "PROJECT"))
        echo("-".repeat(110))
        for (s in specs) {
            echo("%-12s  %-15s  %3d/%-6d  %-36s  %s".format(
                s.key, s.status.name.take(15),
                s.childDoneCount, s.childCount,
                s.id, s.project?.key ?: "-"
            ))
        }
    }
}

class SpecGetCommand : WorkOpsSubcommand("get") {
    override fun help(context: Context) = "Get a spec by ID or key"
    private val id by option("--id", help = "Spec UUID")
    private val key by option("--key", "-k", help = "Spec key")

    override suspend fun execute(api: WorkOpsApi) {
        val spec = when {
            id != null -> api.getSpec(Uuid.parse(id!!))
            key != null -> api.getSpecByKey(key!!)
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        if (spec == null) { echo("Spec not found."); return }
        echo("Key:         ${spec.key}")
        echo("Status:      ${spec.status.name} (${spec.status.category})")
        echo("Project:     ${spec.project?.let { "${it.key} — ${it.name}" } ?: "none"}")
        echo("Owner:       ${spec.ownerProfileId}")
        echo("Metadata:    ${spec.metadataId}")
        if (spec.parentSpec != null) echo("Parent:      ${spec.parentSpec!!.key}")
        echo("Children:    ${spec.childDoneCount}/${spec.childCount}")
        if (spec.gitRepositoryId != null) echo("Git Repo:    ${spec.gitRepositoryId}")
        if (spec.gitPath != null) echo("Git Path:    ${spec.gitPath}")
        if (spec.labelIds.isNotEmpty()) echo("Labels:      ${spec.labelIds.joinToString(", ")}")
        echo("Created:     ${spec.createdAt}")
        echo("Modified:    ${spec.modifiedAt}")
        echo("Version:     ${spec.version}")
        echo("ID:          ${spec.id}")
    }
}

class SpecCreateCommand : WorkOpsSubcommand("create") {
    override fun help(context: Context) = "Create a new spec. If --metadata-id is omitted, a new document is created automatically (titled --name or the spec key)."
    private val metadataId by option("--metadata-id", help = "Existing metadata UUID. If omitted, a new bosca/v-document is auto-created.")
    private val name by option("--name", help = "Name for the auto-created metadata. Used only when --metadata-id is omitted.")
    private val projectId by option("--project-id", help = "Project UUID")
    private val projectKey by option("--project-key", "-p", help = "Project key")
    private val programId by option("--program-id", help = "Program UUID")
    private val parentSpecId by option("--parent-id", help = "Parent spec UUID")
    private val workflowId by option("--workflow-id", help = "Workflow UUID")
    private val sortOrder by option("--sort-order").int()
    private val gitRepositoryId by option("--git-repo-id", help = "Git repository UUID")
    private val gitPath by option("--git-path", help = "Path in git repository")

    override suspend fun execute(api: WorkOpsApi) {
        val resolvedProjectId = when {
            projectId != null -> Uuid.parse(projectId!!)
            projectKey != null -> api.getProjectByKey(projectKey!!)?.id
                ?: run { echo("Error: project '${projectKey}' not found", err = true); return }
            else -> null
        }
        val result = api.createSpec(CreateWorkOpsSpecInput(
            metadataId = metadataId?.let { Uuid.parse(it) },
            name = name,
            projectId = resolvedProjectId,
            programId = programId?.let { Uuid.parse(it) },
            parentSpecId = parentSpecId?.let { Uuid.parse(it) },
            workflowId = workflowId?.let { Uuid.parse(it) },
            sortOrder = sortOrder,
            gitRepositoryId = gitRepositoryId?.let { Uuid.parse(it) },
            gitPath = gitPath,
        ))
        echo("Created: ${result.key} (id: ${result.id})")
    }
}

class SpecUpdateCommand : WorkOpsSubcommand("update") {
    override fun help(context: Context) = "Update a spec"
    private val id by option("--id", help = "Spec UUID")
    private val key by option("--key", "-k", help = "Spec key")
    private val ownerProfileId by option("--owner-id", help = "New owner profile UUID")
    private val projectId by option("--project-id", help = "New project UUID")
    private val clearProjectId by option("--clear-project", help = "Remove project association").flag()
    private val programId by option("--program-id", help = "New program UUID")
    private val parentSpecId by option("--parent-id", help = "New parent spec UUID")
    private val clearParentSpecId by option("--clear-parent", help = "Remove parent association").flag()
    private val sortOrder by option("--sort-order").int()
    private val gitRepositoryId by option("--git-repo-id", help = "Git repository UUID")
    private val gitPath by option("--git-path", help = "Path in git repository")
    private val expectedVersion by option("--version", help = "Expected version").long()

    override suspend fun execute(api: WorkOpsApi) {
        val spec = when {
            id != null -> api.getSpec(Uuid.parse(id!!))
            key != null -> api.getSpecByKey(key!!)
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        if (spec == null) { echo("Spec not found."); return }
        val version = expectedVersion ?: spec.version
        val result = api.updateSpec(spec.id, UpdateWorkOpsSpecInput(
            ownerProfileId = ownerProfileId?.let { Uuid.parse(it) },
            projectId = projectId?.let { Uuid.parse(it) },
            clearProjectId = if (clearProjectId) true else null,
            programId = programId?.let { Uuid.parse(it) },
            parentSpecId = parentSpecId?.let { Uuid.parse(it) },
            clearParentSpecId = if (clearParentSpecId) true else null,
            sortOrder = sortOrder,
            gitRepositoryId = gitRepositoryId?.let { Uuid.parse(it) },
            gitPath = gitPath,
            expectedVersion = version,
        ))
        echo("Updated: ${result.key}")
    }
}

class SpecTransitionCommand : WorkOpsSubcommand("transition") {
    override fun help(context: Context) = "Transition a spec to a new workflow status"
    private val id by option("--id", help = "Spec UUID")
    private val key by option("--key", "-k", help = "Spec key")
    private val transitionId by option("--transition-id", help = "Transition UUID").required()
    private val resolutionId by option("--resolution-id", help = "Resolution UUID")
    private val expectedVersion by option("--version", help = "Expected version").long()

    override suspend fun execute(api: WorkOpsApi) {
        val spec = when {
            id != null -> api.getSpec(Uuid.parse(id!!))
            key != null -> api.getSpecByKey(key!!)
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        if (spec == null) { echo("Spec not found."); return }
        val version = expectedVersion ?: spec.version
        val result = api.transitionSpec(
            spec.id, Uuid.parse(transitionId), version,
            resolutionId?.let { Uuid.parse(it) },
        )
        echo("Transitioned: ${result.key} → ${result.status.name}")
    }
}

class SpecDeleteCommand : WorkOpsSubcommand("delete") {
    override fun help(context: Context) = "Soft-delete a spec"
    private val id by option("--id", help = "Spec UUID")
    private val key by option("--key", "-k", help = "Spec key")
    private val expectedVersion by option("--version", help = "Expected version").long()

    override suspend fun execute(api: WorkOpsApi) {
        val spec = when {
            id != null -> api.getSpec(Uuid.parse(id!!))
            key != null -> api.getSpecByKey(key!!)
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        if (spec == null) { echo("Spec not found."); return }
        val version = expectedVersion ?: spec.version
        api.softDeleteSpec(spec.id, version)
        echo("Deleted: ${spec.key}")
    }
}

class SpecHistoryCommand : WorkOpsSubcommand("history") {
    override fun help(context: Context) = "Show audit history for a spec"
    private val id by option("--id", help = "Spec UUID")
    private val key by option("--key", "-k", help = "Spec key")
    private val limit by option("--limit").int().default(20)

    override suspend fun execute(api: WorkOpsApi) {
        val specId = when {
            id != null -> Uuid.parse(id!!)
            key != null -> api.getSpecByKey(key!!)?.id
                ?: run { echo("Spec not found."); return }
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        val history = api.getSpecHistory(specId, limit = limit)
        if (history.isEmpty()) { echo("No history found."); return }
        for (entry in history) {
            echo("${entry.changedAt}  by ${entry.changedByProfileId ?: entry.changedByPrincipalId}")
            for (c in entry.changes) {
                echo("  ${c.fieldName}: ${c.fromValue} → ${c.toValue}")
            }
        }
    }
}

class SpecContextListCommand : WorkOpsSubcommand("contexts") {
    override fun help(context: Context) = "List contexts linked to a spec"
    private val id by option("--id", help = "Spec UUID")
    private val key by option("--key", "-k", help = "Spec key")

    override suspend fun execute(api: WorkOpsApi) {
        val specId = when {
            id != null -> Uuid.parse(id!!)
            key != null -> api.getSpecByKey(key!!)?.id
                ?: run { echo("Spec not found."); return }
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        val contexts = api.getSpecContexts(specId)
        if (contexts.isEmpty()) { echo("No contexts."); return }
        echo("%-36s  %-15s  %-36s  %s".format("ID", "TYPE", "TARGET", "LABEL"))
        echo("-".repeat(100))
        for (c in contexts) {
            echo("%-36s  %-15s  %-36s  %s".format(
                c.id, c.contextType.name.take(15), c.targetId.take(36), c.label ?: ""
            ))
        }
    }
}

class SpecContextAddCommand : WorkOpsSubcommand("add-context") {
    override fun help(context: Context) = "Add a context to a spec"
    private val id by option("--id", help = "Spec UUID")
    private val key by option("--key", "-k", help = "Spec key")
    private val contextType by option("--type", help = "Context type (GIT_RESOURCE, METADATA, COLLECTION, PROFILE, CHAT_CHANNEL, AI_SESSION, SPEC, TASK, PROJECT, EXTERNAL_URI)").required()
    private val targetId by option("--target-id", help = "Target ID").required()
    private val label by option("--label", help = "Display label")

    override suspend fun execute(api: WorkOpsApi) {
        val specId = when {
            id != null -> Uuid.parse(id!!)
            key != null -> api.getSpecByKey(key!!)?.id
                ?: run { echo("Spec not found."); return }
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        val result = api.addSpecContext(specId, CreateWorkOpsSpecContextInput(
            contextType = WorkOpsSpecContextType.valueOf(contextType.uppercase()),
            targetId = targetId,
            label = label,
        ))
        echo("Context added (id: ${result.id})")
    }
}

class SpecContextRemoveCommand : WorkOpsSubcommand("remove-context") {
    override fun help(context: Context) = "Remove a context from a spec"
    private val specId by option("--spec-id", help = "Spec UUID").required()
    private val contextId by option("--context-id", help = "Context UUID").required()

    override suspend fun execute(api: WorkOpsApi) {
        api.removeSpecContext(Uuid.parse(specId), Uuid.parse(contextId))
        echo("Context removed.")
    }
}

class SpecCommentCommand : WorkOpsSubcommand("comment") {
    override fun help(context: Context) = "Add a comment to a spec"
    private val id by option("--id", help = "Spec UUID")
    private val key by option("--key", "-k", help = "Spec key")
    private val content by option("--content", "-c", help = "Comment text").required()

    override suspend fun execute(api: WorkOpsApi) {
        val specId = when {
            id != null -> Uuid.parse(id!!)
            key != null -> api.getSpecByKey(key!!)?.id
                ?: run { echo("Spec not found."); return }
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        val result = api.addSpecComment(specId, WorkOpsSpecCommentInput(content = content))
        echo("Comment added (id: ${result.id})")
    }
}

class SpecCommentsListCommand : WorkOpsSubcommand("comments") {
    override fun help(context: Context) = "List comments on a spec"
    private val id by option("--id", help = "Spec UUID")
    private val key by option("--key", "-k", help = "Spec key")

    override suspend fun execute(api: WorkOpsApi) {
        val specId = when {
            id != null -> Uuid.parse(id!!)
            key != null -> api.getSpecByKey(key!!)?.id
                ?: run { echo("Spec not found."); return }
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        val comments = api.getSpecComments(specId)
        if (comments.isEmpty()) { echo("No comments."); return }
        for (c in comments) {
            echo("[${c.created}] ${c.profileId}: ${c.content}")
        }
    }
}

class SpecGenerateTasksCommand : WorkOpsSubcommand("generate-tasks") {
    override fun help(context: Context) = "Generate tasks from a spec's requirements"
    private val id by option("--id", help = "Spec UUID")
    private val key by option("--key", "-k", help = "Spec key")
    private val metadataVersion by option("--metadata-version", help = "Metadata version to snapshot").int().required()
    private val source by option("--source", help = "Generation source (CLAUDE_CODE, MANUAL, KIT)").required()
    private val agentSessionId by option("--agent-session-id", help = "Agent session UUID")

    override suspend fun execute(api: WorkOpsApi) {
        val specId = when {
            id != null -> Uuid.parse(id!!)
            key != null -> api.getSpecByKey(key!!)?.id
                ?: run { echo("Spec not found."); return }
            else -> { echo("Error: --id or --key required", err = true); return }
        }
        val result = api.generateSpecTasks(
            specId, metadataVersion,
            WorkOpsGenerationSource.valueOf(source.uppercase()),
            agentSessionId?.let { Uuid.parse(it) },
        )
        echo("Generated ${result.generatedTaskIds.size} tasks:")
        for (taskId in result.generatedTaskIds) {
            echo("  $taskId")
        }
    }
}
