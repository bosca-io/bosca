package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsPlanningTest {
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private val original = WorkOpsPlanningRow("entity", "PLAN", "Planning item", "Description", "private-owner", "", "parent", "Parent unavailable",
        modified = "2026-11-14T00:00:00Z", version = 5, startDate = "2026-12-01T09:30:00Z", targetDate = "2027-02-01T17:00:00Z",
        defaultTaskTypeSchemeId = "custom-scheme", taskCreationFormSchemaKey = "custom.task-intake")
    private val programChoices = json("""{"workOps":{"programs":{"all":[{"id":"destination","key":"NEXT","name":"Destination program","archivedAt":null},{"id":"archived","key":"OLD","name":"Archived program","archivedAt":"2026-01-01T00:00:00Z"}]}}}""")

    @Test fun `project edits retain private owner immutable hierarchy inspected version and independent task configuration`() = runTest {
        val page = WorkOpsPlanningModel("project", rows = listOf(original)).apply { edit(original.id) }
        page.rows = listOf(original.copy(version = 8))
        page.save("FORGED", "New project name", "Kept description", original.ownerId, "other-parent", context { operation, variables ->
            assertEquals("WorkOpsUpdateProject", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals("PLAN", input.getValue("key").jsonPrimitive.content)
            assertEquals("parent", input.getValue("programId").jsonPrimitive.content)
            assertEquals("private-owner", input.getValue("ownerProfileId").jsonPrimitive.content)
            assertEquals("custom-scheme", input.getValue("defaultTaskTypeSchemeId").jsonPrimitive.content)
            assertEquals("custom.task-intake", input.getValue("taskCreationFormSchemaKey").jsonPrimitive.content)
            assertEquals(5L, variables.getValue("version").jsonPrimitive.long)
            error("A newer project was saved")
        })
        assertTrue(page.feedback.failed); assertTrue(page.editing); assertEquals("New project name", page.draft.name)
        assertEquals("Profile unavailable", page.draft.ownerLabel); assertEquals(5L, page.draft.version)
    }

    @Test fun `program name edits preserve precise dates and the inaccessible current portfolio`() = runTest {
        val page = WorkOpsPlanningModel("program", rows = listOf(original)).apply { edit(original.id) }
        page.save("PLAN", "Edited program", "", original.ownerId, "", context { operation, variables ->
            assertEquals("WorkOpsUpdateProgram", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(original.startDate, input.getValue("startDate").jsonPrimitive.content)
            assertEquals(original.targetDate, input.getValue("targetDate").jsonPrimitive.content)
            assertEquals(original.parentId, input.getValue("portfolioId").jsonPrimitive.content)
            error("Update rejected")
        })
        assertTrue(page.editing); assertEquals("Edited program", page.draft.name)
    }

    @Test fun `owner changes are revalidated after a rejected write and retain the original private identity`() = runTest {
        val page = WorkOpsPlanningModel("portfolio", rows = listOf(original)).apply { edit(original.id) }
        var lookups = 0
        repeat(2) {
            page.save("PLAN", "Name", "Draft", "new-owner", "", context { operation, _ -> when (operation) {
                "WorkOpsTaskProfile" -> { lookups++; json("""{"profiles":{"profile":{"id":"new-owner","name":"New owner"}}}""") }
                "WorkOpsUpdatePortfolio" -> error("Permission denied")
                else -> error(operation)
            } })
        }
        assertEquals(2, lookups); assertEquals(original.ownerId, page.draft.originalOwnerId)
        assertEquals("new-owner", page.draft.ownerId); assertTrue(page.feedback.failed)
    }

    @Test fun `program lists use authorized portfolio roots for labels and keep archive tabs and offset pages`() = runTest {
        val page = WorkOpsPlanningModel("program", tab = "all", offset = 25)
        page.reload(context { operation, _ -> when (operation) {
            "WorkOpsPlanningPortfolioChoices" -> json("""{"workOps":{"portfolios":{"all":[]}}}""")
            "WorkOpsPlanningPortfolioName" -> json("""{"workOps":{"portfolios":{"portfolio":null}}}""")
            "WorkOpsPlanningPrograms" -> buildJsonObject { putJsonObject("workOps") { putJsonObject("programs") { putJsonArray("all") {
                repeat(51) { addJsonObject {
                    put("id", "program-$it"); put("key", "P$it"); put("name", "Program $it"); put("description", JsonNull)
                    put("ownerProfileId", "private-owner"); put("owner", JsonNull); putJsonObject("portfolio") { put("id", "private-parent") }
                    put("startDate", JsonNull); put("targetDate", JsonNull); put("archivedAt", JsonNull)
                    put("modifiedAt", original.modified); put("version", 1)
                } }
            } } } }
            else -> error(operation)
        } })
        assertEquals(25, page.rows.size); assertEquals("program-25", page.rows.first().id)
        assertEquals("Portfolio unavailable", page.rows.first().parentName)
        assertEquals("/workops/programs?tab=all&offset=50", page.nextHref)
        assertEquals("/workops/programs?tab=all&offset=0", page.previousHref)
        assertEquals("/workops/programs?tab=archived", page.tabs.last().href)
    }

    @Test fun `archive confirmation retains the inspected row when a newer list is loaded`() = runTest {
        val page = WorkOpsPlanningModel("portfolio", rows = listOf(original)).apply { beginArchive(original.id) }
        page.rows = listOf(original.copy(version = 9))
        page.archive(false, context { operation, _ -> error(operation) })
        assertEquals("Confirm this change.", page.feedback.message)
        page.archive(true, context { operation, variables ->
            assertEquals("WorkOpsArchivePortfolio", operation); assertEquals(5L, variables.getValue("version").jsonPrimitive.long)
            error("Archive rejected")
        })
        assertTrue(page.feedback.failed); assertEquals(original, page.archiveTarget)
        page.cancelArchive(); assertNull(page.archiveTarget)
    }

    @Test fun `project moves use the distinct move API and retain denied destination and inspected version`() = runTest {
        val page = WorkOpsPlanningModel("project", rows = listOf(original))
        page.beginMove(original.id, context { operation, _ -> assertEquals("WorkOpsPlanningProgramChoices", operation); programChoices })
        assertEquals(listOf("destination"), page.moveChoices.map { it.id })
        page.move("archived", context { operation, _ -> error(operation) })
        assertEquals("Choose another available active program.", page.feedback.message)
        page.move("destination", context { operation, variables ->
            assertEquals("WorkOpsMoveProject", operation)
            assertEquals("destination", variables.getValue("program").jsonPrimitive.content)
            assertEquals(5L, variables.getValue("version").jsonPrimitive.long)
            error("Unauthorized access")
        })
        assertEquals("destination", page.moveProgramId); assertNotNull(page.moveTarget); assertTrue(page.feedback.failed)
    }

    @Test fun `new hierarchy drafts validate keys and preserve form values while reloading choices`() = runTest {
        val page = WorkOpsPlanningModel("project")
        val ctx = context { operation, _ -> when (operation) {
            "WorkOpsPlanningProgramChoices" -> programChoices
            "WorkOpsTaskProfile" -> json("""{"profiles":{"profile":{"id":"owner","name":"Chosen owner"}}}""")
            else -> error(operation)
        } }
        page.open(ctx)
        assertEquals("destination", page.draft.parentId)
        page.refreshChoices("new", "Draft project", "Full description", "owner", "destination", ctx)
        assertEquals("NEW", page.draft.key); assertEquals("Full description", page.draft.description)
        assertEquals("Chosen owner", page.draft.ownerLabel)
        page.save("!", "Name", "Description", "owner", "destination", context { operation, _ -> error(operation) })
        assertTrue(page.feedback.failed); assertEquals("!", page.draft.key)
        assertFailsWith<CancellationException> { page.retry(context { _, _ -> throw CancellationException("left list") }) }
    }

    @Test fun `a failed new-form lookup cannot reuse the previous edited entity`() = runTest {
        val page = WorkOpsPlanningModel("program", rows = listOf(original)).apply { edit(original.id); cancelEdit() }
        page.open(context { _, _ -> error("Service unavailable") })
        assertTrue(page.editing); assertTrue(page.feedback.failed); assertEquals("", page.draft.id)
        assertEquals("", page.draft.ownerId); assertEquals("", page.draft.key)
    }
}
