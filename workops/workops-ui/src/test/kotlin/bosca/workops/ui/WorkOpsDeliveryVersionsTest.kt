package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.OffsetDateTime
import kotlin.test.*

class WorkOpsDeliveryVersionsTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun data(field: String, value: JsonElement) = buildJsonObject {
        putJsonObject("workOps") { putJsonObject("versions") { put(field, value) } }
    }
    private fun version(id: String, sequence: Int) = json("""{"id":"$id","name":"Version $id","description":"Notes","released":false,"archived":false,"startDate":"2026-12-01T00:00:00Z","releaseDate":"2026-12-15T00:00:00Z","sequenceNumber":$sequence,"version":7}""")
    private fun model(rows: List<WorkOpsProjectCatalogRow> = emptyList()) =
        WorkOpsProjectCatalogModel("project", "versions", rows = rows, loaded = true, delivery = true)

    @Test fun `project selection defaults only when unset and retains unavailable explicit selections`() = runTest {
        val ctx = context { operation, _ ->
            assertEquals("WorkOpsVersionProjects", operation)
            json("""{"workOps":{"projects":{"all":[{"id":"archived","key":"OLD","name":"Archived project"},{"id":"active","key":"NEW","name":"Active project"}]}}}""")
        }
        val first = WorkOpsProjectDirectoryModel(); first.reload(ctx)
        assertEquals("archived", first.projectId); assertEquals(2, first.projects.size); assertTrue(first.loaded)
        val missing = WorkOpsProjectDirectoryModel("private"); missing.reload(ctx)
        assertEquals("private", missing.projectId); assertNull(missing.selected)
        missing.reload(context { _, _ -> error("Choices unavailable") })
        assertFalse(missing.loaded); assertEquals("private", missing.projectId); assertTrue(missing.feedback.failed)
    }

    @Test fun `delivery versions sort by sequence and retain dates and project context in offset and result links`() = runTest {
        val model = WorkOpsProjectCatalogModel("project", "versions", targetId = "v1", delivery = true)
        model.reload(context { operation, variables ->
            assertEquals("WorkOpsDeliveryVersions", operation); assertEquals("project", variables.getValue("project").jsonPrimitive.content)
            data("byProject", JsonArray((1..28).map { version("v$it", it) }))
        })
        assertEquals((28 downTo 1).toList(), model.rows.map { it.sequenceNumber })
        assertEquals(25L, model.offset); assertEquals(listOf("v3", "v2", "v1"), model.pageRows.map { it.id })
        assertEquals("2026-12-01T00:00:00Z", model.rows.first().startDate)
        assertEquals("2026-12-15T00:00:00Z", model.rows.first().date)
        assertEquals("/workops/releases/versions?projectId=project&offset=0", model.previousHref)
        model.resultId = "v1"
        assertEquals("/workops/releases/versions?projectId=project&offset=0&item=v1#workops-project-versions-v1", model.resultHref)
    }

    @Test fun `version creation retains rejected dates and fields and sends trimmed values at UTC midnight`() = runTest {
        val model = model().apply { open() }
        model.create(" 2.0 ", " Release notes ", "", "", "2026-12-01", "2026-12-15", context { operation, variables ->
            assertEquals("WorkOpsCreateProjectVersion", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals("2.0", input.getValue("name").jsonPrimitive.content); assertEquals("Release notes", input.getValue("description").jsonPrimitive.content)
            assertEquals("project", input.getValue("projectId").jsonPrimitive.content)
            for ((field, date) in listOf("startDate" to "2026-12-01", "releaseDate" to "2026-12-15")) {
                assertEquals("${date}T00:00:00Z", OffsetDateTime.parse(input.getValue(field).jsonPrimitive.content).toInstant().toString())
            }
            error("Denied")
        })
        assertTrue(model.creating); assertTrue(model.feedback.failed)
        assertEquals(" 2.0 ", model.nameDraft); assertEquals(" Release notes ", model.descriptionDraft)
        model.reloadDraft("2.0 revised", "Updated notes", "", "", "2026-12-02", "2026-12-16", context { _, _ -> data("byProject", JsonArray(emptyList())) })
        assertEquals("2.0 revised", model.nameDraft); assertEquals("2026-12-02", model.startDateDraft); assertEquals("2026-12-16", model.releaseDateDraft)
        assertTrue(model.creating); assertFalse(model.feedback.failed)
    }

    @Test fun `invalid dates fail before writing and cancellation continues to the request`() = runTest {
        val model = model().apply { open() }
        var called = false
        model.create("2.0", "", "", "", "2026-02-30", "", context { _, _ -> called = true; error("Must not call API") })
        assertFalse(called)
        assertTrue(model.feedback.failed); assertTrue(model.creating); assertEquals("2026-02-30", model.startDateDraft)
        assertFailsWith<CancellationException> {
            model.create("2.0", "", "", "", "", "", context { _, _ -> throw CancellationException("Navigation") })
        }
    }

    @Test fun `delivery release permits an archived unreleased version and keeps the inspected version through refresh`() = runTest {
        val original = WorkOpsProjectCatalogRow("item", "2.0", version = 7, archived = true)
        val model = model(listOf(original)); model.beginRelease(original.id)
        model.rows = listOf(original.copy(version = 9))
        model.release(true, context { operation, variables ->
            assertEquals("WorkOpsReleaseProjectVersion", operation); assertEquals(7L, variables.getValue("version").jsonPrimitive.long)
            error("Newer version")
        })
        assertEquals(original, model.releaseTarget); assertTrue(model.feedback.failed)
        assertFailsWith<IllegalArgumentException> {
            WorkOpsProjectCatalogModel("project", "versions", rows = listOf(original), loaded = true).beginRelease(original.id)
        }
    }

    @Test fun `confirmed version deletion uses only its id and retains a referenced version on rejection`() = runTest {
        val row = WorkOpsProjectCatalogRow("item", "2.0", version = 7)
        val model = model(listOf(row)); model.beginDelete(row.id)
        model.delete(false, context { _, _ -> error("Must not call API") })
        assertEquals("Confirm deletion.", model.feedback.message)
        model.delete(true, context { operation, variables ->
            assertEquals("WorkOpsDeleteProjectVersion", operation); assertEquals(setOf("id"), variables.keys)
            assertEquals(row.id, variables.getValue("id").jsonPrimitive.content); error("VERSION_IN_USE")
        })
        assertEquals(row, model.deleteTarget); assertEquals("project", model.projectId); assertTrue(model.feedback.failed)
        model.delete(true, context { operation, _ ->
            if (operation == "WorkOpsDeleteProjectVersion") data("delete", JsonPrimitive(true)) else data("byProject", JsonArray(emptyList()))
        })
        assertNull(model.deleteTarget); assertEquals(emptyList(), model.rows); assertFalse(model.feedback.failed)
    }

    @Test fun `successful creation keeps its result after a failed refresh and returns to the first delivery page`() = runTest {
        val model = model().apply { offset = 25; open() }
        model.create("2.0", "", "", "", "", "", context { operation, variables ->
            if (operation == "WorkOpsCreateProjectVersion") {
                val input = variables.getValue("input").jsonObject
                assertTrue(input["startDate"] == null || input["startDate"] == JsonNull)
                assertTrue(input["releaseDate"] == null || input["releaseDate"] == JsonNull)
                data("create", version("created", 29))
            } else error("Read unavailable")
        })
        assertFalse(model.creating); assertFalse(model.loaded); assertEquals(0L, model.offset)
        assertTrue("projectId=project&offset=0&item=created" in model.resultHref)
    }
}
