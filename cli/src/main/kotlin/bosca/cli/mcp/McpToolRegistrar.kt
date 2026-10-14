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
import kotlinx.serialization.builtins.serializer
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * Registers coarse-grained MCP tools for the entire workops surface.
 * Each tool maps to one or more WorkOpsApi operations, selected by
 * parameters to keep the tool count AI-manageable.
 */
object McpToolRegistrar {

    private const val TASK_STATUS_REMINDER = "\n\n⚠️ REMINDER: Keep this task's status current. If you are about to work on it, transition it to IN_PROGRESS now (use workops_task_transitions to find available transitions). Load the effective project guidance and follow its policy for interface review, TDD, and quality evidence. When done, transition to DONE with a resolution. If you decompose this into subtasks, create a workops task for EACH one with parentTaskId set to this task's id."
    private const val SPEC_STATUS_REMINDER = "\n\nKeep the spec's status and document current. Discover workflow transitions with workops_spec action=transitions. Load the effective project guidance and follow its policy. Before completion, record evidence and check workops_plan action=verify; review any remaining gaps before transitioning to DONE."
    private const val REQUIREMENT_STATUS_REMINDER = "\n\nPopulate the document and linked task together with workops_requirement action=set_content (id, name, markdownContent, acceptanceCriteria). Keep the linked task status current and record validation with workops_plan."

