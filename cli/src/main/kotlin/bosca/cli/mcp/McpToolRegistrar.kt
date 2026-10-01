package bosca.cli.mcp

import bosca.cli.api.ContentApi
import bosca.cli.api.WorkOpsApi
import bosca.graphql.gen.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import kotlin.uuid.Uuid

/**
 * Registers coarse-grained MCP tools for the entire workops surface.
 * Each tool maps to one or more WorkOpsApi operations, selected by
 * parameters to keep the tool count AI-manageable.
 */
object McpToolRegistrar {

    private const val TASK_STATUS_REMINDER = "\n\n⚠️ REMINDER: Keep this task's status current. If you are about to work on it, transition it to IN_PROGRESS now (use workops_task_transitions to find available transitions). When done, transition to DONE with a resolution. If you decompose this into subtasks, create a workops task for EACH one with parentTaskId set to this task's id."
    private const val SPEC_STATUS_REMINDER = "\n\n⚠️ REMINDER: Keep this spec's status AND document current. If you are beginning execution, transition it to IN_PROGRESS (use workops_reference_data action=workflows). When all requirements are done, transition to DONE. As you learn new things or change approach, update the spec document via content_metadata action=set_content to reflect the current plan."
    private const val REQUIREMENT_STATUS_REMINDER = "\n\n⚠️ REMINDER: This requirement has a linked Task (see taskId). 1) Write the requirement body to its document via content_metadata action=set_content id=<metadataId> markdownContent=… 2) The linked Task was snapshotted from the document at create time (empty) and is NOT re-synced — push the same body to it via workops_task action=update id=<taskId> descriptionMarkdown=…, or its page stays blank. 3) Keep the linked Task's status current — IN_PROGRESS when starting, DONE when the requirement is satisfied."

    fun registerAll(server: Server, api: WorkOpsApi, contentApi: ContentApi) {
        registerProjectTools(server, api)
        registerTaskTools(server, api)
        registerSpecInstructionsTool(server)
        registerSpecTools(server, api)
        registerRequirementTools(server, api)
        registerBoardTools(server, api)
        registerSprintTools(server, api)
        registerWorklogTools(server, api)
        registerCommentTools(server, api)
        registerLinkTools(server, api)
        registerSearchTools(server, api)
        registerRefDataTools(server, api)
        registerNotificationTools(server, api)
        registerPortfolioTools(server, api)
        registerProgramTools(server, api)
    }

    // ── Portfolios ──────────────────────────────────────────────

