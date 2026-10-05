package bosca.ide.workops

import bosca.ide.server.BoscaConnectionManager
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture

/** Server-scoped WorkOps queries and optimistic-locking mutations. */
@Service(Service.Level.PROJECT)
class BoscaWorkOpsService(project: Project) {
    private val connections = project.getService(BoscaConnectionManager::class.java)

    fun projects(serverProfileId: String): CompletableFuture<List<BoscaWorkOpsProject>> =
        connections.execute(serverProfileId, PROJECTS_QUERY).thenApply { data ->
            data.getAsJsonObject("workOps").getAsJsonObject("projects").getAsJsonArray("all").map { value ->
                value.asJsonObject.let {
                    BoscaWorkOpsProject(serverProfileId, it.string("id"), it.string("key"), it.string("name"))
                }
            }
        }

    fun bundle(serverProfileId: String, projectId: String): CompletableFuture<BoscaWorkOpsBundle> =
        connections.execute(serverProfileId, BUNDLE_QUERY, variables("projectId" to projectId)).thenApply { data ->
            val workOps = data.getAsJsonObject("workOps")
            BoscaWorkOpsBundle(
                tasks = workOps.getAsJsonObject("tasks").getAsJsonArray("byProject")
                    .map { parseTask(serverProfileId, projectId, it.asJsonObject) },
                specs = workOps.getAsJsonObject("specs").getAsJsonArray("byProject")
                    .map { parseSpec(serverProfileId, projectId, it.asJsonObject) },
                resolutions = workOps.getAsJsonObject("tasks").getAsJsonArray("resolutions")
                    .map { BoscaWorkOpsResolution(it.asJsonObject.string("id"), it.asJsonObject.string("name")) },
                linkTypes = workOps.getAsJsonObject("links").getAsJsonArray("linkTypes").map {
                    BoscaWorkOpsLinkType(
                        it.asJsonObject.string("id"),
                        it.asJsonObject.string("name"),
                        it.asJsonObject.string("outwardLabel"),
                    )
                },
            )
        }

    fun document(serverProfileId: String, metadataId: String): CompletableFuture<BoscaWorkOpsDocument> =
        connections.execute(serverProfileId, DOCUMENT_QUERY, variables("id" to metadataId)).thenApply { data ->
            val metadata = data.getAsJsonObject("content").getAsJsonObject("metadata")
            val document = metadata.getAsJsonObject("document")
            BoscaWorkOpsDocument(
                metadataId = metadata.string("id"),
                version = metadata.get("version").asInt,
                title = document.string("title"),
                markdown = BoscaDocumentMarkdown.toMarkdown(document.get("content")),
            )
        }

    fun createTask(serverId: String, projectId: String, summary: String, description: String): CompletableFuture<Boolean> =
        mutation(serverId, CREATE_TASK, variables("input" to JsonObject().apply {
            addProperty("projectId", projectId)
            addProperty("summary", summary)
            addProperty("descriptionMarkdown", description)
        }))

    fun updateTask(serverId: String, task: BoscaWorkOpsTask, summary: String, description: String, assigneeId: String?): CompletableFuture<Boolean> =
        mutation(serverId, UPDATE_TASK, variables("id" to task.id, "input" to JsonObject().apply {
            addProperty("summary", summary)
            addProperty("descriptionMarkdown", description)
            addProperty("expectedVersion", task.version)
            if (assigneeId.isNullOrBlank()) addProperty("clearAssignee", true) else addProperty("assigneeProfileId", assigneeId)
        }))

    fun transitionTask(
        serverId: String,
        task: BoscaWorkOpsTask,
        transition: BoscaWorkOpsTransition,
        resolutionId: String?,
        comment: String?,
    ): CompletableFuture<Boolean> = mutation(serverId, TRANSITION_TASK, variables(
        "id" to task.id,
        "transitionId" to transition.id,
        "version" to task.version,
        "resolutionId" to resolutionId,
        "comment" to comment,
    ))

    fun commentTask(serverId: String, taskId: String, content: String): CompletableFuture<Boolean> =
        mutation(serverId, COMMENT_TASK, variables("id" to taskId, "input" to commentInput(content)))

    fun linkTask(serverId: String, task: BoscaWorkOpsTask, linkType: BoscaWorkOpsLinkType, targetKey: String): CompletableFuture<Boolean> =
        connections.execute(serverId, TASK_ID_QUERY, variables("key" to targetKey)).thenCompose { data ->
            val target = data.getAsJsonObject("workOps").getAsJsonObject("tasks").getAsJsonObject("taskByKey")
                ?: throw IllegalArgumentException("No visible WorkOps issue has key $targetKey")
            mutation(serverId, LINK_TASK, variables("input" to JsonObject().apply {
                addProperty("linkTypeId", linkType.id)
                addProperty("sourceTaskId", task.id)
                addProperty("targetTaskId", target.string("id"))
            }))
        }