    fun registerAll(server: Server, api: WorkOpsApi, contentApi: ContentApi) {
        registerProjectTools(server, api)
        registerTaskTools(server, api)
        registerSpecInstructionsTool(server, api)
        registerSpecTools(server, api)
        WorkOpsPlanToolRegistrar.registerAll(server, api, contentApi)
        registerRequirementTools(server, api, contentApi)
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Spec Instructions ───────────────────────────────────────

    private fun registerSpecInstructionsTool(server: Server, api: WorkOpsApi) {
        server.addTool(Tool(name = "workops_spec_instructions",
            description = "Read effective WorkOps guides and executable policy before investigation, planning, or implementation. Optional projectId or projectKey selects project overrides; no arguments returns platform defaults. Reload when resuming work.",
            inputSchema = ToolSchema(properties = buildJsonObject {
                put("projectId", buildJsonObject { put("type", "string") })
                put("projectKey", buildJsonObject { put("type", "string") })
            }))) { request ->
            WorkOpsPlanToolRegistrar.result {
                WorkOpsPlanToolRegistrar.loadInstructions(api, request.arguments.orEmpty())
            }
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

                    THE WORKFLOW: Spec (the plan) → Requirements (testable deliverables, each linked to a Task) → execute linked tasks and record validation. generate_tasks optionally creates one additional Task per Requirement.

                    ⚠️ A SPEC WITHOUT REQUIREMENTS IS INCOMPLETE. After workops_spec action=create, you MUST decompose the spec's body into Requirements using workops_requirement action=create (parentType=SPEC, parentId=<this spec's id>). The Spec is the narrative plan; the Requirements are the actionable items. Create a complete plan with workops_plan action=create, including bodies and acceptance criteria. Do not consider spec creation done until at least one Requirement has been added.

                    PREREQUISITES for create:
                    - A Project must exist (use workops_project). Pass `projectId` or `projectKey`.
                    - That's it. No metadata setup needed — pass `name` and a `bosca/v-document` metadata is auto-created, bound to the seeded Spec Document Template, and linked. You can also pass an existing `metadataId` to attach to a pre-built document.

                    ACTIONS:
                    - get: fetch by id or key.
                    - list: list specs in a project/program/by owner (one of projectId, projectKey, programId, ownerId required).
                    - children: list direct sub-specs of a parent (pass parentSpecId or id).
                    - create: create a new spec. Required: projectId or projectKey. Optional: name (titles the auto-created document — defaults to the minted SPEC key), metadataId (skip auto-create), parentSpecId, workflowId, sortOrder, gitRepositoryId+gitPath. MUST be followed by workops_requirement creates that decompose the spec body.
                    - update: edit owner/project/program/parent/sortOrder/git binding. Pass id or key, optionally expectedVersion for optimistic locking.
                    - transitions: list transitions reachable from the current state (id or key).
                    - transition: move to a new workflow state. Pass transitionId (from action=transitions). Optional: resolutionId.
                    - delete / restore: soft delete or restore.
                    - contexts / add_context / remove_context: manage curated references. ContextTypes: GIT_RESOURCE, METADATA, COLLECTION, PROFILE, CHAT_CHANNEL, AI_SESSION (the Claude Code session that authored the spec — store its UUID here), SPEC, TASK, PROJECT, EXTERNAL_URI.
                    - comments / add_comment: discussion thread on the spec.
                    - generate_tasks: create one additional Task per requirement and record the generation. Required: metadataVersion (the current spec metadata version), source (CLAUDE_CODE | MANUAL | KIT). For source=CLAUDE_CODE, pass agentSessionId. Note: each Requirement already has one linked Task; generate_tasks creates ADDITIONAL Tasks.

                    To edit the spec's document body (the actual plan text), use the content_metadata tool against the returned metadataId.

                    ⚠️ MANDATORY: KEEP THE SPEC DOCUMENT CURRENT. The spec body is the living plan — not a snapshot from creation time. As you execute:
                    - When you LEARN something new (a constraint, a dependency, a risk): update the spec document to reflect it via content_metadata action=set_content.
                    - When you CHANGE APPROACH (different design, dropped a requirement, added a new one): update the spec document AND add/remove/update Requirements to match.
                    - When a requirement turns out to be wrong or unnecessary: update or delete it, and update the spec body to explain why.
                    - When work reveals new requirements: add them as new Requirements AND update the spec body.
                    The spec must always reflect the CURRENT plan, not the original plan. A spec whose document diverges from reality is as broken as a stale status. Use add_comment for point-in-time notes; use the document body for the authoritative current state.

                    ⚠️ MANDATORY STATUS TRACKING: Agents MUST keep spec statuses up-to-date at all times. This is not optional.
                    - Prefer workops_plan action=context to resume and action=verify to check completion evidence.
                    - When you BEGIN executing a spec's plan: transition the spec to IN_PROGRESS immediately (use workops_spec action=transitions to find transitions, then action=transition).
                    - Before transitioning the spec to DONE: satisfy all requirements, record validation, and review workops_plan action=verify for remaining gaps.
                    - When a spec is BLOCKED or paused: add a comment explaining why and transition to the appropriate status.
                    - When you STOP working on a spec without completing it: add a comment explaining the current state.
                    Stale statuses are treated as data integrity failures. A spec that has been worked on but still shows TODO, or is complete but still shows IN_PROGRESS, is broken tracking data.

                    Read effective project guidance with workops_spec_instructions before planning and implementation.
                    Use workops_plan for complete plan creation, reviews, context, next_action, and validation.
                    Current typed policy governs interface, TDD, test quality, and coverage checks.
                """.trimIndent(),
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("get", "list", "create", "update", "transition", "delete", "restore", "children", "transitions", "contexts", "add_context", "remove_context", "comments", "add_comment", "generate_tasks").map { JsonPrimitive(it) })) })
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
                        put("transitionId", buildJsonObject { put("type", "string"); put("description", "Workflow transition UUID. Look up via workops_spec action=transitions.") })
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
                        put("metadataVersion", buildJsonObject { put("type", "integer"); put("description", "Version of the spec's document to snapshot for generate_tasks. Read the current version through content_metadata action=get.") })
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
                    "transitions" -> {
                        val specId = args.resolveSpecId(api)
                        val transitions = api.getSpecTransitions(specId) ?: error("Spec not found")
                        JsonArray(transitions.transitions.map { WorkOpsPlanToolRegistrar.transitionJson(it) }).toString()
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Requirements ───────────────────────────────────────────

    private fun registerRequirementTools(server: Server, api: WorkOpsApi, contentApi: ContentApi) {
        server.addTool(
            Tool(
                name = "workops_requirement",
                description = """
                    Manage workops Requirements — the individual items inside a Spec (or pinned to a Task). Each Requirement automatically gets:
                    1. Its own backing metadata document (auto-created — pass `name` to title it, or supply your own `metadataId`).
                    2. A linked Task that mirrors its status. The linked Task is created the moment the Requirement is created.

                    Later, calling workops_spec action=generate_tasks against the parent Spec will create ADDITIONAL Tasks from each Requirement's document content. The linked Task and generated Tasks are separate — the linked Task tracks the Requirement itself; each generation creates one additional Task per Requirement.

                    CREATE FLOW: pass parentType=SPEC (or TASK) and parentId=<spec or task UUID>. Pass `name` to title the new document. Everything else (workflow, priority, status) defaults to the parent project's settings. The create response returns both `metadataId` (the requirement document) and `taskId` (the linked Task) — you need both for the next step.

                    Use set_content with id (or key), name, markdownContent, and acceptanceCriteria to
                    populate or revise both the requirement document and its linked task in one call.
                    This explicit edit replaces the linked task's independent summary/description too.
                    Low-level content_metadata writes alone do not synchronize the linked task.
                    Use workops_plan for complete planning, review, resumption, and validation.

                    ⚠️ MANDATORY STATUS TRACKING: Agents MUST keep requirement statuses up-to-date. Requirements have a linked Task whose status mirrors the requirement. When you begin work on a requirement's linked task, transition it to IN_PROGRESS. When the requirement is satisfied, transition its linked task to DONE. Stale requirement statuses are treated as data integrity failures.

                    Use `move` to re-parent a Requirement between Spec/Task. Use `list_by_spec` / `list_by_task` to enumerate.
                """.trimIndent(),
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("get", "list_by_spec", "list_by_task", "create", "update", "delete", "restore", "move", "set_content").map { JsonPrimitive(it) })) })
                        put("id", buildJsonObject { put("type", "string"); put("description", "Requirement UUID") })
                        put("key", buildJsonObject { put("type", "string"); put("description", "Requirement key (e.g. REQ-7)") })
                        put("specId", buildJsonObject { put("type", "string"); put("description", "Spec UUID (for list_by_spec)") })
                        put("taskId", buildJsonObject { put("type", "string"); put("description", "Task UUID (for list_by_task)") })
                        put("metadataId", buildJsonObject { put("type", "string"); put("description", "Optional. Existing metadata to link. If omitted on create, a new bosca/v-document is auto-created titled `name` (or the minted REQ key).") })
                        put("name", buildJsonObject { put("type", "string"); put("description", "Title for the auto-created document on create. Recommended.") })
                        put("markdownContent", buildJsonObject { put("type", "string"); put("description", "Requirement body for set_content; acceptance criteria are supplied separately.") })
                        put("acceptanceCriteria", buildJsonObject {
                            put("type", "array"); put("minItems", 1)
                            put("items", buildJsonObject { put("type", "string") })
                        })
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
            if (args.str("action") == "set_content") {
                return@addTool WorkOpsPlanToolRegistrar.result {
                    val explicitId = args.optStr("id")
                    val requirementId = if (explicitId != null) Uuid.parse(explicitId) else {
                        val key = args.optStr("key") ?: error("Provide id or key")
                        val requirement = api.getRequirementByKey(key) ?: error("Requirement not found")
                        requirement.id
                    }
                    val input = WorkOpsPlanToolRegistrar.RequirementInput(
                        name = args.str("name"), markdownContent = args.str("markdownContent"),
                        acceptanceCriteria = json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(String.serializer()), args.getValue("acceptanceCriteria")),
                    )
                    WorkOpsPlanToolRegistrar.writeRequirementContent(api, contentApi, requirementId, input).toString()
                }
            }
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private val json = Json { prettyPrint = false; encodeDefaults = true }



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
