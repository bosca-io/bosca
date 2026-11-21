package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.withRenderContext
import bosca.workops.ui.graphql.WorkOpsRequirementDetail
import bosca.workops.ui.graphql.WorkOpsRequirementParent
import bosca.workops.ui.graphql.WorkOpsStatusCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsRequirementTest {
    private val id = "10000000-0000-0000-0000-000000000001"
    private val detail = Json.parseToJsonElement("""{
      "profiles":{"current":[{"id":"me","name":"Me"}]},"workOps":{
        "requirements":{"requirement":{"id":"$id","metadataId":"metadata","parentType":"TASK","parentId":"parent","version":7,
          "createdAt":"2026-11-14T10:00:00Z","modifiedAt":"2026-11-14T10:00:00Z",
          "status":{"name":"To Do","category":"TODO"},"priority":{"id":"priority","name":"Medium","colorHex":"#91a6f6"},
          "assigneeProfileId":"private-assignee","assignee":null,
          "task":{"id":"lifecycle-task","summary":"Requirement body","version":19,"status":{"name":"To Do","category":"TODO"},
            "transitions":[{"id":"done","name":"Resolve","toState":{"status":{"category":"DONE"}}}]},"history":[]}},
        "requirementComments":{"forRequirement":[]},"tasks":{"priorities":[{"id":"priority","name":"Medium"}],"resolutions":[{"id":"resolution","name":"Done"}]},"statuses":{"all":[{"id":"done","name":"Done"}]}
      }}""")
    private val document = Json.parseToJsonElement("""{
      "profiles":{"current":[{"id":"me","name":"Me"}]},"content":{"editable":["metadata"],"metadata":{"id":"metadata","version":1,"name":"Customer export",
        "document":{"title":"Body title","content":{"document":{"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Body title"}]},{"type":"paragraph"}]}},"template":null}}}
    }""")
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun loaded(operation: String): JsonElement = when (operation) {
        "WorkOpsRequirementDetail" -> detail
        "WorkOpsDocumentMetadata" -> document
        "WorkOpsRequirementParentTask" -> Json.parseToJsonElement("""{"workOps":{"tasks":{"task":{"summary":"Parent task"}}}}""")
        else -> error("Unexpected $operation")
    }
    private fun model() = WorkOpsRequirementDetailModel(id, data = WorkOpsRequirementDetail.decodeData(detail))

    @Test fun `requirement edits validate current priorities and newly selected assignees before updating`() = runTest {
        val page = model().apply { edit() }
        val noWrites = context { _, _ -> throw AssertionError("Invalid requirement cannot write") }
        page.save("unknown", "private-assignee", noWrites)
        assertEquals("Choose a priority.", page.feedback.message)
        page.save("priority", "unknown", context { operation, _ ->
            assertEquals("WorkOpsTaskProfile", operation)
            Json.parseToJsonElement("""{"profiles":{"profile":null}}""")
        })
        assertEquals("The selected assignee is unavailable.", page.feedback.message)
        page.save("priority", "person", context { operation, variables ->
            if (operation == "WorkOpsTaskProfile") Json.parseToJsonElement("""{"profiles":{"profile":{"id":"person","name":"Person"}}}""")
            else {
                assertEquals("WorkOpsUpdateRequirement", operation)
                assertEquals(JsonPrimitive(7), variables.getValue("input").jsonObject["expectedVersion"])
                error("Newer requirement")
            }
        })
        assertEquals("Newer requirement", page.feedback.message)
        assertEquals("person", page.assigneeDraft)
        assertTrue(page.editing)
    }

    @Test fun `reload resolves historical assignments once and retains a missing specification parent fallback`() = runTest {
        val history = """[{"id":"history","changedAt":"2026-11-17","changedByProfileId":"actor","changes":[
          {"fieldKey":"assignee_profile_id","fromValue":"old-assignee","toValue":null},
          {"fieldKey":"assignee_profile_id","fromValue":null,"toValue":"old-assignee"},
          {"fieldKey":"summary","fromValue":"Old title","toValue":"New title"}]}]"""
        val changed = Json.parseToJsonElement(detail.toString().replace("\"history\":[]", "\"history\":$history")
            .replace("\"parentType\":\"TASK\"", "\"parentType\":\"SPEC\"")
            .replace("\"assignee\":null", "\"assignee\":{\"id\":\"assignee\",\"name\":\"Assignee\"}"))
        val requested = mutableListOf<String>()
        val page = WorkOpsRequirementDetailModel(id, document = WorkOpsDocumentModel("previous-document"))
        page.retry(context { operation, variables -> when (operation) {
            "WorkOpsRequirementDetail" -> changed
            "WorkOpsRequirementParentSpec" -> Json.parseToJsonElement("""{"workOps":{"specs":{"spec":{"metadata":null}}}}""")
            "WorkOpsTaskProfile" -> {
                val profile = variables.getValue("id").jsonPrimitive.content
                requested += profile
                Json.parseToJsonElement(if (profile == "old-assignee") """{"profiles":{"profile":{"id":"old-assignee","name":"Previous assignee"}}}""" else """{"profiles":{"profile":null}}""")
            }
            else -> loaded(operation)
        } })
        assertFalse(page.feedback.failed, page.feedback.message)
        assertEquals(listOf("actor", "old-assignee"), requested)
        assertEquals("Previous assignee", page.profileName("old-assignee"))
        assertEquals("Profile unavailable", page.profileName("actor"))
        assertEquals("Assignee", page.profileName("assignee"))
        assertEquals("Parent specification unavailable", page.parentName)
        assertEquals("metadata", page.document?.metadataId)
    }

    @Test fun `removed requirements clear the stale Content editor without reading its parent`() = runTest {
        val page = WorkOpsRequirementDetailModel(id, document = WorkOpsDocumentModel("metadata"))
        val ctx = context { operation, _ ->
            assertEquals("WorkOpsRequirementDetail", operation)
            Json.parseToJsonElement("""{"profiles":{"current":[]},"workOps":{"requirements":{"requirement":null},"requirementComments":{"forRequirement":[]},"tasks":{"priorities":[],"resolutions":[]},"statuses":{"all":[]}}}""")
        }
        page.retry(ctx)
        assertFalse(page.feedback.failed)
        assertTrue(ctx.notFound)
        assertNull(page.document)
        assertFalse(page.hasNext)
        assertEquals("", page.parentHref)
    }

    @Test fun `loaded requirement state retains independent Content and lifecycle task versions`() {
        val page = assertWorkOpsSerialization(WorkOpsRequirementDetailModel.serializer(), """{"id":"$id"}""", """{
          "id":"$id","data":$detail,"document":{"metadataId":"metadata","data":$document}
        }""")
        assertEquals("Customer export", page.title)
        assertEquals("metadata.1", page.document?.room)
        assertEquals(19L, page.requirement?.task?.version)
        assertEquals(7L, page.requirement?.version)
    }

    @Test fun `requirement history handles absent documents parents catalogs and structured values explicitly`() {
        val page = model()
        page.profileNames = mapOf("known" to "Known person")
        assertEquals("System", page.profileName(null))
        assertEquals("Known person", page.historyValue("assignee_profile_id", JsonPrimitive("known")))
        assertEquals("Unset", page.historyValue("priority_id", null))
        assertEquals("Requirement", page.historyValue("metadata_id", JsonPrimitive("metadata")))
        assertEquals("Parent unavailable", page.historyValue("parent_id", JsonPrimitive("parent")))
        page.parentName = "Parent title"
        assertEquals("Parent title", page.historyValue("parent_id", JsonPrimitive("parent")))
        assertEquals("Status unavailable", page.historyValue("status_id", JsonPrimitive("unknown")))
        assertEquals("Priority unavailable", page.historyValue("priority_id", JsonPrimitive("unknown")))
        assertEquals("[false,0]", page.historyValue("custom", Json.parseToJsonElement("[false,0]")))
        for ((key, label) in mapOf("created" to "Requirement", "task_id" to "Lifecycle task", "parent_id" to "Parent",
            "metadata_id" to "Document", "status_id" to "Status", "priority_id" to "Priority", "assignee_profile_id" to "Assignee", "custom_field" to "custom field")) {
            assertEquals(label, page.historyField(key))
        }
        page.data = null
        assertEquals("Status unavailable", page.historyValue("status_id", JsonPrimitive("done")))
        assertEquals("Priority unavailable", page.historyValue("priority_id", JsonPrimitive("priority")))
        assertEquals("Task unavailable", page.lifecycleTaskName)
        assertEquals("Requirement unavailable", page.title)
        page.feedback.failed = true
        assertEquals("Could not load requirement", page.title)
    }

    @Test fun `history uses product names without exposing requirement tracking identifiers`() {
        val page = model()
        assertEquals("Created", page.historyValue("created", JsonPrimitive("TEST-REQ-4")))
        assertEquals("Done", page.historyValue("status_id", JsonPrimitive("done")))
        assertEquals("Medium", page.historyValue("priority_id", JsonPrimitive("priority")))
        assertEquals("Requirement body", page.historyValue("task_id", JsonPrimitive("lifecycle-task")))
        assertEquals("Task unavailable", page.historyValue("task_id", JsonPrimitive("previous-task")))
        assertEquals("Previous parent", page.historyValue("parent_id", JsonPrimitive("previous-parent")))
        assertEquals("Previous document", page.historyValue("metadata_id", JsonPrimitive("previous-document")))
        assertEquals("Profile unavailable", page.historyValue("assignee_profile_id", JsonPrimitive("hidden")))
        assertEquals("Unset", page.historyValue("assignee_profile_id", JsonNull))
        page.data = WorkOpsRequirementDetail.decodeData(Json.parseToJsonElement(detail.toString().replace("Requirement body", "REQ TEST-REQ-4")))
        assertEquals("Requirement", page.lifecycleTaskName)
        assertEquals("Requirement", page.historyValue("task_id", JsonPrimitive("lifecycle-task")))
    }

    @Test fun `key deep links resolve and retain independent metadata name document title and parent`() = runTest {
        val ctx = context { operation, variables ->
            if (operation == "WorkOpsRequirementIdByKey") {
                assertEquals("TEST-REQ-4", variables.getValue("key").jsonPrimitive.content)
                Json.parseToJsonElement("""{"workOps":{"requirements":{"requirementByKey":{"id":"$id"}}}}""")
            } else loaded(operation)
        }
        val page = withRenderContext(RenderContext(gql = ctx.gql, params = mapOf("id" to "test-req-4"), query = mapOf("tab" to "history", "offset" to "-9"))) { workOpsRequirementDetail() }
        assertFalse(page.feedback.failed)
        assertEquals(id, page.id); assertEquals("Customer export", page.title)
        assertEquals("Body title", page.document?.metadata?.document?.title)
        assertEquals("Parent task", page.parentName); assertEquals("/workops/tasks/parent", page.parentHref)
        assertEquals("Profile unavailable", page.assigneeLabel)
        assertEquals("history", page.tab); assertEquals(0L, page.offset)
        assertFalse(page.title.contains("REQ-"))
    }

    @Test fun `requirement edits retain a hidden assignee and the original version on rejection`() = runTest {
        val page = model().apply { edit() }
        page.save("priority", "private-assignee", context { operation, variables ->
            assertEquals("WorkOpsUpdateRequirement", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(7L, input.getValue("expectedVersion").jsonPrimitive.long)
            assertEquals("private-assignee", input.getValue("assigneeProfileId").jsonPrimitive.content)
            assertFalse(input.getValue("clearAssignee").jsonPrimitive.boolean)
            error("A newer requirement was saved")
        })
        assertTrue(page.feedback.failed); assertTrue(page.editing)
        assertEquals("private-assignee", page.assigneeDraft); assertEquals(7L, page.editVersion)
        page.save("priority", "", context { operation, variables ->
            if (operation == "WorkOpsUpdateRequirement") {
                assertTrue(variables.getValue("input").jsonObject.getValue("clearAssignee").jsonPrimitive.boolean)
                Json.parseToJsonElement("""{"workOps":{"requirements":{"update":{"id":"$id"}}}}""")
            } else loaded(operation)
        })
        assertFalse(page.feedback.failed); assertFalse(page.editing)
    }

    @Test fun `lifecycle transitions require a resolution and use the linked task version`() = runTest {
        val page = model()
        val noRequests = context { operation, _ -> error("Unexpected $operation") }
        page.beginTransition("done", noRequests)
        page.completeTransition("", noRequests)
        assertTrue(page.feedback.failed); assertEquals("done", page.pendingTransition)
        page.completeTransition("resolution", context { operation, variables ->
            if (operation == "WorkOpsTransitionTask") {
                assertEquals("lifecycle-task", variables.getValue("id").jsonPrimitive.content)
                assertEquals(19L, variables.getValue("expectedVersion").jsonPrimitive.long)
                assertEquals("resolution", variables.getValue("resolutionId").jsonPrimitive.content)
                Json.parseToJsonElement("""{"workOps":{"tasks":{"transition":{"id":"lifecycle-task"}}}}""")
            } else loaded(operation)
        })
        assertFalse(page.feedback.failed); assertEquals("", page.pendingTransition)
    }

    @Test fun `name edits call Content without replacing the rich body or task summary`() = runTest {
        val page = model()
        page.reload(context { operation, _ -> loaded(operation) })
        page.beginRename()
        page.rename("Renamed export", context { operation, variables ->
            if (operation == "WorkOpsRenameRequirement") {
                assertEquals("metadata", variables.getValue("id").jsonPrimitive.content)
                val input = variables.getValue("input").jsonObject
                assertEquals("Renamed export", input.getValue("name").jsonPrimitive.content)
                assertFalse("document" in input)
                Json.parseToJsonElement("""{"content":{"metadata":{"edit":{"id":"metadata"}}}}""")
            } else loaded(operation)
        })
        assertFalse(page.feedback.failed); assertFalse(page.renaming)
        assertEquals("Body title", page.document?.metadata?.document?.title)
        page.document?.data = null
        page.beginRename(); page.rename("Denied name", context { operation, _ -> error("Unexpected $operation") })
        assertTrue(page.feedback.failed); assertTrue(page.renaming); assertEquals("Denied name", page.nameDraft)
    }

    @Test fun `comments retain rejected drafts and do not swallow request cancellation`() = runTest {
        val page = model()
        page.comment("Retained comment", context { _, _ -> error("Comment rejected") })
        assertTrue(page.feedback.failed); assertEquals("Retained comment", page.commentDraft); assertEquals(0L, page.commentRevision)
        assertFailsWith<CancellationException> { page.comment("Cancelled comment", context { _, _ -> throw CancellationException("closed") }) }
        page.comment("Accepted comment", context { operation, variables ->
            if (operation == "WorkOpsCommentOnRequirement") {
                assertEquals("USER", variables.getValue("input").jsonObject.getValue("visibility").jsonPrimitive.content)
                Json.parseToJsonElement("""{"workOps":{"requirementComments":{"add":{"id":1}}}}""")
            } else loaded(operation)
        })
        assertFalse(page.feedback.failed); assertEquals("", page.commentDraft); assertEquals(1L, page.commentRevision)
    }

    private fun listData(count: Int = 1) = buildJsonObject {
        putJsonObject("workOps") { putJsonObject("requirements") { putJsonArray("byTask") {
            repeat(count) { index -> addJsonObject {
                put("id", "requirement-$index"); put("metadataId", "metadata-$index"); put("version", 7); put("sortOrder", index)
                putJsonObject("status") { put("name", "Done"); put("category", "DONE") }
                putJsonObject("priority") { put("name", "Medium"); put("colorHex", "#91a6f6") }
                put("assigneeProfileId", "private-assignee"); put("assignee", JsonNull)
            } }
        } } }
    }

    private fun requirementRows(count: Int = 1) = List(count) { index ->
        WorkOpsRequirementRow("requirement-$index", "metadata-$index", 7, index, "Done", WorkOpsStatusCategory.DONE,
            "Medium", "#91a6f6", "private-assignee", null)
    }

    @Test fun `task requirement pages use a sentinel without counting it in visible progress`() {
        val page = WorkOpsRequirementsModel("parent", "/workops/tasks/parent", 25,
            data = requirementRows(26))
        assertEquals(25, page.rows.size); assertEquals(25, page.doneCount)
        assertEquals("/workops/tasks/parent?requirementsOffset=0", page.previousHref)
        assertEquals("/workops/tasks/parent?requirementsOffset=50", page.nextHref)
        val activity = page.copy(parentHref = "/workops/tasks/parent?tab=history&offset=25")
        assertEquals("/workops/tasks/parent?tab=history&offset=25&requirementsOffset=0", activity.previousHref)
        assertEquals("/workops/tasks/parent?tab=history&offset=25&requirementsOffset=50", activity.nextHref)
    }

    @Test fun `creation retains a direct result link when the new requirement is outside the current page`() = runTest {
        val page = WorkOpsRequirementsModel("parent", "/workops/tasks/parent").apply { beginCreate() }
        page.create("New requirement", context { operation, _ -> when (operation) {
            "WorkOpsCreateRequirement" -> Json.parseToJsonElement("""{"workOps":{"requirements":{"create":{"id":"created"}}}}""")
            "WorkOpsRequirementsForTask" -> listData(0)
            else -> error(operation)
        } })
        assertFalse(page.feedback.failed); assertFalse(page.creating)
        assertEquals("created", page.createdId); assertTrue(page.rows.isEmpty())
    }

    @Test fun `task requirement creation uses the aggregate and retains its name when rejected`() = runTest {
        val page = WorkOpsRequirementsModel("parent", "/workops/tasks/parent").apply { beginCreate() }
        page.create("Customer export", context { operation, variables ->
            assertEquals("WorkOpsCreateRequirement", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals("Customer export", input.getValue("name").jsonPrimitive.content)
            assertEquals("TASK", input.getValue("parentType").jsonPrimitive.content)
            assertEquals("parent", input.getValue("parentId").jsonPrimitive.content)
            assertTrue(input["metadataId"] == null || input["metadataId"] == JsonNull)
            error("Create rejected")
        })
        assertTrue(page.feedback.failed); assertTrue(page.creating); assertEquals("Customer export", page.nameDraft)
    }

    @Test fun `task requirement assignment preserves inaccessible identity and deletion requires a matching version`() = runTest {
        val page = WorkOpsRequirementsModel("parent", "/workops/tasks/parent", data = requirementRows())
        page.editAssignee("requirement-0")
        assertEquals("Profile unavailable", page.assigneeName)
        page.assign("private-assignee", context { operation, variables ->
            assertEquals("WorkOpsUpdateRequirement", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(7L, input.getValue("expectedVersion").jsonPrimitive.long)
            assertEquals("private-assignee", input.getValue("assigneeProfileId").jsonPrimitive.content)
            error("Assignment rejected")
        })
        assertTrue(page.feedback.failed); assertEquals("requirement-0", page.editingId)
        page.delete("requirement-0", 6, true, context { operation, _ -> error("Unexpected $operation") })
        assertTrue(page.feedback.failed)
        page.delete("requirement-0", 7, false, context { operation, _ -> error("Unexpected $operation") })
        assertTrue(page.feedback.failed)
        page.delete("requirement-0", 7, true, context { operation, variables ->
            when (operation) {
                "WorkOpsDeleteRequirement" -> {
                    assertEquals(7L, variables.getValue("expectedVersion").jsonPrimitive.long)
                    Json.parseToJsonElement("""{"workOps":{"requirements":{"softDelete":{"id":"requirement-0"}}}}""")
                }
                "WorkOpsRequirementsForTask" -> listData(0)
                else -> error(operation)
            }
        })
        assertFalse(page.feedback.failed); assertTrue(page.rows.isEmpty())
    }

    @Test fun `specification requirements reuse aggregate creation and authorized offset lookup`() = runTest {
        val page = WorkOpsRequirementsModel("spec-parent", "/workops/specs/spec-parent?tab=history&offset=25&childrenOffset=25",
            offset = 25, parentType = WorkOpsRequirementParent.SPEC).apply { beginCreate() }
        page.create("Specification requirement", context { operation, variables -> when (operation) {
            "WorkOpsCreateRequirement" -> {
                val input = variables.getValue("input").jsonObject
                assertEquals("SPEC", input.getValue("parentType").jsonPrimitive.content)
                assertEquals("spec-parent", input.getValue("parentId").jsonPrimitive.content)
                assertEquals("Specification requirement", input.getValue("name").jsonPrimitive.content)
                Json.parseToJsonElement("""{"workOps":{"requirements":{"create":{"id":"created"}}}}""")
            }
            "WorkOpsRequirementsForSpec" -> {
                assertEquals(25L, variables.getValue("offset").jsonPrimitive.long)
                assertEquals("spec-parent", variables.getValue("specId").jsonPrimitive.content)
                JsonObject(mapOf("workOps" to JsonObject(mapOf("requirements" to JsonObject(mapOf("bySpec" to listData(26).jsonObject
                    .getValue("workOps").jsonObject.getValue("requirements").jsonObject.getValue("byTask")))))))
            }
            "WorkOpsRequirementName" -> Json.parseToJsonElement("""{"content":{"metadata":{"name":"Specification requirement"}}}""")
            else -> error(operation)
        } })
        assertFalse(page.feedback.failed); assertFalse(page.creating); assertEquals(25, page.rows.size)
        assertEquals("Specification requirement", page.name("metadata-0"))
        assertEquals("/workops/specs/spec-parent?tab=history&offset=25&childrenOffset=25&requirementsOffset=50", page.nextHref)
    }
}