    fun createSpec(serverId: String, projectId: String, name: String): CompletableFuture<Boolean> =
        mutation(serverId, CREATE_SPEC, variables("input" to JsonObject().apply {
            addProperty("projectId", projectId)
            addProperty("name", name)
        }))

    fun transitionSpec(serverId: String, spec: BoscaWorkOpsSpec, transition: BoscaWorkOpsTransition, resolutionId: String?): CompletableFuture<Boolean> =
        mutation(serverId, TRANSITION_SPEC, variables(
            "id" to spec.id,
            "transitionId" to transition.id,
            "version" to spec.version,
            "resolutionId" to resolutionId,
        ))

    fun commentSpec(serverId: String, specId: String, content: String): CompletableFuture<Boolean> =
        mutation(serverId, COMMENT_SPEC, variables("id" to specId, "input" to commentInput(content)))

    fun createRequirement(serverId: String, specId: String, name: String): CompletableFuture<Boolean> =
        mutation(serverId, CREATE_REQUIREMENT, variables("input" to JsonObject().apply {
            addProperty("parentType", "SPEC")
            addProperty("parentId", specId)
            addProperty("name", name)
        }))

    fun transitionRequirement(
        serverId: String,
        requirement: BoscaWorkOpsRequirement,
        transition: BoscaWorkOpsTransition,
        resolutionId: String?,
        comment: String?,
    ): CompletableFuture<Boolean> {
        val taskId = requireNotNull(requirement.taskId) { "Requirement ${requirement.key} has no linked task" }
        val taskVersion = requireNotNull(requirement.taskVersion) { "Requirement ${requirement.key} has no linked task version" }
        return mutation(serverId, TRANSITION_TASK, variables(
            "id" to taskId,
            "transitionId" to transition.id,
            "version" to taskVersion,
            "resolutionId" to resolutionId,
            "comment" to comment,
        ))
    }

    fun commentRequirement(serverId: String, requirementId: String, content: String): CompletableFuture<Boolean> =
        mutation(serverId, COMMENT_REQUIREMENT, variables("id" to requirementId, "input" to commentInput(content)))

    fun saveDocument(serverId: String, document: BoscaWorkOpsDocument, title: String, markdown: String): CompletableFuture<Boolean> =
        mutation(serverId, SAVE_DOCUMENT, variables(
            "id" to document.metadataId,
            "version" to document.version,
            "title" to title,
            "markdown" to markdown,
        )).thenCompose {
            mutation(serverId, RENAME_DOCUMENT, variables("id" to document.metadataId, "title" to title))
        }

    private fun mutation(serverId: String, query: String, variables: JsonObject): CompletableFuture<Boolean> =
        connections.execute(serverId, query, variables).thenApply { true }

    private fun commentInput(content: String) = JsonObject().apply {
        addProperty("visibility", "USER")
        addProperty("content", content)
    }

    private fun parseTask(serverId: String, projectId: String, json: JsonObject) = BoscaWorkOpsTask(
        serverProfileId = serverId,
        projectId = projectId,
        id = json.string("id"),
        key = json.string("key"),
        summary = json.string("summary"),
        descriptionMarkdown = json.nullableString("descriptionMarkdown").orEmpty(),
        status = json.getAsJsonObject("status").string("name"),
        statusCategory = json.getAsJsonObject("status").string("category"),
        priority = json.getAsJsonObject("priority").string("name"),
        taskType = json.getAsJsonObject("taskType").string("name"),
        assigneeProfileId = json.nullableString("assigneeProfileId"),
        assigneeName = json.getAsJsonObject("assignee")?.nullableString("name"),
        modifiedAt = json.string("modifiedAt"),
        version = json.get("version").asLong,
        transitions = json.getAsJsonArray("transitions").map { parseTransition(it.asJsonObject) },
        comments = json.getAsJsonArray("comments").map { parseComment(it.asJsonObject) },
        links = json.getAsJsonArray("links").map { link -> parseLink(json.string("id"), link.asJsonObject) },
        history = json.getAsJsonArray("history").map { parseHistory(it.asJsonObject) },
    )

