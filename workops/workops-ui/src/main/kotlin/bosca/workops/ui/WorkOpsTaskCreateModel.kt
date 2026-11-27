package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.SchemaFormOption
import bosca.design.model.schemaFormNodes
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

/** WorkOps owns form selection, domain choices and submission; shared controls own rendering. */
@Serializable
class WorkOpsTaskCreateModel(
    var open: Boolean = false,
    var projectId: String = "",
    var draft: JsonObject = JsonObject(emptyMap()),
    var definition: WorkOpsTaskForm? = null,
    var options: Map<String, List<SchemaFormOption>> = emptyMap(),
    var error: String = "",
    var createdId: String = "",
    var reloadRevision: Long = 0,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val controlAliases get() = mapOf("workops-select" to "select", "profile-select" to "remote-select", "graphql-select" to "remote-select", "api-script-select" to "remote-select")
    val preserveKey get() = "workops-create-${definition?.key}-${definition?.version}-$projectId-$reloadRevision"

    suspend fun begin(ctx: RenderContext) {
        feedback.message = ""
        if (createdId.isNotBlank()) {
            createdId = ""
            draft = if (projectId.isBlank()) JsonObject(emptyMap()) else buildJsonObject { put("projectId", projectId) }
        }
        open = true
        if (definition == null) load(ctx)
    }

    fun cancel() { open = false }

    suspend fun retry(ctx: RenderContext) { load(ctx) }

    suspend fun reload(attributes: String, ctx: RenderContext) {
        retain(attributes)
        load(ctx)
        reloadRevision++
    }

    suspend fun changeProject(projectId: String, attributes: String, ctx: RenderContext) {
        retain(attributes)
        this.projectId = projectId
        draft = JsonObject(draft + ("projectId" to JsonPrimitive(projectId)))
        load(ctx)
    }

    suspend fun submit(attributes: String, ctx: RenderContext) {
        try {
            retain(attributes)
            error = ""
            val form = requireNotNull(definition) { "Load the task form before creating a task." }
            val submitted = ctx.gql.execute(WorkOpsSubmitTaskForm, WorkOpsSubmitTaskForm.Variables(form.key, draft)).forms.submit
            check(submitted.type == FormSchemaType.WORK_OPS) { "The selected form did not create a WorkOps task." }
            createdId = submitted.id
            open = false
            feedback.success("Task created.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not create the task." }
    }

    suspend fun load(ctx: RenderContext) {
        try {
            error = ""
            val available = ctx.gql.execute(WorkOpsTaskCreateChoices, Unit).workOps
            val project = available.projects.all.firstOrNull { it.id == projectId }
            require(projectId.isBlank() || project != null) { "The selected project is unavailable." }
            val key = project?.taskCreationFormSchemaKey ?: "workops.create-task"
            val form = requireNotNull(ctx.gql.execute(WorkOpsTaskCreateForm, WorkOpsTaskCreateForm.Variables(key)).formSchemas.byKey) {
                "The task creation form is unavailable."
            }
            require(form.type == FormSchemaType.WORK_OPS) { "The project task form must have WorkOps type." }
            definition = WorkOpsTaskForm(form.key, form.name, form.version, form.schema.jsonObject, form.uiSchema.jsonObject)
            val entities = mapOf(
                "projects" to available.projects.all.map { SchemaFormOption(it.id, "${it.key} · ${it.name}") },
                "taskTypes" to available.tasks.taskTypes.map { SchemaFormOption(it.id, it.name) },
                "priorities" to available.tasks.priorities.sortedBy { it.displayOrder }.map { SchemaFormOption(it.id, it.name) },
                "statuses" to available.statuses.all.map { SchemaFormOption(it.id, it.name) },
                "resolutions" to available.tasks.resolutions.map { SchemaFormOption(it.id, it.name) },
            )
            val fields = mutableMapOf<String, List<SchemaFormOption>>()
            fun collect(layout: JsonArray) {
                for (element in layout) {
                    val node = element.jsonObject
                    if (node["control"]?.jsonPrimitive?.content == "workops-select") {
                        val property = node.getValue("property").jsonPrimitive.content
                        val entity = node["workOpsEntity"]?.jsonPrimitive?.contentOrNull
                        fields[property] = requireNotNull(entities[entity]) { "Unknown WorkOps choice source: $entity" }
                    }
                    (node["children"] as? JsonArray)?.let(::collect)
                }
            }
            collect(form.uiSchema.jsonObject.getValue("layout").jsonArray)
            options = fields
            if (projectId.isNotBlank() && !draft.containsKey("projectId")) draft = JsonObject(draft + ("projectId" to JsonPrimitive(projectId)))
            schemaFormNodes(form.schema.jsonObject, form.uiSchema.jsonObject, draft, options, controlAliases)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { definition = null; error = e.message ?: "Could not load the task form." }
    }

    private fun retain(attributes: String) { draft = Json.parseToJsonElement(attributes).jsonObject }
}

suspend fun workOpsTaskCreate(): WorkOpsTaskCreateModel {
    val ctx = currentRenderContext()
    return WorkOpsTaskCreateModel(open = ctx.query["new"] == "1", projectId = ctx.query["project"].orEmpty()).also {
        if (it.open) it.load(ctx)
    }
}
