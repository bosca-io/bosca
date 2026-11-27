package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.withRenderContext
import bosca.workops.ui.graphql.WorkOpsSpecDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsSpecDetailTest {
    private val id = "10000000-0000-0000-0000-000000000001"
    private val detail = Json.parseToJsonElement("""{
      "profiles":{"current":[{"id":"me","name":"Me"}]},"workOps":{
        "specs":{"spec":{"id":"$id","metadataId":"metadata","status":{"id":"todo","name":"To Do","category":"TODO"},
          "ownerProfileId":"private-owner","owner":null,"project":{"id":"project","name":"Project name"},"parentSpecId":"parent",
          "childCount":27,"childDoneCount":3,"requirementCount":1,"gitRepositoryId":null,"gitPath":null,"watcherProfileIds":[],
          "createdAt":"2026-11-14T10:00:00Z","modifiedAt":"2026-11-14T10:00:00Z","version":17,
          "transitions":[{"id":"resolve","name":"Resolve","toState":{"status":{"category":"DONE"}}}],"comments":[],"history":[]},"children":[]},
        "tasks":{"resolutions":[{"id":"resolution","name":"Done"}]},"statuses":{"all":[{"id":"done","name":"Done"}]}
      }}""")
    private val document = Json.parseToJsonElement("""{
      "profiles":{"current":[{"id":"me","name":"Me"}]},"content":{"editable":["metadata"],"metadata":{"id":"metadata","version":3,"name":"Customer export",
        "document":{"title":"Independent body title","content":{"document":{"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Independent body title"}]},{"type":"paragraph"}]}},"template":null}}}
    }""")
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun loaded(operation: String): JsonElement = when (operation) {
        "WorkOpsSpecDetail" -> detail
        "WorkOpsDocumentMetadata" -> document
        "WorkOpsSpecChoice" -> Json.parseToJsonElement("""{"workOps":{"specs":{"spec":{"id":"parent","metadataId":"parent-metadata","metadata":{"name":"Parent document"}}}}}""")
        else -> error("Unexpected $operation")
    }
    private fun model() = WorkOpsSpecDetailModel(id, data = WorkOpsSpecDetail.decodeData(detail))

    @Test fun `nonclosing specification transitions send no resolution and do not open a closing dialog`() = runTest {
        val nonclosing = Json.parseToJsonElement(detail.toString().replace("\"category\":\"DONE\"", "\"category\":\"IN_PROGRESS\""))
        val page = WorkOpsSpecDetailModel(id, data = WorkOpsSpecDetail.decodeData(nonclosing))
        var writes = 0
        page.beginTransition("resolve", context { operation, variables ->
            if (operation == "WorkOpsTransitionSpec") {
                writes++
                assertEquals(JsonNull, variables["resolutionId"] ?: JsonNull)
                assertEquals(JsonPrimitive(17), variables["expectedVersion"])
                Json.parseToJsonElement("""{"workOps":{"specs":{"transition":{"id":"$id"}}}}""")
            } else loaded(operation)
        })
        assertFalse(page.feedback.failed, page.feedback.message)
        assertEquals(1, writes)
        assertEquals("", page.pendingTransition)
        page.beginTransition("missing", context { _, _ -> throw AssertionError("Unknown transitions cannot write") })
        assertEquals("Reload the available transitions.", page.feedback.message)
    }

    @Test fun `reload resolves distinct historical owners while unavailable actors and parents stay unnamed`() = runTest {
        val history = """[{"id":"history","changedAt":"2026-11-17","changedByProfileId":"actor","changes":[
          {"fieldKey":"owner_profile_id","fromValue":"old-owner","toValue":null},
          {"fieldKey":"owner_profile_id","fromValue":null,"toValue":"old-owner"},
          {"fieldKey":"summary","fromValue":"Previous title","toValue":"Current title"}]}]"""
        val changed = Json.parseToJsonElement(detail.toString().replace("\"history\":[]", "\"history\":$history")
            .replace("\"owner\":null", "\"owner\":{\"id\":\"owner\",\"name\":\"Owner\"}"))
        val requested = mutableListOf<String>()
        val page = WorkOpsSpecDetailModel(id, document = WorkOpsDocumentModel("previous-document"))
        page.retry(context { operation, variables -> when (operation) {
            "WorkOpsSpecDetail" -> changed
            "WorkOpsSpecChoice" -> Json.parseToJsonElement("""{"workOps":{"specs":{"spec":null}}}""")
            "WorkOpsTaskProfile" -> {
                val profile = variables.getValue("id").jsonPrimitive.content
                requested += profile
                Json.parseToJsonElement(if (profile == "old-owner") """{"profiles":{"profile":{"id":"old-owner","name":"Previous owner"}}}""" else """{"profiles":{"profile":null}}""")
            }
            else -> loaded(operation)
        } })
        assertFalse(page.feedback.failed, page.feedback.message)
        assertEquals(listOf("actor", "old-owner"), requested)
        assertEquals("Previous owner", page.profileName("old-owner"))
        assertEquals("Profile unavailable", page.profileName("actor"))
        assertEquals("Owner", page.profileName("owner"))
        assertEquals("Specification unavailable", page.parentName)
        assertEquals("metadata", page.document?.metadataId)
    }

    @Test fun `removed specifications clear the stale editor and stop dependent reads`() = runTest {
        val page = WorkOpsSpecDetailModel(id, document = WorkOpsDocumentModel("metadata"))
        val ctx = context { operation, _ ->
            assertEquals("WorkOpsSpecDetail", operation)
            Json.parseToJsonElement("""{"profiles":{"current":[]},"workOps":{"specs":{"spec":null,"children":[]},"tasks":{"resolutions":[]},"statuses":{"all":[]}}}""")
        }
        page.retry(ctx)
        assertFalse(page.feedback.failed)
        assertTrue(ctx.notFound)
        assertNull(page.document)
        assertFalse(page.hasNext)
        assertEquals("", page.parentHref)
    }

    @Test fun `loaded specification state preserves its independent Content identity and body`() {
        val page = assertWorkOpsSerialization(WorkOpsSpecDetailModel.serializer(), """{"id":"$id"}""", """{
          "id":"$id","data":$detail,"document":{"metadataId":"metadata","data":$document}
        }""")
        assertEquals("Customer export", page.title)
        assertEquals("metadata.3", page.document?.room)
        assertEquals("Independent body title", page.document?.metadata?.document?.title)
    }

    @Test fun `specification history resolves current references and conceals unavailable historical identities`() {
        val page = model()
        page.profileNames = mapOf("known" to "Known person")
        assertEquals("System", page.profileName(null))
        assertEquals("Known person", page.historyValue("owner_profile_id", JsonPrimitive("known")))
        assertEquals("Unset", page.historyValue("owner_profile_id", null))
        assertEquals("Unset", page.historyValue("owner_profile_id", JsonNull))
        assertEquals("Created", page.historyValue("created", JsonPrimitive("internal-key")))
        assertEquals("Specification", page.historyValue("metadata_id", JsonPrimitive("metadata")))
        assertEquals("Previous document", page.historyValue("metadata_id", JsonPrimitive("old")))
        assertEquals("Specification unavailable", page.historyValue("parent_spec_id", JsonPrimitive("parent")))
        page.parentName = "Parent title"
        assertEquals("Parent title", page.historyValue("parent_spec_id", JsonPrimitive("parent")))
        assertEquals("Previous parent", page.historyValue("parent_spec_id", JsonPrimitive("old")))
        assertEquals("Project name", page.historyValue("project_id", JsonPrimitive("project")))
        assertEquals("Previous project", page.historyValue("project_id", JsonPrimitive("old")))
        assertEquals("Status unavailable", page.historyValue("status_id", JsonPrimitive("unknown")))
        assertEquals("Done", page.historyValue("status_id", JsonPrimitive("done")))
        for ((value, label) in listOf("added:reference" to "Added", "removed:reference" to "Removed", "reference" to "Changed")) {
            assertEquals(label, page.historyValue("context", JsonPrimitive(value)))
        }
        assertEquals("Generated tasks", page.historyValue("task_generation", JsonPrimitive("internal-key")))
        for (key in listOf("unknown_id", "unknown_ids")) assertEquals("Changed reference", page.historyValue(key, JsonPrimitive("hidden")))
        assertEquals("{\"enabled\":false}", page.historyValue("custom", Json.parseToJsonElement("""{"enabled":false}""")))
        for ((key, label) in mapOf("created" to "Specification", "owner_profile_id" to "Owner", "parent_spec_id" to "Parent specification",
            "metadata_id" to "Document", "status_id" to "Status", "project_id" to "Project", "context" to "Context", "task_generation" to "Tasks", "custom_field" to "custom field")) {
            assertEquals(label, page.historyField(key))
        }
        page.data = null
        assertEquals("Status unavailable", page.historyValue("status_id", JsonPrimitive("done")))
        assertEquals("Specification unavailable", page.title)
        page.feedback.failed = true
        assertEquals("Could not load specification", page.title)
    }

    @Test fun `key links use authorized roots and retain independent title body version and page state`() = runTest {
        val ctx = context { operation, variables -> when (operation) {
            "WorkOpsSpecIdByKey" -> {
                assertEquals("TEST-SPEC-4", variables.getValue("key").jsonPrimitive.content)
                Json.parseToJsonElement("""{"workOps":{"specs":{"specByKey":{"id":"$id"}}}}""")
            }
            "WorkOpsSpecDetail" -> {
                assertEquals(25L, variables.getValue("childrenOffset").jsonPrimitive.long)
                assertEquals(0L, variables.getValue("offset").jsonPrimitive.long)
                loaded(operation)
            }
            else -> loaded(operation)
        } }
        val page = withRenderContext(RenderContext(gql = ctx.gql, params = mapOf("id" to "test-spec-4"),
            query = mapOf("tab" to "history", "offset" to "-9", "childrenOffset" to "25", "requirementsOffset" to "50", "generationsOffset" to "75"))) { workOpsSpecDetail() }
        assertFalse(page.feedback.failed); assertEquals(id, page.id); assertEquals("Customer export", page.title)
        assertEquals("Independent body title", page.document?.metadata?.document?.title); assertEquals("metadata.3", page.document?.room)
        assertEquals("Parent document", page.parentName); assertEquals("/workops/specs/parent", page.parentHref)
        assertEquals("Profile unavailable", page.ownerLabel)
        assertTrue(page.previousChildrenHref.endsWith("tab=history&offset=0&childrenOffset=0&requirementsOffset=50&generationsOffset=75"))
        assertFalse(page.requirementsHref.contains("requirementsOffset"))
        assertTrue(page.tabs.first().href.endsWith("tab=comments&offset=0&childrenOffset=25&requirementsOffset=50&generationsOffset=75"))
        assertFalse(page.generationsHref.contains("generationsOffset"))
    }

    @Test fun `owner changes retain inaccessible current identity and optimistic version when rejected`() = runTest {
        val page = model().apply { editOwner() }
        page.saveOwner("private-owner", context { operation, variables ->
            assertEquals("WorkOpsUpdateSpec", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals("private-owner", input.getValue("ownerProfileId").jsonPrimitive.content)
            assertEquals(17L, input.getValue("expectedVersion").jsonPrimitive.long)
            error("A newer specification was saved")
        })
        assertTrue(page.feedback.failed); assertTrue(page.editingOwner); assertEquals(17L, page.editVersion)
        page.saveOwner("", context { operation, _ -> error("Unexpected $operation") })
        assertEquals("Choose an owner.", page.feedback.message)
        page.saveOwner("unavailable", context { operation, _ ->
            assertEquals("WorkOpsTaskProfile", operation); Json.parseToJsonElement("""{"profiles":{"profile":null}}""")
        })
        assertEquals("The selected owner is unavailable.", page.feedback.message); assertEquals("unavailable", page.ownerDraft)
    }

    @Test fun `specification resolution sends its own identity and captured version with explicit resolution`() = runTest {
        val page = model()
        page.beginTransition("resolve", context { operation, _ -> error("Unexpected $operation") })
        assertEquals("resolve", page.pendingTransition); assertEquals(17L, page.transitionVersion)
        page.completeTransition("resolution", context { operation, variables ->
            assertEquals("WorkOpsTransitionSpec", operation)
            assertEquals(id, variables.getValue("id").jsonPrimitive.content)
            assertEquals(17L, variables.getValue("expectedVersion").jsonPrimitive.long)
            assertEquals("resolution", variables.getValue("resolutionId").jsonPrimitive.content)
            error("Transition rejected")
        })
        assertTrue(page.feedback.failed); assertEquals("resolve", page.pendingTransition)
        page.completeTransition("resolution", context { operation, _ ->
            if (operation == "WorkOpsTransitionSpec") Json.parseToJsonElement("""{"workOps":{"specs":{"transition":{"id":"$id"}}}}""") else loaded(operation)
        })
        assertFalse(page.feedback.failed); assertEquals("", page.pendingTransition); assertEquals("Specification status updated.", page.feedback.message)
    }

    @Test fun `rename changes Content name without submitting a replacement rich body`() = runTest {
        val page = model(); page.reload(context { operation, _ -> loaded(operation) }); page.beginRename()
        page.rename("Human specification name", context { operation, variables ->
            if (operation == "WorkOpsRenameSpec") {
                assertEquals("metadata", variables.getValue("id").jsonPrimitive.content)
                val input = variables.getValue("input").jsonObject
                assertEquals("Human specification name", input.getValue("name").jsonPrimitive.content)
                assertFalse(input.containsKey("document")); error("Rename rejected")
            } else loaded(operation)
        })
        assertTrue(page.renaming); assertEquals("Human specification name", page.nameDraft)
        assertEquals("Independent body title", page.document?.metadata?.document?.title)
    }

    @Test fun `history resolves authorized names and hides specification tracking and reference identifiers`() {
        val page = model()
        assertEquals("Created", page.historyValue("created", JsonPrimitive("TEST-SPEC-4")))
        assertEquals("Done", page.historyValue("status_id", JsonPrimitive("done")))
        assertEquals("Profile unavailable", page.historyValue("owner_profile_id", JsonPrimitive("hidden-owner")))
        assertEquals("Project name", page.historyValue("project_id", JsonPrimitive("project")))
        assertEquals("Previous parent", page.historyValue("parent_spec_id", JsonPrimitive("previous-parent")))
        assertEquals("Changed reference", page.historyValue("context_id", JsonPrimitive("private-context")))
        assertEquals("Added", page.historyValue("context", JsonPrimitive("added:private-context")))
        assertEquals("Removed", page.historyValue("context", JsonPrimitive("removed:private-context")))
        assertEquals("Generated tasks", page.historyValue("task_generation", JsonPrimitive("generation-id")))
    }

    @Test fun `rejected comments retain draft and loading preserves cancellation`() = runTest {
        val page = model()
        page.comment("Authored comment", context { operation, variables ->
            assertEquals("WorkOpsCommentOnSpec", operation)
            assertEquals(id, variables.getValue("id").jsonPrimitive.content)
            assertEquals("Authored comment", variables.getValue("input").jsonObject.getValue("content").jsonPrimitive.content)
            error("Comment rejected")
        })
        assertTrue(page.feedback.failed); assertEquals("Authored comment", page.commentDraft); assertEquals(0L, page.commentRevision)
        assertFailsWith<CancellationException> { page.retry(context { _, _ -> throw CancellationException("closed") }) }
        val denied = context { operation, _ ->
            assertEquals("WorkOpsSpecDetail", operation)
            Json.parseToJsonElement("""{"profiles":{"current":[]},"workOps":{"specs":{"spec":null,"children":[]},"tasks":{"resolutions":[]},"statuses":{"all":[]}}}""")
        }
        page.reload(denied); assertNull(page.spec); assertNull(page.document); assertTrue(denied.notFound)
    }
}