    private fun parseSpec(serverId: String, projectId: String, json: JsonObject): BoscaWorkOpsSpec {
        val metadata = json.getAsJsonObject("metadata")
        return BoscaWorkOpsSpec(
            serverProfileId = serverId,
            projectId = projectId,
            id = json.string("id"),
            key = json.string("key"),
            name = metadata?.nullableString("name") ?: json.string("key"),
            metadataId = json.string("metadataId"),
            metadataVersion = metadata?.get("version")?.asInt ?: 1,
            markdown = BoscaDocumentMarkdown.toMarkdown(metadata?.getAsJsonObject("document")?.get("content")),
            status = json.getAsJsonObject("status").string("name"),
            statusCategory = json.getAsJsonObject("status").string("category"),
            ownerName = json.getAsJsonObject("owner")?.nullableString("name"),
            modifiedAt = json.string("modifiedAt"),
            version = json.get("version").asLong,
            transitions = json.getAsJsonArray("transitions").map { parseTransition(it.asJsonObject) },
            requirements = json.getAsJsonArray("requirements").map { parseRequirement(serverId, it.asJsonObject) },
            comments = json.getAsJsonArray("comments").map { parseComment(it.asJsonObject) },
            history = json.getAsJsonArray("history").map { parseHistory(it.asJsonObject) },
        )
    }

    private fun parseRequirement(serverId: String, json: JsonObject): BoscaWorkOpsRequirement {
        val task = json.getAsJsonObject("task")
        return BoscaWorkOpsRequirement(
            serverProfileId = serverId,
            id = json.string("id"),
            key = json.string("key"),
            metadataId = json.string("metadataId"),
            status = json.getAsJsonObject("status").string("name"),
            statusCategory = json.getAsJsonObject("status").string("category"),
            priority = json.getAsJsonObject("priority").string("name"),
            assigneeName = json.getAsJsonObject("assignee")?.nullableString("name"),
            taskId = json.nullableString("taskId"),
            taskVersion = task?.get("version")?.asLong,
            transitions = task?.getAsJsonArray("transitions")?.map { parseTransition(it.asJsonObject) }.orEmpty(),
            version = json.get("version").asLong,
        )
    }

    private fun parseTransition(json: JsonObject): BoscaWorkOpsTransition {
        val status = json.getAsJsonObject("toState").getAsJsonObject("status")
        return BoscaWorkOpsTransition(json.string("id"), json.string("name"), status.string("name"), status.string("category"))
    }

    private fun parseComment(json: JsonObject) = BoscaWorkOpsComment(
        id = json.get("id").asLong,
        author = json.getAsJsonObject("profile")?.nullableString("name") ?: "Unknown",
        created = json.string("created"),
        content = json.string("content"),
    )

    private fun parseLink(taskId: String, json: JsonObject): String {
        val source = json.getAsJsonObject("sourceTask")
        val target = json.getAsJsonObject("targetTask")
        val type = json.getAsJsonObject("linkType")
        return if (source?.nullableString("id") == taskId) {
            "${type.string("outwardLabel")} ${target?.nullableString("key") ?: "deleted issue"}"
        } else {
            "${type.string("inwardLabel")} ${source?.nullableString("key") ?: "deleted issue"}"
        }
    }

    private fun parseHistory(json: JsonObject): String {
        val fields = json.getAsJsonArray("changes").joinToString { change ->
            change.asJsonObject.string("fieldKey")
        }
        return "${json.string("changedAt")} · $fields"
    }

    private fun variables(vararg values: Pair<String, Any?>) = JsonObject().apply {
        values.forEach { (key, value) ->
            when (value) {
                null -> add(key, JsonNull.INSTANCE)
                is JsonElement -> add(key, value)
                is Int -> addProperty(key, value)
                is Long -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                else -> addProperty(key, value.toString())
            }
        }
    }

