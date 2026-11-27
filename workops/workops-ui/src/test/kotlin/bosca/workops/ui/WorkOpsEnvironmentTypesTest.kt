package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsEnvironmentTypesTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun type(id: String, order: Int, revision: Long = 7) = buildJsonObject {
        put("id", id); put("name", "Type $id"); put("description", JsonNull); put("displayOrder", order); put("version", revision)
    }
    private fun data(field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject("multiRepo") { put(field, value) } } }

    @Test fun `types sort by display order and resolve target links beyond the first offset page`() = runTest {
        val model = WorkOpsEnvironmentTypesModel(targetId = "type-26")
        model.reload(context { operation, _ ->
            assertEquals("WorkOpsEnvironmentTypes", operation)
            data("environmentTypes", JsonArray((27 downTo 0).map { type("type-$it", it) }))
        })
        assertEquals((0..27).toList(), model.rows.map { it.order }); assertEquals(25L, model.offset)
        assertEquals(3, model.pageRows.size); assertTrue(model.pageRows.any { it.id == "type-26" })
        assertEquals("/workops/settings/environment-types?offset=0", model.previousHref)
        model.resultId = "type-26"; assertTrue("item=type-26#workops-environment-type-type-26" in model.resultHref)
    }

    @Test fun `type edits keep inspected versions and drafts when the catalog changes`() = runTest {
        val original = WorkOpsEnvironmentTypeRecord("type", "Testing", "Original notes", 2, 7)
        val model = WorkOpsEnvironmentTypesModel(listOf(original), loaded = true)
        model.open(original.id)
        model.reloadDraft("QA", "Draft notes", "4", context { _, _ -> data("environmentTypes", JsonArray(listOf(type("type", 9, 9)))) })
        assertEquals(original, model.editing); assertEquals("Draft notes", model.draft.description)
        model.save(" QA ", " Updated notes ", "4", context { operation, variables ->
            assertEquals("WorkOpsUpdateEnvironmentType", operation); assertEquals("type", variables.getValue("id").jsonPrimitive.content)
            assertEquals(7L, variables.getValue("version").jsonPrimitive.long); assertEquals(4, variables.getValue("order").jsonPrimitive.int)
            assertEquals("QA", variables.getValue("name").jsonPrimitive.content); assertEquals("Updated notes", variables.getValue("description").jsonPrimitive.content)
            error("Newer type")
        })
        assertEquals(original, model.editing); assertEquals(" QA ", model.draft.name); assertEquals("4", model.draft.order); assertTrue(model.feedback.failed)
    }

    @Test fun `creation keeps the saved result across failed refresh and does not keep a duplicate form open`() = runTest {
        val model = WorkOpsEnvironmentTypesModel(loaded = true); model.open("")
        model.save(" New stage ", " Notes ", "3", context { operation, variables ->
            if (operation == "WorkOpsCreateEnvironmentType") {
                assertEquals(3, variables.getValue("order").jsonPrimitive.int); assertEquals("New stage", variables.getValue("name").jsonPrimitive.content)
                data("createEnvironmentType", type("created", 3))
            } else error("Read unavailable")
        })
        assertFalse(model.creating); assertNull(model.editing); assertFalse(model.loaded); assertTrue(model.feedback.failed)
        assertTrue("item=created" in model.resultHref)
    }

    @Test fun `invalid display orders and missing names fail without writes and preserve exact input`() = runTest {
        val model = WorkOpsEnvironmentTypesModel(loaded = true); model.open("")
        var called = false
        val ctx = context { _, _ -> called = true; error("Must not write") }
        for (value in listOf("-1", "100", "1.5", "bad", "")) {
            model.save("Testing", "Retained description", value, ctx)
            assertTrue(model.feedback.failed); assertEquals(value, model.draft.order); assertTrue(model.creating)
        }
        model.save(" ", "", "1", ctx); assertEquals("Enter a name.", model.feedback.message); assertFalse(called)
    }

    @Test fun `in use type deletion retains its target and requires confirmation before the id only mutation`() = runTest {
        val original = WorkOpsEnvironmentTypeRecord("type", "Testing", version = 7)
        val model = WorkOpsEnvironmentTypesModel(listOf(original), loaded = true); model.beginDelete(original.id)
        model.delete(false, context { _, _ -> error("Must not write") }); assertEquals("Confirm deletion.", model.feedback.message)
        model.delete(true, context { operation, variables ->
            assertEquals("WorkOpsDeleteEnvironmentType", operation); assertEquals(setOf("id"), variables.keys); error("Type is in use")
        })
        assertEquals(original, model.deleting); assertTrue(model.feedback.failed)
        model.delete(true, context { operation, _ -> if (operation == "WorkOpsDeleteEnvironmentType") data("deleteEnvironmentType", JsonPrimitive(true)) else data("environmentTypes", JsonArray(emptyList())) })
        assertNull(model.deleting); assertTrue(model.rows.isEmpty()); assertFalse(model.feedback.failed)
    }

    @Test fun `cancellation propagates and an unavailable catalog cannot open a stale action`() = runTest {
        val model = WorkOpsEnvironmentTypesModel(listOf(WorkOpsEnvironmentTypeRecord("type", "Testing")), loaded = true); model.open("type")
        assertFailsWith<CancellationException> {
            model.reloadDraft("Draft stage", "Notes", "5", context { _, _ -> throw CancellationException("Navigation") })
        }
        assertFalse(model.loaded); assertEquals("Draft stage", model.draft.name)
        assertFailsWith<IllegalArgumentException> { model.open("type") }
        assertFailsWith<IllegalArgumentException> { model.beginDelete("type") }
    }
}