    private fun registerPortfolioTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_portfolio",
                description = "Manage workops portfolios. Use action: list, get, create, archive.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "get", "create", "archive").map { JsonPrimitive(it) })) })
                        put("id", buildJsonObject { put("type", "string"); put("description", "Portfolio UUID (for get/archive)") })
                        put("key", buildJsonObject { put("type", "string"); put("description", "Portfolio key (for get/create)") })
                        put("name", buildJsonObject { put("type", "string"); put("description", "Portfolio name (for create)") })
                        put("description", buildJsonObject { put("type", "string") })
                        put("ownerProfileId", buildJsonObject { put("type", "string"); put("description", "Owner profile UUID (for create)") })
                        put("expectedVersion", buildJsonObject { put("type", "integer"); put("description", "Version for optimistic lock (archive)") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            val action = args.str("action")
            try {
                val result = when (action) {
                    "list" -> json.encodeToString(JsonArray.serializer(), JsonArray(api.listPortfolios().map { it.toJson() }))
                    "get" -> {
                        val p = args.optStr("id")?.let { api.getPortfolio(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getPortfolioByKey(it) }
                            ?: error("Provide id or key")
                        json.encodeToString(JsonElement.serializer(), p.toJson())
                    }
                    "create" -> {
                        val p = api.createPortfolio(WorkOpsPortfolioInput(
                            key = args.str("key"), name = args.str("name"),
                            description = args.optStr("description"),
                            ownerProfileId = Uuid.parse(args.str("ownerProfileId")),
                        ))
                        json.encodeToString(JsonElement.serializer(), p.toJson())
                    }
                    "archive" -> {
                        val p = api.archivePortfolio(Uuid.parse(args.str("id")), args.long("expectedVersion"))
                        json.encodeToString(JsonElement.serializer(), p.toJson())
                    }
                    else -> error("Unknown action: $action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Programs ────────────────────────────────────────────────

    private fun registerProgramTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_program",
                description = "Manage workops programs. Use action: list, get, create, archive.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "get", "create", "archive").map { JsonPrimitive(it) })) })
                        put("id", buildJsonObject { put("type", "string") })
                        put("portfolioId", buildJsonObject { put("type", "string"); put("description", "Filter by portfolio UUID (for list) or parent (for create)") })
                        put("key", buildJsonObject { put("type", "string") })
                        put("name", buildJsonObject { put("type", "string") })
                        put("description", buildJsonObject { put("type", "string") })
                        put("ownerProfileId", buildJsonObject { put("type", "string") })
                        put("expectedVersion", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> {
                        val programs = args.optStr("portfolioId")?.let { api.getProgramsByPortfolio(Uuid.parse(it)) } ?: api.listPrograms()
                        json.encodeToString(JsonArray.serializer(), JsonArray(programs.map { it.toJson() }))
                    }
                    "get" -> {
                        val p = api.getProgram(Uuid.parse(args.str("id"))) ?: error("Program not found")
                        json.encodeToString(JsonElement.serializer(), p.toJson())
                    }
                    "create" -> {
                        val p = api.createProgram(WorkOpsProgramInput(
                            portfolioId = Uuid.parse(args.str("portfolioId")),
                            key = args.str("key"), name = args.str("name"),
                            description = args.optStr("description"),
                            ownerProfileId = Uuid.parse(args.str("ownerProfileId")),
                        ))
                        json.encodeToString(JsonElement.serializer(), p.toJson())
                    }
                    "archive" -> {
                        val p = api.archiveProgram(Uuid.parse(args.str("id")), args.long("expectedVersion"))
                        json.encodeToString(JsonElement.serializer(), p.toJson())
                    }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Projects ────────────────────────────────────────────────

    private fun registerProjectTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_project",
                description = "Manage workops projects. Use action: list, get, create, archive.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "get", "create", "archive").map { JsonPrimitive(it) })) })
                        put("id", buildJsonObject { put("type", "string"); put("description", "Project UUID") })
                        put("key", buildJsonObject { put("type", "string"); put("description", "Project key (e.g. BOS)") })
                        put("programId", buildJsonObject { put("type", "string"); put("description", "Filter by program (list) or parent (create)") })
                        put("name", buildJsonObject { put("type", "string") })
                        put("description", buildJsonObject { put("type", "string") })
                        put("ownerProfileId", buildJsonObject { put("type", "string") })
                        put("expectedVersion", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> {
                        val projects = args.optStr("programId")?.let { api.getProjectsByProgram(Uuid.parse(it)) } ?: api.listProjects()
                        json.encodeToString(JsonArray.serializer(), JsonArray(projects.map { it.toJson() }))
                    }
                    "get" -> {
                        val p = args.optStr("id")?.let { api.getProject(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getProjectByKey(it) }
                            ?: error("Provide id or key")
                        if (p == null) error("Project not found")
                        json.encodeToString(JsonElement.serializer(), p.toJson())
                    }
                    "create" -> {
                        val p = api.createProject(WorkOpsProjectInput(
                            programId = Uuid.parse(args.str("programId")),
                            key = args.str("key"), name = args.str("name"),
                            description = args.optStr("description"),
                            ownerProfileId = Uuid.parse(args.str("ownerProfileId")),
                        ))
                        json.encodeToString(JsonElement.serializer(), p.toJson())
                    }
                    "archive" -> {
                        val p = api.archiveProject(Uuid.parse(args.str("id")), args.long("expectedVersion"))
                        json.encodeToString(JsonElement.serializer(), p.toJson())
                    }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Tasks ───────────────────────────────────────────────────

    private fun registerTaskTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_task",
                description = """
                    Manage workops tasks. Actions: get, list, create, update, transition, delete, restore, addAffectedProject, removeAffectedProject. Use 'get' with id or key to fetch a single task. Use 'list' with projectId or projectKey. Use 'transition' to change status. Use 'addAffectedProject'/'removeAffectedProject' to manage cross-project associations.

                    ⚠️ MANDATORY STATUS TRACKING: Agents MUST keep task statuses up-to-date at all times. This is not optional.
                    - When you BEGIN work on a task: transition it to IN_PROGRESS immediately (use workops_task_transitions to find the transition, then action=transition).
                    - When you COMPLETE work on a task: transition it to DONE with an appropriate resolution.
                    - When a task is BLOCKED or cannot proceed: add a comment explaining why and transition to the appropriate status.
                    - When you STOP working on a task without completing it: add a comment explaining the current state so the next person can pick up.
                    Stale statuses (e.g. a task still in TODO that has been worked on, or still IN_PROGRESS after completion) are treated as data integrity failures. Always transition status BEFORE and AFTER doing the actual work.

                    ⚠️ MANDATORY SUBTASK TRACKING: When you decompose work into subtasks (e.g. breaking a task into smaller steps, spinning off sub-problems, or creating follow-up work), you MUST create a workops task for EACH subtask using action=create with parentTaskId set to the parent task's UUID. This establishes the task hierarchy so progress rolls up. Every unit of work an agent performs must be tracked as a task in workops — do not do work that is invisible to the project tracking system. Each subtask must follow the same status tracking rules above.
                """.trimIndent(),
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("get", "list", "create", "update", "transition", "delete", "restore", "addAffectedProject", "removeAffectedProject").map { JsonPrimitive(it) })) })
                        put("id", buildJsonObject { put("type", "string"); put("description", "Task UUID") })
                        put("key", buildJsonObject { put("type", "string"); put("description", "Task key (e.g. BOS-42)") })
                        put("projectId", buildJsonObject { put("type", "string"); put("description", "Project UUID (for list/create)") })
                        put("projectKey", buildJsonObject { put("type", "string"); put("description", "Project key (for list/create)") })
                        put("summary", buildJsonObject { put("type", "string") })
                        put("descriptionMarkdown", buildJsonObject { put("type", "string") })
                        put("assigneeProfileId", buildJsonObject { put("type", "string") })
                        put("clearAssignee", buildJsonObject { put("type", "boolean") })
                        put("priorityId", buildJsonObject { put("type", "string") })
                        put("taskTypeId", buildJsonObject { put("type", "string") })
                        put("sprintId", buildJsonObject { put("type", "string"); put("description", "Sprint UUID (for create/update)") })
                        put("clearSprintId", buildJsonObject { put("type", "boolean"); put("description", "Remove task from sprint (for update)") })
                        put("parentTaskId", buildJsonObject { put("type", "string"); put("description", "Parent task UUID — set this when creating subtasks to establish hierarchy (for create/update)") })
                        put("clearParentTask", buildJsonObject { put("type", "boolean"); put("description", "Remove parent task relationship (for update). Takes precedence over parentTaskId.") })
                        put("affectedProjectIds", buildJsonObject { put("type", "array"); put("description", "Affected project UUIDs (for create)"); put("items", buildJsonObject { put("type", "string") }) })
                        put("affectedProjectId", buildJsonObject { put("type", "string"); put("description", "Affected project UUID (for addAffectedProject/removeAffectedProject)") })
                        put("transitionId", buildJsonObject { put("type", "string"); put("description", "Workflow transition UUID (for transition action)") })
                        put("resolutionId", buildJsonObject { put("type", "string"); put("description", "Resolution UUID (for closing transitions)") })
                        put("comment", buildJsonObject { put("type", "string"); put("description", "Transition comment") })
                        put("expectedVersion", buildJsonObject { put("type", "integer") })
                        put("offset", buildJsonObject { put("type", "integer") })
                        put("limit", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "get" -> {
                        val t = args.optStr("id")?.let { api.getTask(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getTaskByKey(it) }
                            ?: error("Provide id or key")
                        if (t == null) error("Task not found")
                        json.encodeToString(JsonElement.serializer(), t.toJson())
                    }
                    "list" -> {
                        val pid = args.optStr("projectId")?.let { Uuid.parse(it) }
                            ?: args.optStr("projectKey")?.let { api.getProjectByKey(it)?.id ?: error("Project not found") }
                            ?: error("Provide projectId or projectKey")
                        val tasks = api.getTasksByProject(pid, offset = args.optLong("offset") ?: 0, limit = args.optInt("limit") ?: 50)
                        json.encodeToString(JsonArray.serializer(), JsonArray(tasks.map { it.toJson() }))
                    }
                    "create" -> {
                        val pid = args.optStr("projectId")?.let { Uuid.parse(it) }
                            ?: args.optStr("projectKey")?.let { api.getProjectByKey(it)?.id ?: error("Project not found") }
                            ?: error("Provide projectId or projectKey")
                        val t = api.createTask(CreateWorkOpsTaskInput(
                            projectId = pid,
                            summary = args.str("summary"),
                            descriptionMarkdown = args.optStr("descriptionMarkdown"),
                            assigneeProfileId = args.optStr("assigneeProfileId")?.let { Uuid.parse(it) },
                            priorityId = args.optStr("priorityId")?.let { Uuid.parse(it) },
                            taskTypeId = args.optStr("taskTypeId")?.let { Uuid.parse(it) },
                            sprintId = args.optStr("sprintId")?.let { Uuid.parse(it) },
                            parentTaskId = args.optStr("parentTaskId")?.let { Uuid.parse(it) },
                        ))
                        json.encodeToString(JsonElement.serializer(), t.toJson()) + TASK_STATUS_REMINDER
                    }
                    "update" -> {
                        val task = args.optStr("id")?.let { api.getTask(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getTaskByKey(it) }
                            ?: error("Provide id or key")
                        if (task == null) error("Task not found")
                        val version = args.optLong("expectedVersion") ?: task.version
                        val t = api.updateTask(task.id, UpdateWorkOpsTaskInput(
                            summary = args.optStr("summary"),
                            descriptionMarkdown = args.optStr("descriptionMarkdown"),
                            assigneeProfileId = args.optStr("assigneeProfileId")?.let { Uuid.parse(it) },
                            clearAssignee = args.optBool("clearAssignee"),
                            priorityId = args.optStr("priorityId")?.let { Uuid.parse(it) },
                            sprintId = args.optStr("sprintId")?.let { Uuid.parse(it) },
                            clearSprintId = args.optBool("clearSprintId"),
                            parentTaskId = args.optStr("parentTaskId")?.let { Uuid.parse(it) },
                            clearParentTask = args.optBool("clearParentTask"),
                            expectedVersion = version,
                        ))
                        json.encodeToString(JsonElement.serializer(), t.toJson()) + TASK_STATUS_REMINDER
                    }
                    "transition" -> {
                        val task = args.optStr("id")?.let { api.getTask(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getTaskByKey(it) }
                            ?: error("Provide id or key")
                        if (task == null) error("Task not found")
                        val version = args.optLong("expectedVersion") ?: task.version
                        val t = api.transitionTask(
                            task.id, Uuid.parse(args.str("transitionId")), version,
                            args.optStr("resolutionId")?.let { Uuid.parse(it) },
                            args.optStr("comment"),
                        )
                        json.encodeToString(JsonElement.serializer(), t.toJson())
                    }
                    "delete" -> {
                        val task = args.optStr("id")?.let { api.getTask(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getTaskByKey(it) }
                            ?: error("Provide id or key")
                        if (task == null) error("Task not found")
                        val version = args.optLong("expectedVersion") ?: task.version
                        api.softDeleteTask(task.id, version)
                        """{"deleted": true, "key": "${task.key}"}"""
                    }
                    "restore" -> {
                        val task = args.optStr("id")?.let { api.getTask(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getTaskByKey(it) }
                            ?: error("Provide id or key")
                        if (task == null) error("Task not found")
                        val version = args.optLong("expectedVersion") ?: task.version
                        val t = api.restoreTask(task.id, version)
                        json.encodeToString(JsonElement.serializer(), t.toJson())
                    }
                    "addAffectedProject" -> {
                        val task = args.optStr("id")?.let { api.getTask(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getTaskByKey(it) }
                            ?: error("Provide id or key")
                        if (task == null) error("Task not found")
                        val projectId = Uuid.parse(args.str("affectedProjectId"))
                        api.addTaskAffectedProject(task.id, projectId)
                        """{"added": true, "taskKey": "${task.key}", "projectId": "$projectId"}"""
                    }
                    "removeAffectedProject" -> {
                        val task = args.optStr("id")?.let { api.getTask(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getTaskByKey(it) }
                            ?: error("Provide id or key")
                        if (task == null) error("Task not found")
                        val projectId = Uuid.parse(args.str("affectedProjectId"))
                        api.removeTaskAffectedProject(task.id, projectId)
                        """{"removed": true, "taskKey": "${task.key}", "projectId": "$projectId"}"""
                    }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }

        server.addTool(
            Tool(
                name = "workops_task_transitions",
                description = "List available workflow transitions for a task, showing what status changes are possible from the current state.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string"); put("description", "Task UUID") })
                        put("key", buildJsonObject { put("type", "string"); put("description", "Task key") })
                    },
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val taskId = args.optStr("id")?.let { Uuid.parse(it) }
                    ?: args.optStr("key")?.let { api.getTaskByKey(it)?.id ?: error("Task not found") }
                    ?: error("Provide id or key")
                val result = api.getTaskTransitions(taskId) ?: error("Task not found")
                val arr = JsonArray(result.transitions.map { t ->
                    buildJsonObject {
                        put("id", t.id.toString())
                        put("name", t.name)
                        put("description", t.description)
                        put("toStatus", t.toState.status.name)
                        put("toStatusCategory", t.toState.status.category.name)
                    }
                })
                CallToolResult(content = listOf(TextContent(json.encodeToString(JsonArray.serializer(), arr))))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }

        server.addTool(
            Tool(
                name = "workops_task_history",
                description = "Get the audit history for a task showing all changes over time.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string"); put("description", "Task UUID") })
                        put("key", buildJsonObject { put("type", "string"); put("description", "Task key") })
                        put("limit", buildJsonObject { put("type", "integer") })
                    },
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val taskId = args.optStr("id")?.let { Uuid.parse(it) }
                    ?: args.optStr("key")?.let { api.getTaskByKey(it)?.id ?: error("Task not found") }
                    ?: error("Provide id or key")
                val entries = api.getTaskHistory(taskId, limit = args.optInt("limit") ?: 50)
                val arr = JsonArray(entries.map { e ->
                    buildJsonObject {
                        put("id", e.id.toString())
                        put("changedAt", e.changedAt.toString())
                        put("changedBy", (e.changedByProfileId ?: e.changedByPrincipalId).toString())
                        put("changes", JsonArray(e.changes.map { c ->
                            buildJsonObject {
                                put("field", c.fieldName)
                                put("from", c.fromValue ?: JsonNull)
                                put("to", c.toValue ?: JsonNull)
                            }
                        }))
                    }
                })
                CallToolResult(content = listOf(TextContent(json.encodeToString(JsonArray.serializer(), arr))))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Spec Instructions ───────────────────────────────────────

    private fun registerSpecInstructionsTool(server: Server) {
        server.addTool(
            Tool(
                name = "workops_spec_instructions",
                description = "⚠️ MANDATORY: Read this BEFORE creating or working with workops Specs, Requirements, or generating Tasks from a Spec. Returns the mental model, lifecycle, prerequisites, mandatory status tracking rules, and a worked plan-mode example. Agents MUST follow the status tracking rules described here — keeping spec/requirement/task statuses up-to-date is a hard requirement, not optional. No arguments.",
                inputSchema = ToolSchema(properties = buildJsonObject {}),
            ),
        ) { _ ->
            CallToolResult(content = listOf(TextContent(SPEC_INSTRUCTIONS)))
        }
    }

    // ── Specs ───────────────────────────────────────────────────

    private fun registerSpecTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_spec",
                description = """
                    Manage workops Specs — the unit of persistent planning in workops. A Spec is a versioned plan/requirements document tied to a workflow (status, owner, history, hierarchy) and backed by a content metadata document.

                    USE THIS WHEN: capturing a plan, RFC, design doc, or requirements that needs to persist beyond the conversation, be tracked through a workflow, and decompose into actionable tasks.

                    THE PIPELINE: Spec (the plan) → Requirements (the items inside the plan, each auto-linked to a Task) → generate_tasks (creates additional concrete Tasks from each Requirement's content).

                    ⚠️ A SPEC WITHOUT REQUIREMENTS IS INCOMPLETE. After workops_spec action=create, you MUST decompose the spec's body into Requirements using workops_requirement action=create (parentType=SPEC, parentId=<this spec's id>). The Spec is the narrative plan; the Requirements are the actionable items. A Spec on its own cannot drive task generation, cannot be reviewed for completeness, and cannot be planned against. Do not consider spec creation done until at least one Requirement has been added.

                    PREREQUISITES for create:
                    - A Project must exist (use workops_project). Pass `projectId` or `projectKey`.
                    - That's it. No metadata setup needed — pass `name` and a `bosca/v-document` metadata is auto-created, bound to the seeded Spec Document Template, and linked. You can also pass an existing `metadataId` to attach to a pre-built document.

                    ACTIONS:
                    - get: fetch by id or key.
                    - list: list specs in a project/program/by owner (one of projectId, projectKey, programId, ownerId required).
                    - children: list direct sub-specs of a parent (pass parentSpecId or id).
                    - create: create a new spec. Required: projectId or projectKey. Optional: name (titles the auto-created document — defaults to the minted SPEC key), metadataId (skip auto-create), parentSpecId, workflowId, sortOrder, gitRepositoryId+gitPath. MUST be followed by workops_requirement creates that decompose the spec body.
                    - update: edit owner/project/program/parent/sortOrder/git binding. Pass id or key, optionally expectedVersion for optimistic locking.
                    - transition: move to a new workflow state. Pass transitionId (from workops_reference_data action=workflows). Optional: resolutionId.
                    - delete / restore: soft delete or restore.
                    - contexts / add_context / remove_context: manage curated references. ContextTypes: GIT_RESOURCE, METADATA, COLLECTION, PROFILE, CHAT_CHANNEL, AI_SESSION (the Claude Code session that authored the spec — store its UUID here), SPEC, TASK, PROJECT, EXTERNAL_URI.
                    - comments / add_comment: discussion thread on the spec.
                    - generate_tasks: snapshot the spec's current requirements and create Tasks from each. Required: metadataVersion (use 1 if you have not edited the document), source (CLAUDE_CODE | MANUAL | KIT). For source=CLAUDE_CODE, pass agentSessionId. Note: each Requirement already has one linked Task; generate_tasks creates ADDITIONAL Tasks.

                    To edit the spec's document body (the actual plan text), use the content_metadata tool against the returned metadataId.

                    ⚠️ MANDATORY: KEEP THE SPEC DOCUMENT CURRENT. The spec body is the living plan — not a snapshot from creation time. As you execute:
                    - When you LEARN something new (a constraint, a dependency, a risk): update the spec document to reflect it via content_metadata action=set_content.
                    - When you CHANGE APPROACH (different design, dropped a requirement, added a new one): update the spec document AND add/remove/update Requirements to match.
                    - When a requirement turns out to be wrong or unnecessary: update or delete it, and update the spec body to explain why.
                    - When work reveals new requirements: add them as new Requirements AND update the spec body.
                    The spec must always reflect the CURRENT plan, not the original plan. A spec whose document diverges from reality is as broken as a stale status. Use add_comment for point-in-time notes; use the document body for the authoritative current state.

                    ⚠️ MANDATORY STATUS TRACKING: Agents MUST keep spec statuses up-to-date at all times. This is not optional.
                    - When you BEGIN executing a spec's plan: transition the spec to IN_PROGRESS immediately (use workops_reference_data action=workflows to find transitions, then action=transition).
                    - When ALL requirements under the spec are DONE: transition the spec to DONE.
                    - When a spec is BLOCKED or paused: add a comment explaining why and transition to the appropriate status.
                    - When you STOP working on a spec without completing it: add a comment explaining the current state.
                    Stale statuses are treated as data integrity failures. A spec that has been worked on but still shows TODO, or is complete but still shows IN_PROGRESS, is broken tracking data.

                    EXPECTED WORKFLOW (per new spec):
                      1. workops_spec action=create projectKey=… name=…
                      2. content_metadata action=set_content id=<returned metadataId> documentContent=<typed Bosca Document>
                      3. For each major item / metric / deliverable in the spec body:
                           workops_requirement action=create parentType=SPEC parentId=<spec id> name=…
                           content_metadata action=set_content id=<requirement metadataId> documentContent=<typed body>
                      4. (Optional) workops_spec action=generate_tasks id=<spec id> metadataVersion=1 source=CLAUDE_CODE agentSessionId=…
                      5. When beginning execution: workops_spec action=transition (to IN_PROGRESS)
                      6. When all work is done: workops_spec action=transition (to DONE)
                """.trimIndent(),
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("get", "list", "create", "update", "transition", "delete", "restore", "children", "contexts", "add_context", "remove_context", "comments", "add_comment", "generate_tasks").map { JsonPrimitive(it) })) })
                        put("id", buildJsonObject { put("type", "string"); put("description", "Spec UUID") })
                        put("key", buildJsonObject { put("type", "string"); put("description", "Spec key (e.g. SPEC-12)") })
                        put("projectId", buildJsonObject { put("type", "string"); put("description", "Project UUID. Required for create (or use projectKey). Filter for list.") })
                        put("projectKey", buildJsonObject { put("type", "string"); put("description", "Project key (alternative to projectId)") })
                        put("programId", buildJsonObject { put("type", "string"); put("description", "Program UUID (filter for list, optional for create)") })
                        put("ownerId", buildJsonObject { put("type", "string"); put("description", "Owner profile UUID (filter for list)") })
                        put("parentSpecId", buildJsonObject { put("type", "string"); put("description", "Parent spec UUID for hierarchy (used by children/create/update)") })
                        put("metadataId", buildJsonObject { put("type", "string"); put("description", "Optional. Existing metadata UUID to attach. If omitted on create, a new bosca/v-document is auto-created titled `name` (or the minted spec key).") })
                        put("name", buildJsonObject { put("type", "string"); put("description", "Title for the auto-created document when metadataId is omitted (create only). Recommended.") })
                        put("ownerProfileId", buildJsonObject { put("type", "string"); put("description", "Owner profile UUID (update only)") })
                        put("workflowId", buildJsonObject { put("type", "string"); put("description", "Workflow UUID. If omitted on create, the project's default workflow is used.") })
                        put("sortOrder", buildJsonObject { put("type", "integer"); put("description", "Sort order among siblings under the same parent") })
                        put("gitRepositoryId", buildJsonObject { put("type", "string"); put("description", "Bind spec to a git repo. Requires gitPath.") })
                        put("gitPath", buildJsonObject { put("type", "string"); put("description", "Path within the git repository") })
                        put("transitionId", buildJsonObject { put("type", "string"); put("description", "Workflow transition UUID. Look up via workops_reference_data action=workflows.") })
                        put("resolutionId", buildJsonObject { put("type", "string"); put("description", "Resolution UUID (optional, for transitions into terminal states)") })
                        put("expectedVersion", buildJsonObject { put("type", "integer"); put("description", "Optimistic-lock version. Optional — if omitted, the current version is used.") })
                        put("contextType", buildJsonObject {
                            put("type", "string")
                            put("description", "Type of contextual reference being linked")
                            put("enum", JsonArray(listOf("GIT_RESOURCE", "METADATA", "COLLECTION", "PROFILE", "CHAT_CHANNEL", "AI_SESSION", "SPEC", "TASK", "PROJECT", "EXTERNAL_URI").map { JsonPrimitive(it) }))
                        })
                        put("targetId", buildJsonObject { put("type", "string"); put("description", "Target identifier for the context (UUID, commit SHA, URL, etc., depending on contextType)") })
                        put("label", buildJsonObject { put("type", "string"); put("description", "Friendly display label for the context") })
                        put("contextId", buildJsonObject { put("type", "string"); put("description", "Context UUID (for remove_context)") })
                        put("content", buildJsonObject { put("type", "string"); put("description", "Comment text (for add_comment)") })
                        put("metadataVersion", buildJsonObject { put("type", "integer"); put("description", "Version of the spec's document to snapshot for generate_tasks. Pass 1 if document has not been edited.") })
                        put("source", buildJsonObject {
                            put("type", "string")
                            put("description", "Who/what is generating the tasks")
                            put("enum", JsonArray(listOf("CLAUDE_CODE", "MANUAL", "KIT").map { JsonPrimitive(it) }))
                        })
                        put("agentSessionId", buildJsonObject { put("type", "string"); put("description", "Agent session UUID (recommended when source=CLAUDE_CODE)") })
                        put("offset", buildJsonObject { put("type", "integer") })
                        put("limit", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "get" -> {
                        val s = args.optStr("id")?.let { api.getSpec(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getSpecByKey(it) }
                            ?: error("Provide id or key")
                        if (s == null) error("Spec not found")
                        json.encodeToString(JsonElement.serializer(), s.toSpecJson())
                    }
                    "list" -> {
                        val specs = when {
                            args.optStr("projectId") != null -> api.getSpecsByProject(Uuid.parse(args.str("projectId")), offset = args.optLong("offset") ?: 0, limit = args.optInt("limit") ?: 50)
                            args.optStr("projectKey") != null -> {
                                val proj = api.getProjectByKey(args.str("projectKey")) ?: error("Project not found")
                                api.getSpecsByProject(proj.id, offset = args.optLong("offset") ?: 0, limit = args.optInt("limit") ?: 50)
                            }
                            args.optStr("programId") != null -> api.getSpecsByProgram(Uuid.parse(args.str("programId")), offset = args.optLong("offset") ?: 0, limit = args.optInt("limit") ?: 50)
                            args.optStr("ownerId") != null -> api.getSpecsByOwner(Uuid.parse(args.str("ownerId")), offset = args.optLong("offset") ?: 0, limit = args.optInt("limit") ?: 50)
                            else -> error("Provide projectId, projectKey, programId, or ownerId")
                        }
                        json.encodeToString(JsonArray.serializer(), JsonArray(specs.map { it.toSpecJson() }))
                    }
                    "children" -> {
                        val parentId = args.optStr("parentSpecId") ?: args.optStr("id") ?: error("Provide parentSpecId or id")
                        val children = api.getSpecChildren(Uuid.parse(parentId), offset = args.optLong("offset") ?: 0, limit = args.optInt("limit") ?: 50)
                        json.encodeToString(JsonArray.serializer(), JsonArray(children.map { it.toSpecJson() }))
                    }
                    "create" -> {
                        val pid = args.optStr("projectId")?.let { Uuid.parse(it) }
                            ?: args.optStr("projectKey")?.let { api.getProjectByKey(it)?.id ?: error("Project not found") }
                            ?: error("Provide projectId or projectKey")
                        val s = api.createSpec(CreateWorkOpsSpecInput(
                            metadataId = args.optStr("metadataId")?.let { Uuid.parse(it) },
                            name = args.optStr("name"),
                            projectId = pid,
                            programId = args.optStr("programId")?.let { Uuid.parse(it) },
                            parentSpecId = args.optStr("parentSpecId")?.let { Uuid.parse(it) },
                            workflowId = args.optStr("workflowId")?.let { Uuid.parse(it) },
                            sortOrder = args.optInt("sortOrder"),
                            gitRepositoryId = args.optStr("gitRepositoryId")?.let { Uuid.parse(it) },
                            gitPath = args.optStr("gitPath"),
                        ))
                        json.encodeToString(JsonElement.serializer(), s.toSpecJson()) + SPEC_STATUS_REMINDER
                    }
                    "update" -> {
                        val spec = args.optStr("id")?.let { api.getSpec(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getSpecByKey(it) }
                            ?: error("Provide id or key")
                        if (spec == null) error("Spec not found")
                        val version = args.optLong("expectedVersion") ?: spec.version
                        val s = api.updateSpec(spec.id, UpdateWorkOpsSpecInput(
                            ownerProfileId = args.optStr("ownerProfileId")?.let { Uuid.parse(it) },
                            projectId = args.optStr("projectId")?.let { Uuid.parse(it) },
                            programId = args.optStr("programId")?.let { Uuid.parse(it) },
                            parentSpecId = args.optStr("parentSpecId")?.let { Uuid.parse(it) },
                            sortOrder = args.optInt("sortOrder"),
                            gitRepositoryId = args.optStr("gitRepositoryId")?.let { Uuid.parse(it) },
                            gitPath = args.optStr("gitPath"),
                            expectedVersion = version,
                        ))
                        json.encodeToString(JsonElement.serializer(), s.toSpecJson()) + SPEC_STATUS_REMINDER
                    }
                    "transition" -> {
                        val spec = args.optStr("id")?.let { api.getSpec(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getSpecByKey(it) }
                            ?: error("Provide id or key")
                        if (spec == null) error("Spec not found")
                        val version = args.optLong("expectedVersion") ?: spec.version
                        val s = api.transitionSpec(
                            spec.id, Uuid.parse(args.str("transitionId")), version,
                            args.optStr("resolutionId")?.let { Uuid.parse(it) },
                        )
                        json.encodeToString(JsonElement.serializer(), s.toSpecJson())
                    }
                    "delete" -> {
                        val spec = args.optStr("id")?.let { api.getSpec(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getSpecByKey(it) }
                            ?: error("Provide id or key")
                        if (spec == null) error("Spec not found")
                        val version = args.optLong("expectedVersion") ?: spec.version
                        api.softDeleteSpec(spec.id, version)
                        """{"deleted": true, "key": "${spec.key}"}"""
                    }
                    "restore" -> {
                        val spec = args.optStr("id")?.let { api.getSpec(Uuid.parse(it)) }
                            ?: args.optStr("key")?.let { api.getSpecByKey(it) }
                            ?: error("Provide id or key")
                        if (spec == null) error("Spec not found")
                        val version = args.optLong("expectedVersion") ?: spec.version
                        val s = api.restoreSpec(spec.id, version)
                        json.encodeToString(JsonElement.serializer(), s.toSpecJson())
                    }
                    "contexts" -> {
                        val specId = args.resolveSpecId(api)
                        val contexts = api.getSpecContexts(specId)
                        json.encodeToString(JsonArray.serializer(), JsonArray(contexts.map { c ->
                            buildJsonObject {
                                put("id", c.id.toString())
                                put("contextType", c.contextType.name)
                                put("targetId", c.targetId)
                                put("label", c.label)
                                put("addedByProfileId", c.addedByProfileId.toString())
                                put("createdAt", c.createdAt.toString())
                            }
                        }))
                    }
                    "add_context" -> {
                        val specId = args.resolveSpecId(api)
                        val ctx = api.addSpecContext(specId, CreateWorkOpsSpecContextInput(
                            contextType = WorkOpsSpecContextType.valueOf(args.str("contextType").uppercase()),
                            targetId = args.str("targetId"),
                            label = args.optStr("label"),
                        ))
                        buildJsonObject {
                            put("id", ctx.id.toString()); put("contextType", ctx.contextType.name)
                            put("targetId", ctx.targetId); put("label", ctx.label)
                        }.toString()
                    }
                    "remove_context" -> {
                        val specId = args.resolveSpecId(api)
                        api.removeSpecContext(specId, Uuid.parse(args.str("contextId")))
                        """{"removed": true}"""
                    }
                    "comments" -> {
                        val specId = args.resolveSpecId(api)
                        val comments = api.getSpecComments(specId)
                        json.encodeToString(JsonArray.serializer(), JsonArray(comments.map { c ->
                            buildJsonObject {
                                put("id", c.id); put("profileId", c.profileId.toString())
                                put("content", c.content); put("created", c.created.toString())
                                put("status", c.status.name)
                            }
                        }))
                    }
                    "add_comment" -> {
                        val specId = args.resolveSpecId(api)
                        val c = api.addSpecComment(specId, WorkOpsSpecCommentInput(content = args.str("content")))
                        buildJsonObject { put("id", c.id); put("content", c.content); put("created", c.created.toString()) }.toString()
                    }
                    "generate_tasks" -> {
                        val specId = args.resolveSpecId(api)
                        val result = api.generateSpecTasks(
                            specId,
                            args.optInt("metadataVersion") ?: error("Provide metadataVersion"),
                            WorkOpsGenerationSource.valueOf(args.str("source").uppercase()),
                            args.optStr("agentSessionId")?.let { Uuid.parse(it) },
                        )
                        buildJsonObject {
                            put("id", result.id.toString())
                            put("specId", result.specId.toString())
                            put("source", result.source.name)
                            put("generatedTaskIds", JsonArray(result.generatedTaskIds.map { JsonPrimitive(it.toString()) }))
                            put("createdAt", result.createdAt.toString())
                        }.toString()
                    }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }

        server.addTool(
            Tool(
                name = "workops_spec_history",
                description = "Get the audit history for a spec showing all field changes over time. Provide id or key.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string"); put("description", "Spec UUID (provide id or key)") })
                        put("key", buildJsonObject { put("type", "string"); put("description", "Spec key (provide id or key)") })
                        put("limit", buildJsonObject { put("type", "integer"); put("description", "Max entries to return (default 50)") })
                    },
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val specId = args.resolveSpecId(api)
                val entries = api.getSpecHistory(specId, limit = args.optInt("limit") ?: 50)
                val arr = JsonArray(entries.map { e ->
                    buildJsonObject {
                        put("id", e.id.toString())
                        put("changedAt", e.changedAt.toString())
                        put("changedBy", (e.changedByProfileId ?: e.changedByPrincipalId).toString())
                        put("changes", JsonArray(e.changes.map { c ->
                            buildJsonObject {
                                put("field", c.fieldName)
                                put("from", c.fromValue ?: JsonNull)
                                put("to", c.toValue ?: JsonNull)
                            }
                        }))
                    }
                })
                CallToolResult(content = listOf(TextContent(json.encodeToString(JsonArray.serializer(), arr))))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Requirements ───────────────────────────────────────────

    private fun registerRequirementTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_requirement",
                description = """
                    Manage workops Requirements — the individual items inside a Spec (or pinned to a Task). Each Requirement automatically gets:
                    1. Its own backing metadata document (auto-created — pass `name` to title it, or supply your own `metadataId`).
                    2. A linked Task that mirrors its status. The linked Task is created the moment the Requirement is created.

                    Later, calling workops_spec action=generate_tasks against the parent Spec will create ADDITIONAL Tasks from each Requirement's document content. The linked Task and generated Tasks are separate — the linked Task tracks the Requirement itself; generated Tasks decompose its content into work items.

                    CREATE FLOW: pass parentType=SPEC (or TASK) and parentId=<spec or task UUID>. Pass `name` to title the new document. Everything else (workflow, priority, status) defaults to the parent project's settings. The create response returns both `metadataId` (the requirement document) and `taskId` (the linked Task) — you need both for the next step.

                    ⚠️ THE LINKED TASK STARTS EMPTY — POPULATE IT. The linked Task's summary + description are copied from the requirement's document a SINGLE time, at create. Because auto-create starts that document empty, the freshly-created linked Task is empty too, and NOTHING re-syncs it when you later edit the document. So after creating a Requirement you must do BOTH writes, in order:
                    1. Write the requirement body to its document: content_metadata action=set_content id=<metadataId> markdownContent=…
                    2. Push that same body to the linked Task: workops_task action=update id=<taskId> descriptionMarkdown=<the body as markdown> (set summary too if the title matters).
                    Skipping step 2 leaves the linked Task's page blank even though the requirement document is full.

                    ⚠️ MANDATORY STATUS TRACKING: Agents MUST keep requirement statuses up-to-date. Requirements have a linked Task whose status mirrors the requirement. When you begin work on a requirement's linked task, transition it to IN_PROGRESS. When the requirement is satisfied, transition its linked task to DONE. Stale requirement statuses are treated as data integrity failures.

                    Use `move` to re-parent a Requirement between Spec/Task. Use `list_by_spec` / `list_by_task` to enumerate.
                """.trimIndent(),
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("get", "list_by_spec", "list_by_task", "create", "update", "delete", "restore", "move").map { JsonPrimitive(it) })) })
                        put("id", buildJsonObject { put("type", "string"); put("description", "Requirement UUID") })
                        put("key", buildJsonObject { put("type", "string"); put("description", "Requirement key (e.g. REQ-7)") })
                        put("specId", buildJsonObject { put("type", "string"); put("description", "Spec UUID (for list_by_spec)") })
                        put("taskId", buildJsonObject { put("type", "string"); put("description", "Task UUID (for list_by_task)") })
                        put("metadataId", buildJsonObject { put("type", "string"); put("description", "Optional. Existing metadata to link. If omitted on create, a new bosca/v-document is auto-created titled `name` (or the minted REQ key).") })
                        put("name", buildJsonObject { put("type", "string"); put("description", "Title for the auto-created document on create. Recommended.") })
                        put("parentType", buildJsonObject {
                            put("type", "string")
                            put("description", "Parent kind: SPEC (most common) or TASK")
                            put("enum", JsonArray(listOf("SPEC", "TASK").map { JsonPrimitive(it) }))
                        })
                        put("parentId", buildJsonObject { put("type", "string"); put("description", "Parent UUID (Spec or Task UUID matching parentType)") })
                        put("priorityId", buildJsonObject { put("type", "string"); put("description", "Priority UUID (from workops_reference_data action=priorities)") })
                        put("assigneeProfileId", buildJsonObject { put("type", "string"); put("description", "Profile UUID to assign to") })
                        put("clearAssignee", buildJsonObject { put("type", "boolean"); put("description", "Update only: clear assignee") })
                        put("workflowId", buildJsonObject { put("type", "string"); put("description", "Workflow UUID. Defaults to project workflow if omitted.") })
                        put("sortOrder", buildJsonObject { put("type", "integer"); put("description", "Order within parent") })
                        put("expectedVersion", buildJsonObject { put("type", "integer"); put("description", "Optimistic-lock version (required for update/delete/restore/move)") })
                        put("offset", buildJsonObject { put("type", "integer") })
                        put("limit", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "get" -> {
                        val byId = args.optStr("id")?.let { api.getRequirement(Uuid.parse(it)) }
                        if (byId != null) {
                            json.encodeToString(JsonElement.serializer(), buildJsonObject {
                                put("id", byId.id.toString()); put("key", byId.key)
                                put("metadataId", byId.metadataId.toString())
                                put("parentType", byId.parentType.name); put("parentId", byId.parentId.toString())
                                put("status", byId.status.name); put("statusCategory", byId.status.category.name)
                                put("priority", byId.priority.name)
                                put("assigneeProfileId", byId.assigneeProfileId?.toString())
                                put("taskId", byId.taskId?.toString())
                                put("sortOrder", byId.sortOrder); put("version", byId.version)
                                put("createdAt", byId.createdAt.toString()); put("modifiedAt", byId.modifiedAt.toString())
                            })
                        } else {
                            val byKey = args.optStr("key")?.let { api.getRequirementByKey(it) }
                                ?: error("Provide id or key")
                            json.encodeToString(JsonElement.serializer(), buildJsonObject {
                                put("id", byKey.id.toString()); put("key", byKey.key)
                                put("metadataId", byKey.metadataId.toString())
                                put("parentType", byKey.parentType.name); put("parentId", byKey.parentId.toString())
                                put("status", byKey.status.name); put("statusCategory", byKey.status.category.name)
                                put("priority", byKey.priority.name)
                                put("assigneeProfileId", byKey.assigneeProfileId?.toString())
                                put("taskId", byKey.taskId?.toString())
                                put("sortOrder", byKey.sortOrder); put("version", byKey.version)
                                put("createdAt", byKey.createdAt.toString()); put("modifiedAt", byKey.modifiedAt.toString())
                            })
                        }
                    }
                    "list_by_spec" -> {
                        val reqs = api.getRequirementsBySpec(Uuid.parse(args.str("specId")), offset = args.optLong("offset") ?: 0, limit = args.optInt("limit") ?: 50)
                        json.encodeToString(JsonArray.serializer(), JsonArray(reqs.map { r ->
                            buildJsonObject {
                                put("id", r.id.toString()); put("key", r.key)
                                put("metadataId", r.metadataId.toString())
                                put("status", r.status.name); put("statusCategory", r.status.category.name)
                                put("priority", r.priority.name)
                                put("assigneeProfileId", r.assigneeProfileId?.toString())
                                put("taskId", r.taskId?.toString())
                                put("sortOrder", r.sortOrder); put("version", r.version)
                            }
                        }))
                    }
                    "list_by_task" -> {
                        val reqs = api.getRequirementsByTask(Uuid.parse(args.str("taskId")), offset = args.optLong("offset") ?: 0, limit = args.optInt("limit") ?: 50)
                        json.encodeToString(JsonArray.serializer(), JsonArray(reqs.map { r ->
                            buildJsonObject {
                                put("id", r.id.toString()); put("key", r.key)
                                put("metadataId", r.metadataId.toString())
                                put("status", r.status.name); put("statusCategory", r.status.category.name)
                                put("priority", r.priority.name)
                                put("assigneeProfileId", r.assigneeProfileId?.toString())
                                put("taskId", r.taskId?.toString())
                                put("sortOrder", r.sortOrder); put("version", r.version)
                            }
                        }))
                    }
                    "create" -> {
                        val r = api.createRequirement(CreateWorkOpsRequirementInput(
                            metadataId = args.optStr("metadataId")?.let { Uuid.parse(it) },
                            name = args.optStr("name"),
                            parentType = WorkOpsRequirementParent.valueOf(args.str("parentType").uppercase()),
                            parentId = Uuid.parse(args.str("parentId")),
                            workflowId = args.optStr("workflowId")?.let { Uuid.parse(it) },
                            priorityId = args.optStr("priorityId")?.let { Uuid.parse(it) },
                            assigneeProfileId = args.optStr("assigneeProfileId")?.let { Uuid.parse(it) },
                            sortOrder = args.optInt("sortOrder"),
                        ))
                        buildJsonObject {
                            put("id", r.id.toString()); put("key", r.key)
                            put("metadataId", r.metadataId.toString())
                            put("status", r.status.name); put("priority", r.priority.name)
                            put("taskId", r.taskId?.toString())
                            put("version", r.version)
                        }.toString() + REQUIREMENT_STATUS_REMINDER
                    }
                    "update" -> {
                        val reqId = Uuid.parse(args.str("id"))
                        val r = api.updateRequirement(reqId, UpdateWorkOpsRequirementInput(
                            priorityId = args.optStr("priorityId")?.let { Uuid.parse(it) },
                            assigneeProfileId = args.optStr("assigneeProfileId")?.let { Uuid.parse(it) },
                            clearAssignee = args.optBool("clearAssignee"),
                            sortOrder = args.optInt("sortOrder"),
                            expectedVersion = args.long("expectedVersion"),
                        ))
                        buildJsonObject {
                            put("id", r.id.toString()); put("key", r.key)
                            put("metadataId", r.metadataId.toString())
                            put("status", r.status.name); put("priority", r.priority.name)
                            put("taskId", r.taskId?.toString())
                            put("version", r.version)
                        }.toString() + REQUIREMENT_STATUS_REMINDER
                    }
                    "delete" -> {
                        val reqId = Uuid.parse(args.str("id"))
                        api.softDeleteRequirement(reqId, args.long("expectedVersion"))
                        """{"deleted": true}"""
                    }
                    "restore" -> {
                        val reqId = Uuid.parse(args.str("id"))
                        api.restoreRequirement(reqId, args.long("expectedVersion"))
                        """{"restored": true}"""
                    }
                    "move" -> {
                        val reqId = Uuid.parse(args.str("id"))
                        val r = api.moveRequirement(
                            reqId,
                            WorkOpsRequirementParent.valueOf(args.str("parentType").uppercase()),
                            Uuid.parse(args.str("parentId")),
                            args.long("expectedVersion"),
                        )
                        buildJsonObject {
                            put("id", r.id.toString()); put("key", r.key)
                            put("parentType", r.parentType.name); put("parentId", r.parentId.toString())
                            put("version", r.version)
                        }.toString()
                    }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Boards ──────────────────────────────────────────────────

    private fun registerBoardTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_board",
                description = "Manage workops boards. Actions: list, get, create, delete.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "get", "create", "delete").map { JsonPrimitive(it) })) })
                        put("id", buildJsonObject { put("type", "string") })
                        put("projectId", buildJsonObject { put("type", "string") })
                        put("name", buildJsonObject { put("type", "string") })
                        put("type", buildJsonObject { put("type", "string"); put("description", "KANBAN or SCRUM") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> json.encodeToString(JsonArray.serializer(), JsonArray(api.getBoardsByProject(Uuid.parse(args.str("projectId"))).map { it.toJson() }))
                    "get" -> {
                        val b = api.getBoard(Uuid.parse(args.str("id"))) ?: error("Board not found")
                        json.encodeToString(JsonElement.serializer(), b.toJson())
                    }
                    "create" -> {
                        val b = api.createBoard(CreateWorkOpsBoardInput(
                            projectId = Uuid.parse(args.str("projectId")),
                            name = args.str("name"),
                            type = WorkOpsBoardType.valueOf(args.str("type").uppercase()),
                        ))
                        json.encodeToString(JsonElement.serializer(), b.toJson())
                    }
                    "delete" -> { api.deleteBoard(Uuid.parse(args.str("id"))); """{"deleted": true}""" }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Sprints ─────────────────────────────────────────────────

    private fun registerSprintTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_sprint",
                description = "Manage workops sprints. Actions: list, get, create, start, close.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "get", "create", "start", "close").map { JsonPrimitive(it) })) })
                        put("id", buildJsonObject { put("type", "string") })
                        put("boardId", buildJsonObject { put("type", "string") })
                        put("name", buildJsonObject { put("type", "string") })
                        put("goal", buildJsonObject { put("type", "string") })
                        put("expectedVersion", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> json.encodeToString(JsonArray.serializer(), JsonArray(api.getSprintsByBoard(Uuid.parse(args.str("boardId"))).map { it.toJson() }))
                    "get" -> {
                        val s = api.getSprint(Uuid.parse(args.str("id"))) ?: error("Sprint not found")
                        json.encodeToString(JsonElement.serializer(), s.toJson())
                    }
                    "create" -> {
                        val s = api.createSprint(CreateWorkOpsSprintInput(
                            boardId = Uuid.parse(args.str("boardId")),
                            name = args.str("name"),
                            goal = args.optStr("goal"),
                        ))
                        json.encodeToString(JsonElement.serializer(), s.toJson())
                    }
                    "start" -> {
                        val s = api.startSprint(StartWorkOpsSprintInput(
                            sprintId = Uuid.parse(args.str("id")),
                            expectedVersion = args.long("expectedVersion"),
                        ))
                        json.encodeToString(JsonElement.serializer(), s.toJson())
                    }
                    "close" -> {
                        val s = api.closeSprint(CloseWorkOpsSprintInput(
                            sprintId = Uuid.parse(args.str("id")),
                            expectedVersion = args.long("expectedVersion"),
                        ))
                        json.encodeToString(JsonElement.serializer(), s.toJson())
                    }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Worklogs ────────────────────────────────────────────────

    private fun registerWorklogTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_worklog",
                description = "Log and query work time on tasks. Actions: list, log, delete.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "log", "delete").map { JsonPrimitive(it) })) })
                        put("taskId", buildJsonObject { put("type", "string"); put("description", "Task UUID") })
                        put("taskKey", buildJsonObject { put("type", "string"); put("description", "Task key (alternative to taskId)") })
                        put("id", buildJsonObject { put("type", "string"); put("description", "Worklog UUID (for delete)") })
                        put("timeSpent", buildJsonObject { put("type", "string"); put("description", "Duration shorthand (e.g. 1h 30m, 2d)") })
                        put("comment", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> {
                        val tid = args.resolveTaskId(api)
                        json.encodeToString(JsonArray.serializer(), JsonArray(api.getWorklogs(tid).map { it.toJson() }))
                    }
                    "log" -> {
                        val tid = args.resolveTaskId(api)
                        val wl = api.logWork(tid, WorkOpsWorkLogInput(
                            timeSpent = args.str("timeSpent"),
                            startedAt = ZonedDateTime.now(),
                            comment = args.optStr("comment"),
                            visibility = WorklogVisibility.ALL,
                        ))
                        json.encodeToString(JsonElement.serializer(), wl.toJson())
                    }
                    "delete" -> { api.deleteWorklog(Uuid.parse(args.str("id"))); """{"deleted": true}""" }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Comments ────────────────────────────────────────────────

    private fun registerCommentTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_comment",
                description = "Add and list comments on tasks. Actions: list, add, delete.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "add", "delete").map { JsonPrimitive(it) })) })
                        put("taskId", buildJsonObject { put("type", "string") })
                        put("taskKey", buildJsonObject { put("type", "string") })
                        put("content", buildJsonObject { put("type", "string"); put("description", "Comment text (for add)") })
                        put("commentId", buildJsonObject { put("type", "integer"); put("description", "Comment ID (for delete)") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> {
                        val tid = args.resolveTaskId(api)
                        val comments = api.getTaskComments(tid)
                        json.encodeToString(JsonArray.serializer(), JsonArray(comments.map {
                            buildJsonObject {
                                put("id", it.id)
                                put("profileId", it.profileId.toString())
                                put("content", it.content)
                                put("created", it.created.toString())
                                put("status", it.status.name)
                            }
                        }))
                    }
                    "add" -> {
                        val tid = args.resolveTaskId(api)
                        val c = api.addTaskComment(tid, WorkOpsTaskCommentInput(content = args.str("content")))
                        buildJsonObject { put("id", c.id); put("content", c.content); put("created", c.created.toString()) }.toString()
                    }
                    "delete" -> {
                        val tid = args.resolveTaskId(api)
                        api.deleteTaskComment(tid, args.long("commentId"))
                        """{"deleted": true}"""
                    }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Links ───────────────────────────────────────────────────

    private fun registerLinkTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_link",
                description = "Link and unlink tasks. Actions: list, link, unlink, link_types.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "link", "unlink", "link_types").map { JsonPrimitive(it) })) })
                        put("taskId", buildJsonObject { put("type", "string"); put("description", "Task UUID (for list)") })
                        put("taskKey", buildJsonObject { put("type", "string") })
                        put("linkTypeId", buildJsonObject { put("type", "string") })
                        put("sourceTaskId", buildJsonObject { put("type", "string") })
                        put("targetTaskId", buildJsonObject { put("type", "string") })
                        put("linkId", buildJsonObject { put("type", "string"); put("description", "Link UUID (for unlink)") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> {
                        val tid = args.resolveTaskId(api)
                        val links = api.getTaskLinks(tid)
                        json.encodeToString(JsonArray.serializer(), JsonArray(links.map {
                            buildJsonObject {
                                put("id", it.id.toString())
                                put("linkType", it.linkType.name)
                                put("category", it.linkType.category.name)
                                put("sourceKey", it.sourceTask?.key)
                                put("targetKey", it.targetTask?.key)
                            }
                        }))
                    }
                    "link" -> {
                        val l = api.linkTasks(WorkOpsTaskLinkInput(
                            linkTypeId = Uuid.parse(args.str("linkTypeId")),
                            sourceTaskId = Uuid.parse(args.str("sourceTaskId")),
                            targetTaskId = Uuid.parse(args.str("targetTaskId")),
                        ))
                        buildJsonObject { put("id", l.id.toString()); put("sourceKey", l.sourceTask?.key); put("targetKey", l.targetTask?.key) }.toString()
                    }
                    "unlink" -> { api.unlinkTasks(Uuid.parse(args.str("linkId"))); """{"unlinked": true}""" }
                    "link_types" -> {
                        val types = api.getLinkTypes()
                        json.encodeToString(JsonArray.serializer(), JsonArray(types.map {
                            buildJsonObject {
                                put("id", it.id.toString()); put("name", it.name)
                                put("inwardLabel", it.inwardLabel); put("outwardLabel", it.outwardLabel)
                                put("category", it.category.name)
                            }
                        }))
                    }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Search / BQL ────────────────────────────────────────────

    private fun registerSearchTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_search",
                description = "Search tasks using BQL (Bosca Query Language). Example: 'project = BOS AND status != Done'",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("query", buildJsonObject { put("type", "string"); put("description", "BQL query string") })
                        put("limit", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("query"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = api.searchTasks(args.str("query"), limit = args.optInt("limit") ?: 50)
                val tasks = result.rows
                json.encodeToString(JsonArray.serializer(), JsonArray(tasks.map { it.toJson() }))
                    .let { CallToolResult(content = listOf(TextContent(it))) }
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Reference Data ──────────────────────────────────────────

    private fun registerRefDataTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_reference_data",
                description = "Fetch workops reference data. Actions: statuses, task_types, priorities, resolutions, workflows, labels_global.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("statuses", "task_types", "priorities", "resolutions", "workflows", "labels_global").map { JsonPrimitive(it) })) })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "statuses" -> json.encodeToString(JsonArray.serializer(), JsonArray(api.getStatuses().map {
                        buildJsonObject { put("id", it.id.toString()); put("name", it.name); put("category", it.category.name); put("colorHex", it.colorHex) }
                    }))
                    "task_types" -> json.encodeToString(JsonArray.serializer(), JsonArray(api.getTaskTypes().map {
                        buildJsonObject { put("id", it.id.toString()); put("name", it.name); put("hierarchyLevel", it.hierarchyLevel.name); put("colorHex", it.colorHex) }
                    }))
                    "priorities" -> json.encodeToString(JsonArray.serializer(), JsonArray(api.getPriorities().map {
                        buildJsonObject { put("id", it.id.toString()); put("name", it.name); put("displayOrder", it.displayOrder); put("colorHex", it.colorHex) }
                    }))
                    "resolutions" -> json.encodeToString(JsonArray.serializer(), JsonArray(api.getResolutions().map {
                        buildJsonObject { put("id", it.id.toString()); put("name", it.name); put("displayOrder", it.displayOrder) }
                    }))
                    "workflows" -> json.encodeToString(JsonArray.serializer(), JsonArray(api.getWorkflows().map {
                        buildJsonObject { put("id", it.id.toString()); put("name", it.name); put("stateCount", it.states.size); put("transitionCount", it.transitions.size) }
                    }))
                    "labels_global" -> json.encodeToString(JsonArray.serializer(), JsonArray(api.getGlobalLabels().map {
                        buildJsonObject { put("id", it.id.toString()); put("name", it.name); put("colorHex", it.colorHex) }
                    }))
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Notifications ───────────────────────────────────────────

    private fun registerNotificationTools(server: Server, api: WorkOpsApi) {
        server.addTool(
            Tool(
                name = "workops_notification",
                description = "Manage workops notifications. Actions: list, unread_count, mark_read, mark_all_read, watch, unwatch.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "unread_count", "mark_read", "mark_all_read", "watch", "unwatch").map { JsonPrimitive(it) })) })
                        put("id", buildJsonObject { put("type", "string"); put("description", "Notification UUID (for mark_read)") })
                        put("taskId", buildJsonObject { put("type", "string"); put("description", "Task UUID (for watch/unwatch)") })
                    },
                    required = listOf("action"),
                ),
            ),
        ) { request ->
            val args = request.arguments ?: error("Missing arguments")
            try {
                val result = when (args.str("action")) {
                    "list" -> json.encodeToString(JsonArray.serializer(), JsonArray(api.getNotifications().map {
                        buildJsonObject {
                            put("id", it.id.toString()); put("event", it.event); put("body", it.body)
                            put("taskId", it.taskId?.toString()); put("readAt", it.readAt?.toString())
                            put("createdAt", it.createdAt.toString())
                        }
                    }))
                    "unread_count" -> """{"count": ${api.getUnreadCount()}}"""
                    "mark_read" -> { api.markNotificationRead(Uuid.parse(args.str("id"))); """{"marked": true}""" }
                    "mark_all_read" -> { api.markAllNotificationsRead(); """{"marked": true}""" }
                    "watch" -> { api.watchTask(Uuid.parse(args.str("taskId"))); """{"watching": true}""" }
                    "unwatch" -> { api.unwatchTask(Uuid.parse(args.str("taskId"))); """{"watching": false}""" }
                    else -> error("Unknown action")
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    private val SPEC_INSTRUCTIONS = """
# WorkOps Spec System — Plan-Mode Persistence

Specs are how an AI assistant (or a human) persists a plan/RFC/design/requirements doc in workops so that it can be tracked through a workflow and decomposed into actionable Tasks. Think of a Spec as the durable, server-side version of Claude Code's "plan mode."

## The mental model

```
Portfolio → Program → Project
                        └── Spec (the plan; status; owner; history; metadata document)
                              ├── Requirement[]  (each has its own document AND an auto-linked Task)
                              ├── SpecContext[]  (curated references: git, sessions, profiles, URLs…)
                              └── generate_tasks → Task[]  (decomposes Requirements into ADDITIONAL tasks)
```

- A **Spec** is metadata + workflow semantics. The metadata is the actual document body of the plan.
- A **Requirement** is one line-item in the plan. Creating it auto-creates: (a) its own document, and (b) a Task linked 1:1 that mirrors its status. The linked Task is the unit you "do work" against. ⚠️ The linked Task's body is copied from the requirement document **once, at create time** (when that document is still empty) and is never re-synced — so after writing the requirement body you must also push it to the linked Task. See "Requirements: write the body twice" below.
- **generate_tasks** snapshots each Requirement's document and creates *additional* Tasks from each. Use this for further decomposition once the plan is detailed enough to break down.

## Prerequisites for creating a Spec

Just one: a **Project** must exist. Pass `projectId` or `projectKey` to `workops_spec` action=create.

You do NOT need to create metadata first. Pass `name` and a `bosca/v-document` is created and linked automatically. (Pass `metadataId` only if you already have a document you want to attach.)

## Worked example — full plan-mode lifecycle

### Phase 1 — capture the plan

```
# 1. Ensure a project exists
workops_project action=list                          # find one, or create with action=create

# 2. Create the spec (the plan). Auto-creates the backing document.
workops_spec action=create
    projectKey=BOS
    name="Migrate auth middleware off legacy session store"
  → returns { id, key: "SPEC-12", metadataId, status, … }

# 3. Write the plan body into the document (see "Editing the document body" below).
#    Easiest path: send markdown — it is parsed into a TipTap document server-side.
content_metadata action=set_content id=<metadataId> markdownContent="# Plan\n\n..." documentTitle="Migrate auth middleware"

# 4. Decompose into Requirements (one per plan item).
workops_requirement action=create parentType=SPEC parentId=<specId> name="Audit current callers"
#   → returns { id, key: "REQ-3", metadataId, taskId, … }
#   For EACH requirement, write the body twice (see "Requirements: write the body twice"):
content_metadata  action=set_content id=<req metadataId> markdownContent="## Audit current callers\n- …"
workops_task      action=update      id=<req taskId>     descriptionMarkdown="## Audit current callers\n- …"
# (repeat create + both writes for "Cut over /login", "Decommission old store", …)
# → each yields a Requirement AND a linked Task you can work against later.

# 5. Curate context (research the agent did, source material, related code, etc.)
workops_spec action=add_context id=<specId> contextType=AI_SESSION targetId=<sessionUuid>
workops_spec action=add_context id=<specId> contextType=GIT_RESOURCE targetId=<repoUuid> label="auth-service repo"
workops_spec action=add_context id=<specId> contextType=METADATA targetId=<docUuid>  label="prior RFC"
```

### Phase 2 — read the plan back later

```
# Inspect the spec
workops_spec action=get id=<specId>                            # status, project, metadataId, version
workops_spec_history id=<specId>                               # who changed what when

# Read the plan body and the requirement bodies
content_metadata action=get_document id=<metadataId>           # returns TipTap JSON
workops_requirement action=list_by_spec specId=<specId>        # all requirements + their metadataIds
# then content_metadata action=get_document for each requirement's metadataId

# Inspect curated context
workops_spec action=contexts id=<specId>                       # all linked references
```

### Phase 3 — execute the plan

```
# Fan the plan out into trackable Tasks (additional to the linked ones)
workops_spec action=generate_tasks id=<specId>
    metadataVersion=1 source=CLAUDE_CODE agentSessionId=<sessionUuid>

# Work each linked Task: assign, transition, log work, comment
workops_task action=update id=<taskId> assigneeProfileId=<me>
workops_task_transitions id=<taskId>                           # discover available transitionId
workops_task action=transition id=<taskId> transitionId=<uuid>
workops_worklog action=log taskId=<taskId> timeSpent="2h" comment="Cutover landed"

# Roll the spec itself forward when the plan is done
workops_reference_data action=workflows                        # find a transition out of the current status
workops_spec action=transition id=<specId> transitionId=<uuid>
```

## Editing the document body

Spec and Requirement bodies are TipTap (ProseMirror) documents stored as JSON. You have two ways to edit them, both via `content_metadata action=set_content` against the spec/requirement's `metadataId`:

### Path A — markdown (recommended)

Pass `markdownContent` (a string) and optionally `documentTitle`. The server parses the markdown via CommonMark + GFM extensions (tables, strikethrough, task lists, autolinks) and stores a well-formed TipTap document. This is the easiest path and supports everything you need for plan-mode writing.

```
content_metadata action=set_content id=<metadataId>
    documentTitle="Migrate auth middleware"
    markdownContent="# Goals\n- Cut over /login\n- Decommission legacy session store\n\n## Risks\n1. Active sessions during cutover\n2. Metrics regression\n\n## Tasks\n- [x] Audit callers\n- [ ] Land cutover behind flag\n- [ ] Roll forward\n"
```

Supported: headings, paragraphs, ordered/bulleted/task lists, links, bold/italic/code, blockquotes, fenced code blocks (with `language`), tables, strikethrough, horizontal rules, autolinks. Send the markdown verbatim — escaping is handled for you by the JSON transport.

### Path B — raw TipTap JSON

If you need precise control (custom nodes like `mention` / `container`, embedded images by metadataId), pass `jsonContent` containing a complete TipTap document.

**Hard rule:** `textContent` and `filePath` are NEVER acceptable for spec or requirement bodies (or for any artifact this CLI manages as a document — plans, RFCs, design notes, etc.). They store raw bytes that do not render as a structured document. Always use `markdownContent` (Path A, preferred) or `jsonContent` (Path B). If you only have a markdown string, that is Path A — pass it as `markdownContent`, not `textContent`. There is no shortcut around this.

### Wrapper

```
{ "document": { "type": "doc", "content": [ ... blocks ... ] } }
```

Every node has the shape `{ "type": "...", "attrs": {...}, "content": [...], "marks": [...] }`. Empty `attrs`, `content`, and `marks` are still required.

### Block node types

- `doc` — root.
- `paragraph`
- `heading` — `attrs.level: 1..6`
- `blockquote`
- `bulletList`, `orderedList` (`attrs.start: Int?`), `listItem`
- `taskList`, `taskItem` (`attrs.checked: Boolean`)
- `codeBlock` (`attrs.language: String?`)
- `hardBreak`, `horizontalRule`
- `image` — `attrs: { src, alt?, title?, metadataId? }`. Use `metadataId` to point at a Bosca-hosted image rather than a raw URL.
- `table`, `tableRow`, `tableCell`
- `html` — raw HTML string in `attrs.html` (escape hatch; avoid when a structured node fits)
- `container` — embed referenced entities (see "Linking entities into the body")
- `mention` — inline `@`-mention; `attrs: { id, label, entityType }` where `entityType` is `metadata` | `collection` | `profile`

### Mark types (apply to `text` nodes)

`bold`, `italic`, `underline`, `strike` (where supported), `code`, `superscript`, `subscript`, `hidden`, and `link` (`attrs: { href, target?, rel? }`).

### Minimal example

```
{
  "document": {
    "type": "doc", "attrs": {}, "marks": [],
    "content": [
      { "type": "heading", "attrs": { "level": 1 }, "marks": [],
        "content": [{ "type": "text", "attrs": {}, "marks": [], "text": "Plan: cut over auth" }] },
      { "type": "paragraph", "attrs": {}, "marks": [],
        "content": [
          { "type": "text", "attrs": {}, "marks": [], "text": "We will replace " },
          { "type": "text", "attrs": {},
            "marks": [{ "type": "link", "attrs": { "href": "https://internal/wiki/sessions" } }],
            "text": "the legacy session store" }
        ] },
      { "type": "bulletList", "attrs": {}, "marks": [], "content": [
        { "type": "listItem", "attrs": {}, "marks": [], "content": [
          { "type": "paragraph", "attrs": {}, "marks": [],
            "content": [{ "type": "text", "attrs": {}, "marks": [], "text": "Audit callers" }] }
        ]}
      ]}
    ]
  }
}
```

## Requirements: write the body twice

A Requirement owns TWO independent stores, and they are only joined once:

- its **document** (the `metadataId` returned on create), and
- its **linked Task** (the `taskId` returned on create), whose `summary` + `description` are copied from the document **at the instant the Requirement is created**.

Because auto-create starts the document empty, that one-time copy captures nothing — the linked Task is born blank. There is **no listener** that re-syncs the Task description when you later edit the requirement document (Specs have such a sync; Requirements do not). So you must write the body to BOTH stores yourself:

```
# After workops_requirement action=create returned { metadataId, taskId }
content_metadata action=set_content id=<metadataId> markdownContent=<body>     # the requirement document
workops_task     action=update      id=<taskId>     descriptionMarkdown=<body> # the linked Task page
```

If you write only the document, the linked Task page stays blank and looks like unstarted/empty work. If you write only the Task, the requirement document (what `generate_tasks` and reviewers read) stays empty. Write both, with the same content.

## Linking entities into the body or the context

There are two complementary places to attach references to a Spec: **SpecContext** (sidecar metadata; ideal for things you want to track without polluting the body) and **document nodes** (inline, render in the body). Use both.

### SpecContext.contextType targetId formats

| contextType    | targetId format                | example                                            |
|----------------|--------------------------------|----------------------------------------------------|
| GIT_RESOURCE   | git repository UUID            | `9c1a…`. See "Finding git repositories" below.     |
| METADATA       | metadata UUID                  | any Bosca document/collection item                  |
| COLLECTION     | collection UUID                |                                                    |
| PROFILE        | profile UUID                   | the spec owner, a reviewer, etc.                    |
| CHAT_CHANNEL   | channel UUID                   | Slack/Discord/etc. channel for discussion           |
| AI_SESSION     | agent session UUID             | the Claude Code session that authored the spec      |
| SPEC           | spec UUID                      | sibling/related spec                                |
| TASK           | task UUID                      | related task that isn't a generated/linked one      |
| PROJECT        | project UUID                   |                                                    |
| EXTERNAL_URI   | absolute URL                   | `https://…`. Use this for anything not yet typed (analytics queries, scripts, external docs). |

Always set a friendly `label` so the UI can render the link sensibly.

### In-body references

- **Inline mention**: `{ "type": "mention", "attrs": { "id": "<uuid>", "label": "@auth-service", "entityType": "metadata" }, "content": [], "marks": [] }`
- **Embedded container**: `{ "type": "container", "attrs": { "metadataId": "<uuid>", "references": ["<uuid>", …], "renderer": "card" }, "content": [], "marks": [] }`
- **Linked image / Bosca-hosted asset**: `image` node with `attrs.metadataId` set.

For pointers to git repositories, scripts, or analytics queries that don't have a dedicated TipTap node yet, link in the body with a `link` mark on a `text` node and also store the structured reference as a SpecContext (`EXTERNAL_URI` for now until a typed context is added).

## Finding git repositories that Bosca owns

Specs can bind to a git repository two ways:

1. **First-class binding** on the Spec itself: `workops_spec action=create` (or `update`) with `gitRepositoryId=<repoUuid>` and `gitPath=<path inside repo>`. This is what `pushToGit` / `pullFromGit` use under the hood — the spec body can be round-tripped to/from a file in the repo.
2. **Curated reference** via SpecContext `GIT_RESOURCE` with `targetId=<repoUuid>` and an optional `label`.

To discover repository UUIDs the server is the source of truth — Bosca's git service exposes them via `Git.repositories(ownerId, includeArchived?)`, `Git.repository(owner, repo)`, and `Git.repositoryById(id)`. Note that the MCP layer **does not yet expose a `git_repository` tool**; until it does, look up repo UUIDs through the web UI, the bosca CLI's git commands, or by invoking the GraphQL directly. Once you have the UUID, reference it from the Spec.

To reference a specific file inside a repo, the server's `Git.tree(repoId, ref?, path?)` and `Git.blob(repoId, ref, path)` queries return tree entries and blobs. Until an MCP wrapper is added, store the file pointer either as `gitPath` on the spec (if the spec IS that file) or as a SpecContext `GIT_RESOURCE` with the repo UUID plus an `EXTERNAL_URI` SpecContext to the file's web URL. Inline, link the file with a `link` mark.

## Bosca Scripts and Analytics Queries

The server tracks both Scripts and Analytics Queries as first-class entities. Scripts and queries can be **git-backed** (stored in a repo) — the git service exposes `scriptSourceRef(scriptId)`, `querySourceRef(queryId)`, and `sourceRefs(repositoryId)`. Analytics Queries also have list/get/execute endpoints (`AnalyticsQueries.all / queryById / queryByKey / execute / executeByKey`).

The MCP layer **does not yet wrap these as dedicated tools**. While planning a spec, link to them via:

- A SpecContext with `contextType=EXTERNAL_URI` and `targetId=<absolute URL to the script or query in the Bosca UI>`, plus a clear `label` (e.g. "analytics: weekly_active_users", "script: backfill_v3").
- An inline `link` mark in the document body for human-readable references.
- If the script/query lives in a known git repo, also add a `GIT_RESOURCE` SpecContext for the repo UUID.

When executing a plan, use the bosca CLI (`bosca` commands) or the web UI to run a script or analytics query — record the run in the spec via `workops_spec action=add_comment` with the result summary, and add an `EXTERNAL_URI` SpecContext to the run/output URL if useful.

## Bosca AI Agents (vs. Claude Code)

Bosca has its own first-class **AI Agents** in addition to Claude Code. Both use this same MCP surface — what differs is who is "driving" and how the session is identified.

- **Claude Code** is the agent running this conversation. When you generate tasks from a spec, set `source=CLAUDE_CODE` and pass your `agentSessionId` so the spec records that Claude Code authored the decomposition.
- **Bosca AI Agents** are server-side personas defined in workops/AI (model + system prompt + tool list + optional sub-agents). They are listed/created via the GraphQL `Agents` type (queries: `all`, `byKey`, `byId`; mutations: `add`, `edit`, `delete`). They are invoked by opening a chat session against the Kit chat API (`POST /api/kit/v1/chat`) which returns a `ChatSession` UUID. That UUID is what gets passed as `agentSessionId` with `source=KIT` when the agent generates tasks.
- The third generation source is `MANUAL` — for human-authored decompositions.

When working on a spec collaboratively with a Bosca Agent (e.g. a "Spec Refiner" agent that adds requirements based on conversation history), reference its session via `add_context contextType=AI_SESSION targetId=<chatSessionId>` so the audit trail captures which agent edited what. Multiple AI_SESSION contexts are fine — one per session that touched the spec.

The MCP layer **does not yet expose tools to list/invoke Bosca Agents directly**. Until it does, list/launch them through the Kit web UI or by calling the Kit chat REST endpoint, then bring the resulting ChatSession UUID back here as the `agentSessionId`.

## ⚠️ MANDATORY: Status Tracking

Keeping statuses current on Specs, Requirements, and Tasks is a **hard requirement** — not a nice-to-have. Every agent (Claude Code, Bosca AI Agents, or human-driven tools) MUST transition statuses as work progresses. Stale statuses break dashboards, block downstream automation, and make planning unreliable.

### Rules

1. **Before starting work** on a Task or Spec: transition it from TODO → IN_PROGRESS. Do this BEFORE you begin the actual work, not after.
2. **After completing work** on a Task: transition it to DONE with an appropriate resolution. Do this IMMEDIATELY — do not batch status updates.
3. **When a Requirement's linked Task is done**: the Requirement is done. Transition the linked Task.
4. **When ALL Requirements under a Spec are DONE**: transition the Spec itself to DONE.
5. **When work is blocked or paused**: transition to the appropriate status and add a comment explaining why and what is needed to unblock.
6. **When you stop working** without completing (e.g. end of session, context switch): add a comment to the Task/Spec with current state and any next steps, so the next agent or human can resume.

### How to transition

```
# 1. Find available transitions from current state
workops_task_transitions id=<taskId>        # for tasks
workops_reference_data action=workflows     # for specs (find transitions matching current state)

# 2. Execute the transition
workops_task action=transition id=<taskId> transitionId=<uuid>
workops_spec action=transition id=<specId> transitionId=<uuid>

# 3. For DONE transitions, include a resolution
workops_task action=transition id=<taskId> transitionId=<uuid> resolutionId=<uuid>
```

### What "stale" means

- A Task in TODO that has been worked on → stale (should be IN_PROGRESS or DONE)
- A Task in IN_PROGRESS that was completed → stale (should be DONE)
- A Spec in TODO whose Requirements are IN_PROGRESS → stale (should be IN_PROGRESS)
- A Spec in IN_PROGRESS whose Requirements are ALL DONE → stale (should be DONE)

Treat stale statuses as bugs. Fix them when you encounter them.

## ⚠️ MANDATORY: Keep the Spec Document Current

The spec body is the **living plan** — it must always reflect what you currently intend to do, not what you originally planned. As you execute a spec:

1. **When you learn something new** (a constraint, a dependency, a risk, a better approach): update the spec document via `content_metadata action=set_content id=<metadataId>` with the revised plan.
2. **When you change approach** (different design, dropped scope, pivoted direction): update the spec document AND add/remove/update Requirements to match the new reality.
3. **When a requirement turns out wrong or unnecessary**: delete or update the Requirement, and update the spec body to explain the change.
4. **When work reveals new requirements**: create new Requirements via `workops_requirement action=create` AND update the spec body to include them.
5. **Use comments for point-in-time notes** (`workops_spec action=add_comment`): "discovered X during investigation", "blocked on Y". Use the **document body** for the authoritative current plan.

A spec whose document says "we will do X" when the team has moved to "we will do Y" is broken documentation. Update the plan as the plan changes — the version history preserves what it used to say.

## ⚠️ MANDATORY: Track All Work as Tasks

Every unit of work an agent performs **must** be tracked as a task in workops. Do not do work that is invisible to the project tracking system.

- **When decomposing work into subtasks**: create a workops task for EACH subtask via `workops_task action=create` in the same project with `parentTaskId=<parent task UUID>` to establish the hierarchy.
- **When you discover follow-up work** during execution: create a new task for it immediately — don't leave it as a mental note or comment only.
- **When work spans multiple areas**: create tasks in each relevant project, or use `addAffectedProject` to associate a task with additional projects.
- Each subtask follows all the same status tracking rules: transition to IN_PROGRESS when starting, DONE when complete.

Untracked work is invisible work. If it's not in a task, it doesn't exist from a project management perspective.

## Things that trip people up

- **Don't fetch metadata IDs first.** Pass `name` on create — auto-creation builds the document, binds it to the seeded Spec/Requirement Document Template (which tags it with the right document `type`), and links it. A `metadataId` you create by hand skips that template binding, so only supply one to attach a document you have deliberately prepared.
- **Requirements need their body written twice.** The linked Task is snapshotted from the (empty) document at create and never re-synced — write the body to both the document AND the linked Task. See "Requirements: write the body twice".
- **Requirements already create one Task each.** `generate_tasks` adds MORE; it's not what creates the first.
- **`metadataVersion` for generate_tasks**: pass `1` unless you have edited the spec's document and want to snapshot a specific later version.
- **`expectedVersion`** is the spec's own version (for optimistic locking on mutations), not the metadata version.
- **Spec status ≠ workflow state.** The spec carries a `status` (To Do / In Progress / Done category). To change it, fetch the workflow via `workops_reference_data action=workflows`, pick a transition whose source state matches, and pass its id to action=transition.
- **SpecContext is curated, not auto-discovered.** Use it to bolt references (git commits, Slack channels, AI session IDs, external URLs) onto a Spec without bloating its metadata.
- **Set `contentType=application/json`** when sending a TipTap doc via `set_content` (NOT `text/html` or markdown — those will be stored verbatim, not parsed).

## Tool quick-reference

- `workops_spec` — manage the spec itself. Actions: get, list, children, create, update, transition, delete, restore, contexts, add_context, remove_context, comments, add_comment, generate_tasks.
- `workops_spec_history` — audit trail of every field change.
- `workops_requirement` — manage requirements inside a spec/task.
- `workops_task` / `workops_task_transitions` — work with the linked/generated Tasks.
- `workops_worklog`, `workops_comment`, `workops_link`, `workops_notification` — tracking work, discussion, dependencies, watching.
- `workops_reference_data` — workflows, statuses, priorities, resolutions (needed for transition ids and priority ids).
- `workops_search` — BQL search over tasks.
- `content_metadata` — edit the spec/requirement document bodies using their `metadataId`.
- `content_template`, `content_supplementary`, `content_relationship` — supporting content tools.
""".trimIndent()

    private fun Map<String, JsonElement>.str(key: String): String =
        get(key)?.jsonPrimitive?.content ?: error("Missing required field: $key")

    private fun Map<String, JsonElement>.optStr(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    private fun Map<String, JsonElement>.long(key: String): Long =
        get(key)?.jsonPrimitive?.long ?: error("Missing required field: $key")

    private fun Map<String, JsonElement>.optLong(key: String): Long? =
        get(key)?.jsonPrimitive?.longOrNull

    private fun Map<String, JsonElement>.optInt(key: String): Int? =
        get(key)?.jsonPrimitive?.intOrNull

    private fun Map<String, JsonElement>.optBool(key: String): Boolean? =
        get(key)?.jsonPrimitive?.booleanOrNull

    private fun Map<String, JsonElement>.optJsonObject(key: String): JsonObject? =
        get(key) as? JsonObject

    private suspend fun Map<String, JsonElement>.resolveTaskId(api: WorkOpsApi): Uuid =
        optStr("taskId")?.let { Uuid.parse(it) }
            ?: optStr("taskKey")?.let { api.getTaskByKey(it)?.id ?: error("Task not found") }
            ?: error("Provide taskId or taskKey")

    private suspend fun Map<String, JsonElement>.resolveSpecId(api: WorkOpsApi): Uuid =
        optStr("id")?.let { Uuid.parse(it) }
            ?: optStr("key")?.let { api.getSpecByKey(it)?.id ?: error("Spec not found") }
            ?: error("Provide id or key")

    // Fragment → JSON mappers

    private fun bosca.graphql.gen.IWorkOpsPortfolioFragment.toJson() = buildJsonObject {
        put("id", id.toString()); put("key", key); put("name", name); put("description", description)
        put("ownerProfileId", ownerProfileId.toString()); put("version", version)
        put("archivedAt", archivedAt?.toString()); put("createdAt", createdAt.toString())
    }

    private fun bosca.graphql.gen.IWorkOpsProgramFragment.toJson() = buildJsonObject {
        put("id", id.toString()); put("key", key); put("name", name); put("description", description)
        put("ownerProfileId", ownerProfileId.toString()); put("version", version)
        put("archivedAt", archivedAt?.toString()); put("createdAt", createdAt.toString())
    }

    private fun bosca.graphql.gen.IWorkOpsProjectFragment.toJson() = buildJsonObject {
        put("id", id.toString()); put("key", key); put("name", name); put("description", description)
        put("ownerProfileId", ownerProfileId.toString()); put("version", version)
        put("archivedAt", archivedAt?.toString()); put("createdAt", createdAt.toString())
    }

    private fun bosca.graphql.gen.IWorkOpsTaskFragment.toJson() = buildJsonObject {
        put("id", id.toString()); put("key", key); put("summary", summary)
        put("descriptionMarkdown", descriptionMarkdown)
        put("projectKey", project.key); put("projectName", project.name)
        put("taskType", taskType.name); put("taskTypeHierarchy", taskType.hierarchyLevel.name)
        put("status", status.name); put("statusCategory", status.category.name)
        put("priority", priority.name)
        put("assigneeProfileId", assigneeProfileId?.toString())
        put("reporterProfileId", reporterProfileId.toString())
        put("resolution", resolution?.name)
        put("dueDate", dueDate?.toString()); put("startDate", startDate?.toString())
        put("epicKey", epicTask?.key); put("parentTaskId", parentTask?.id?.toString()); put("parentKey", parentTask?.key)
        put("version", version); put("createdAt", createdAt.toString()); put("modifiedAt", modifiedAt.toString())
    }

    private fun bosca.graphql.gen.IWorkOpsTaskSummaryFragment.toJson() = buildJsonObject {
        put("id", id.toString()); put("key", key); put("summary", summary)
        put("taskType", taskType.name); put("status", status.name); put("statusCategory", status.category.name)
        put("priority", priority.name); put("assigneeProfileId", assigneeProfileId?.toString())
        put("dueDate", dueDate?.toString()); put("version", version)
    }

    private fun bosca.graphql.gen.IWorkOpsBoardFragment.toJson() = buildJsonObject {
        put("id", id.toString()); put("name", name); put("type", type.name)
        put("swimlaneStrategy", swimlaneStrategy.name); put("projectId", projectId?.toString())
        put("programId", programId?.toString()); put("version", version)
        put("columns", JsonArray(columns.sortedBy { it.displayOrder }.map {
            buildJsonObject { put("id", it.id.toString()); put("name", it.name); put("displayOrder", it.displayOrder); put("wipLimit", it.wipLimit) }
        }))
    }

    private fun bosca.graphql.gen.IWorkOpsSprintFragment.toJson() = buildJsonObject {
        put("id", id.toString()); put("name", name); put("goal", goal); put("state", state.name)
        put("boardId", boardId.toString())
        put("startDate", startDate?.toString()); put("endDate", endDate?.toString())
        put("committedTasks", committedTaskIds.size); put("addedTasks", addedDuringSprintTaskIds.size)
        put("velocityPoints", velocityPoints); put("version", version)
    }

    private fun bosca.graphql.gen.IWorkOpsSpecFragment.toSpecJson() = buildJsonObject {
        put("id", id.toString()); put("key", key); put("metadataId", metadataId.toString())
        put("status", status.name); put("statusCategory", status.category.name)
        put("ownerProfileId", ownerProfileId.toString())
        put("projectId", projectId?.toString()); put("projectKey", project?.key)
        put("programId", programId?.toString())
        put("parentSpecId", parentSpecId?.toString()); put("parentSpecKey", parentSpec?.key)
        put("childCount", childCount); put("childDoneCount", childDoneCount)
        put("sortOrder", sortOrder)
        put("gitRepositoryId", gitRepositoryId?.toString()); put("gitPath", gitPath)
        put("version", version)
        put("createdAt", createdAt.toString()); put("modifiedAt", modifiedAt.toString())
    }

    private fun bosca.graphql.gen.IWorkOpsWorkLogFragment.toJson() = buildJsonObject {
        put("id", id.toString()); put("taskId", taskId.toString()); put("profileId", profileId.toString())
        put("timeSpentShort", timeSpentShort); put("timeSpentSeconds", timeSpentSeconds)
        put("startedAt", startedAt.toString()); put("comment", comment)
    }
}