    private fun JsonObject.string(name: String): String = get(name).asString
    private fun JsonObject.nullableString(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString

    companion object {
        private const val STATUS = "status { name category }"
        private const val TRANSITIONS = "transitions { id name toState { status { name category } } }"
        private const val COMMENTS = "comments(offset: 0, limit: 50) { id content created profile { name } }"
        private const val PROJECTS_QUERY = """
            query BoscaIdeWorkOpsProjects {
              workOps { projects { all { id key name } } }
            }
        """
        private const val BUNDLE_QUERY = """
            query BoscaIdeWorkOps(${ '$' }projectId: UUID!) {
              workOps {
                tasks {
                  byProject(projectId: ${ '$' }projectId, offset: 0, limit: 200) {
                    id key summary descriptionMarkdown assigneeProfileId modifiedAt version
                    taskType { name } priority { name } assignee { name }
                    $STATUS $TRANSITIONS $COMMENTS
                    history(offset: 0, limit: 20) { changedAt changes { fieldKey } }
                    links {
                      linkType { inwardLabel outwardLabel }
                      sourceTask { id key }
                      targetTask { id key }
                    }
                  }
                  resolutions { id name }
                }
                specs {
                  byProject(projectId: ${ '$' }projectId, offset: 0, limit: 200) {
                    id key metadataId modifiedAt version owner { name }
                    metadata { id version name document { title content } }
                    $STATUS $TRANSITIONS $COMMENTS
                    history(offset: 0, limit: 20) { changedAt changes { fieldKey } }
                    requirements(offset: 0, limit: 200) {
                      id key metadataId taskId version priority { name } assignee { name } $STATUS
                      task { id version $TRANSITIONS }
                    }
                  }
                }
                links { linkTypes { id name outwardLabel } }
              }
            }
        """
        private const val DOCUMENT_QUERY = """
            query BoscaIdeWorkOpsDocument(${ '$' }id: UUID!) {
              content { metadata(id: ${ '$' }id) { id version document { title content } } }
            }
        """
        private const val CREATE_TASK = "mutation BoscaIdeCreateTask(${'$'}input: CreateWorkOpsTaskInput!) { workOps { tasks { create(input: ${'$'}input) { id } } } }"
        private const val UPDATE_TASK = "mutation BoscaIdeUpdateTask(${'$'}id: UUID!, ${'$'}input: UpdateWorkOpsTaskInput!) { workOps { tasks { update(id: ${'$'}id, input: ${'$'}input) { id version } } } }"
        private const val TRANSITION_TASK = "mutation BoscaIdeTransitionTask(${'$'}id: UUID!, ${'$'}transitionId: UUID!, ${'$'}version: Long!, ${'$'}resolutionId: UUID, ${'$'}comment: String) { workOps { tasks { transition(id: ${'$'}id, transitionId: ${'$'}transitionId, expectedVersion: ${'$'}version, resolutionId: ${'$'}resolutionId, comment: ${'$'}comment) { id version } } } }"
        private const val COMMENT_TASK = "mutation BoscaIdeCommentTask(${'$'}id: UUID!, ${'$'}input: WorkOpsTaskCommentInput!) { workOps { taskComments { add(taskId: ${'$'}id, input: ${'$'}input) { id } } } }"
        private const val TASK_ID_QUERY = "query BoscaIdeTaskId(${'$'}key: String!) { workOps { tasks { taskByKey(key: ${'$'}key) { id } } } }"
        private const val LINK_TASK = "mutation BoscaIdeLinkTask(${'$'}input: WorkOpsTaskLinkInput!) { workOps { links { link(input: ${'$'}input) { id } } } }"
        private const val CREATE_SPEC = "mutation BoscaIdeCreateSpec(${'$'}input: CreateWorkOpsSpecInput!) { workOps { specs { create(input: ${'$'}input) { id } } } }"
        private const val TRANSITION_SPEC = "mutation BoscaIdeTransitionSpec(${'$'}id: UUID!, ${'$'}transitionId: UUID!, ${'$'}version: Long!, ${'$'}resolutionId: UUID) { workOps { specs { transition(id: ${'$'}id, transitionId: ${'$'}transitionId, expectedVersion: ${'$'}version, resolutionId: ${'$'}resolutionId) { id version } } } }"
        private const val COMMENT_SPEC = "mutation BoscaIdeCommentSpec(${'$'}id: UUID!, ${'$'}input: WorkOpsSpecCommentInput!) { workOps { specComments { add(specId: ${'$'}id, input: ${'$'}input) { id } } } }"
        private const val CREATE_REQUIREMENT = "mutation BoscaIdeCreateRequirement(${'$'}input: CreateWorkOpsRequirementInput!) { workOps { requirements { create(input: ${'$'}input) { id } } } }"
        private const val COMMENT_REQUIREMENT = "mutation BoscaIdeCommentRequirement(${'$'}id: UUID!, ${'$'}input: WorkOpsRequirementCommentInput!) { workOps { requirementComments { add(requirementId: ${'$'}id, input: ${'$'}input) { id } } } }"
        private const val SAVE_DOCUMENT = "mutation BoscaIdeSaveWorkOpsDocument(${'$'}id: UUID!, ${'$'}version: Int!, ${'$'}title: String!, ${'$'}markdown: String!) { content { metadata { setMetadataMarkdown(id: ${'$'}id, version: ${'$'}version, title: ${'$'}title, markdown: ${'$'}markdown, collaborationSync: RESET) } } }"
        private const val RENAME_DOCUMENT = "mutation BoscaIdeRenameWorkOpsDocument(${'$'}id: UUID!, ${'$'}title: String!) { content { metadata { setMetadataName(id: ${'$'}id, name: ${'$'}title) { id } } } }"
    }
}
