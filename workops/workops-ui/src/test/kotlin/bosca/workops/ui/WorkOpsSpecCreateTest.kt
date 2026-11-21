package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsSpecCreateTest {
    private val choices = Json.parseToJsonElement("""{"workOps":{"projects":{"all":[
      {"id":"archived","key":"OLD","name":"Archived project","archivedAt":"2026-11-14T10:00:00Z"},
      {"id":"project","key":"UI","name":"UI project","archivedAt":null}
    ]}}}""")
    private val parent = Json.parseToJsonElement("""{"workOps":{"specs":{"spec":{"id":"parent","metadataId":"parent-document","metadata":{"name":"Parent specification"}}}}}""")
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })

    @Test fun `creation uses the aggregate and retains rejected name project and parent drafts`() = runTest {
        val page = WorkOpsSpecCreateModel(parentId = "parent")
        page.open(context { operation, _ -> when (operation) {
            "WorkOpsSpecCreateChoices" -> choices
            "WorkOpsSpecChoice" -> parent
            else -> error(operation)
        } })
        assertEquals("project", page.projectId); assertEquals(1, page.projects.size)
        assertEquals("Parent specification", page.parentName)
        page.create(" Authored specification ", "project", "parent", context { operation, variables -> when (operation) {
            "WorkOpsSpecChoice" -> parent
            "WorkOpsCreateSpec" -> {
                val input = variables.getValue("input").jsonObject
                assertEquals("Authored specification", input.getValue("name").jsonPrimitive.content)
                assertEquals("project", input.getValue("projectId").jsonPrimitive.content)
                assertEquals("parent", input.getValue("parentSpecId").jsonPrimitive.content)
                assertFalse("metadataId" in input)
                error("Creation rejected")
            }
            else -> error(operation)
        } })
        assertTrue(page.feedback.failed); assertTrue(page.creating)
        assertEquals(" Authored specification ", page.nameDraft); assertEquals("parent", page.parentId)
    }

    @Test fun `choice reload retains unsent values and an explicitly cleared project`() = runTest {
        val page = WorkOpsSpecCreateModel()
        val ctx = context { operation, _ -> when (operation) {
            "WorkOpsSpecCreateChoices" -> choices
            "WorkOpsSpecChoice" -> parent
            else -> error(operation)
        } }
        page.open(ctx)
        page.reloadChoices("Retained draft", "", "parent", ctx)
        assertFalse(page.feedback.failed); assertEquals("", page.projectId)
        assertEquals("Retained draft", page.nameDraft); assertEquals("Parent specification", page.parentName)
    }

    @Test fun `unavailable parents stop creation before any metadata or specification write`() = runTest {
        val page = WorkOpsSpecCreateModel()
        page.open(context { _, _ -> choices })
        page.create("Draft", "project", "hidden-parent", context { operation, _ ->
            assertEquals("WorkOpsSpecChoice", operation)
            Json.parseToJsonElement("""{"workOps":{"specs":{"spec":null}}}""")
        })
        assertTrue(page.feedback.failed); assertTrue(page.creating)
        assertEquals("Draft", page.nameDraft); assertEquals("hidden-parent", page.parentId)
        assertEquals("", page.createdId)
    }
}
