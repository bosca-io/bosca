package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsGlobalCatalogTest {
    @Test fun `catalog field validation rejects missing colors icons categories and relationship labels before writing`() = runTest {
        val noWrites = context { _, _ -> throw AssertionError("Invalid catalog draft cannot write") }
        for (fields in listOf(
            listOf("statuses", "", "star", "TODO", "in", "out"),
            listOf("task-types", "#123456", "", "STANDARD", "in", "out"),
            listOf("task-types", "#123456", "star", "UNKNOWN", "in", "out"),
            listOf("statuses", "#123456", "star", "UNKNOWN", "in", "out"),
            listOf("link-types", "", "", "BLOCKS", "", "out"),
            listOf("link-types", "", "", "BLOCKS", "in", ""),
            listOf("link-types", "", "", "UNKNOWN", "in", "out"))) {
            val (kind, color, icon, category, inward) = fields
            val outward = fields[5]
            val model = WorkOpsGlobalCatalogModel(kind, loaded = true)
            model.open("")
            model.save("Retained", "Notes", color, icon, category, "1", inward, outward, noWrites)
            assertTrue(model.feedback.failed)
            assertTrue(model.editing)
            assertEquals("Retained", model.draft.name)
        }
    }
    private val kinds = listOf("task-types", "statuses", "priorities", "labels", "link-types", "resolutions")
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun namespace(kind: String) = when (kind) {
        "task-types" -> "taskTypes"; "link-types" -> "linkTypes"; else -> kind
    }
    private fun operation(kind: String) = when (kind) {
        "task-types" -> "TaskType"; "statuses" -> "Status"; "priorities" -> "Priority"; "labels" -> "Label"; "link-types" -> "LinkType"; else -> "Resolution"
    }
    private fun result(kind: String, action: String, value: JsonElement) = buildJsonObject {
        putJsonObject("workOps") { putJsonObject(namespace(kind)) { put(action, value) } }
    }
    private fun row(kind: String, id: String = "catalog", order: Int = 2, version: Long = 7) = buildJsonObject {
        put("id", id); put("name", "Name $id"); put("version", version)
        when (kind) {
            "task-types" -> { put("description", "Type notes"); put("colorHex", "#1230"); put("iconKey", "check-square"); put("hierarchyLevel", "EPIC") }
            "statuses" -> { put("description", JsonNull); put("category", "DONE"); put("colorHex", "#1230") }
            "priorities" -> { put("description", "Priority notes"); put("colorHex", "#1230"); put("iconKey", "stored-icon"); put("displayOrder", order) }
            "labels" -> { put("colorHex", JsonNull); put("scope", "GLOBAL") }
            "link-types" -> { put("inwardLabel", "is blocked by"); put("outwardLabel", "blocks"); put("category", "BLOCKS") }
            "resolutions" -> { put("description", "Resolution notes"); put("displayOrder", order) }
        }
    }
    private fun list(kind: String, rows: List<JsonElement>) = buildJsonObject {
        putJsonObject("workOps") {
            putJsonObject(when (kind) { "task-types", "priorities", "resolutions" -> "tasks"; "link-types" -> "links"; else -> kind }) {
                put(when (kind) { "task-types" -> "taskTypes"; "statuses" -> "all"; "labels" -> "global"; "link-types" -> "linkTypes"; else -> kind }, JsonArray(rows))
            }
        }
    }
    private suspend fun save(model: WorkOpsGlobalCatalogModel, ctx: RenderContext, name: String = " Draft name ", order: String = "3", category: String = model.draft.category, color: String = "#abc0") =
        model.save(name, " Draft notes ", color, "stored-icon", category, order, " inward ", " outward ", ctx)

    @Test fun `all six typed catalogs distinguish unavailable data from a successful empty read`() = runTest {
        for (kind in kinds) {
            val model = WorkOpsGlobalCatalogModel(kind)
            model.retry(context { _, _ -> error("Read denied") }); assertFalse(model.loaded); assertTrue(model.feedback.failed)
            model.retry(context { _, _ -> list(kind, listOf(row(kind))) })
            assertTrue(model.loaded); assertFalse(model.feedback.failed); assertEquals(7L, model.rows.single().version)
            assertEquals(if (kind == "labels" || kind == "link-types" || kind == "resolutions") "" else "#1230", model.rows.single().color)
            model.retry(context { _, _ -> list(kind, emptyList()) }); assertTrue(model.loaded); assertTrue(model.rows.isEmpty())
        }
    }

    @Test fun `ordered catalogs use display order with offset pages and saved target links`() = runTest {
        for (kind in listOf("priorities", "resolutions")) {
            val model = WorkOpsGlobalCatalogModel(kind, targetId = "row-26")
            model.reload(context { _, _ -> list(kind, (27 downTo 0).map { row(kind, "row-$it", it) }) })
            assertEquals((0..27).map(Int::toString), model.rows.map { it.order }); assertEquals(25L, model.offset)
            assertEquals(3, model.pageRows.size); assertTrue(model.pageRows.any { it.id == "row-26" }); assertEquals("", model.nextHref)
            assertEquals("/workops/settings/$kind?offset=0", model.previousHref)
            model.resultId = "row-26"; assertTrue("item=row-26#workops-catalog-row-26" in model.resultHref)
        }
    }

    @Test fun `typed creates preserve colors and the fields owned by each catalog`() = runTest {
        for (kind in kinds) {
            val model = WorkOpsGlobalCatalogModel(kind, loaded = true); model.open("")
            val category = when (kind) { "task-types" -> "EPIC"; "statuses" -> "CANCELLED"; "link-types" -> "CAUSES"; else -> model.draft.category }
            save(model, context { name, variables ->
                if (name == "WorkOpsCatalogCreate${operation(kind)}") {
                    val input = variables.getValue("input").jsonObject
                    assertEquals("Draft name", input.getValue("name").jsonPrimitive.content)
                    when (kind) {
                        "task-types" -> { assertEquals("EPIC", input.getValue("hierarchyLevel").jsonPrimitive.content); assertEquals("stored-icon", input.getValue("iconKey").jsonPrimitive.content) }
                        "statuses", "link-types" -> assertEquals(category, input.getValue("category").jsonPrimitive.content)
                        "priorities", "resolutions" -> assertEquals(3, input.getValue("displayOrder").jsonPrimitive.int)
                        "labels" -> assertEquals("GLOBAL", input.getValue("scope").jsonPrimitive.content)
                    }
                    if (model.hasColor) assertEquals("#abc0", input.getValue("colorHex").jsonPrimitive.content)
                    if (model.hasDescription) assertEquals("Draft notes", input.getValue("description").jsonPrimitive.content)
                    if (kind == "link-types") { assertEquals("inward", input.getValue("inwardLabel").jsonPrimitive.content); assertEquals("outward", input.getValue("outwardLabel").jsonPrimitive.content) }
                    result(kind, "create", buildJsonObject { put("id", "created") })
                } else list(kind, emptyList())
            }, category = category)
            assertFalse(model.feedback.failed, "$kind: ${model.feedback.message}"); assertFalse(model.editing); assertEquals("created", model.resultId)
        }
    }

    @Test fun `refreshing all six catalogs preserves the captured edit version and exact rejected drafts`() = runTest {
        for (kind in kinds) {
            val model = WorkOpsGlobalCatalogModel(kind)
            model.reload(context { _, _ -> list(kind, listOf(row(kind))) }); model.open("catalog")
            val category = model.draft.category
            model.reloadDraft(" Draft name ", " Draft notes ", "#abc0", "stored-icon", category, "3", " inward ", " outward ", context { _, _ -> list(kind, listOf(row(kind, version = 9))) })
            assertEquals(7L, model.draft.version); assertEquals(9L, model.rows.single().version)
            save(model, context { name, variables ->
                assertEquals("WorkOpsCatalogUpdate${operation(kind)}", name); assertEquals("catalog", variables.getValue("id").jsonPrimitive.content)
                val payload = if (kind == "labels") variables else variables.getValue("input").jsonObject
                assertEquals(7L, payload.getValue(if (kind == "labels") "version" else "expectedVersion").jsonPrimitive.long)
                error("Newer catalog version")
            })
            assertTrue(model.feedback.failed); assertTrue(model.editing); assertEquals(" Draft name ", model.draft.name)
            assertEquals(" Draft notes ", model.draft.description); assertEquals("#abc0", model.draft.color)
            assertEquals(" inward ", model.draft.inward); assertEquals("3", model.draft.order)
        }
    }

    @Test fun `a stored custom priority icon remains selectable and invalid values never write`() = runTest {
        val model = WorkOpsGlobalCatalogModel("priorities")
        model.reload(context { _, _ -> list("priorities", listOf(row("priorities"))) }); model.open("catalog")
        assertTrue(model.icons.any { it.value == "stored-icon" })
        var writes = 0; val ctx = context { _, _ -> writes++; error("Must not write") }
        for (order in listOf("-1", "100", "1.5", "", "bad")) { save(model, ctx, order = order); assertTrue(model.feedback.failed); assertEquals(order, model.draft.order) }
        save(model, ctx, name = " "); assertEquals("Enter a name.", model.feedback.message); assertEquals(0, writes)
    }

    @Test fun `blank global label colors remain nullable and an accepted create closes before failed readback`() = runTest {
        val model = WorkOpsGlobalCatalogModel("labels", loaded = true); model.open("")
        var writes = 0
        val ctx = context { name, variables ->
            if (name == "WorkOpsCatalogCreateLabel") {
                writes++; assertEquals(JsonNull, variables.getValue("input").jsonObject["colorHex"] ?: JsonNull)
                result("labels", "create", buildJsonObject { put("id", "saved") })
            } else error("Refresh unavailable")
        }
        save(model, ctx, color = ""); assertEquals("saved", model.resultId); assertFalse(model.editing); assertFalse(model.loaded); assertTrue(model.feedback.failed)
        save(model, ctx, color = ""); assertEquals(1, writes); assertTrue("item=saved" in model.resultHref)
    }

    @Test fun `catalog color length matches storage and keeps an invalid draft without a write`() = runTest {
        for (kind in listOf("task-types", "statuses", "priorities", "labels")) {
            val model = WorkOpsGlobalCatalogModel(kind, loaded = true); model.open("")
            save(model, context { _, _ -> error("Must not write") }, color = "#12345600")
            assertEquals("Use a color with up to 7 characters.", model.feedback.message)
            assertTrue(model.feedback.failed); assertTrue(model.editing); assertEquals("#12345600", model.draft.color)
        }
    }

    @Test fun `delete confirmation retains each captured identity through rejection and clears it before failed readback`() = runTest {
        for (kind in kinds) {
            val entry = WorkOpsGlobalCatalogEntry("captured", "Captured entry", version = 7)
            val model = WorkOpsGlobalCatalogModel(kind, rows = listOf(entry), loaded = true); model.beginDelete(entry.id)
            model.delete(false, context { _, _ -> error("Must not write") }); assertEquals("Confirm deletion.", model.feedback.message)
            model.delete(true, context { name, variables ->
                assertEquals("WorkOpsCatalogDelete${operation(kind)}", name); assertEquals(setOf("id"), variables.keys)
                assertEquals("captured", variables.getValue("id").jsonPrimitive.content); error("Still referenced")
            })
            assertEquals(entry, model.deleting); assertTrue(model.feedback.failed)
            var writes = 0
            val ctx = context { name, _ ->
                if (name == "WorkOpsCatalogDelete${operation(kind)}") { writes++; result(kind, "delete", JsonPrimitive(true)) }
                else error("Refresh unavailable")
            }
            model.delete(true, ctx); assertNull(model.deleting); assertTrue(model.feedback.failed); assertFalse(model.loaded)
            model.delete(true, ctx); assertEquals(1, writes)
        }
    }

    @Test fun `cancellation propagates and unavailable catalogs cannot open stale actions`() = runTest {
        val model = WorkOpsGlobalCatalogModel("statuses", rows = listOf(WorkOpsGlobalCatalogEntry("status")), loaded = true)
        assertFailsWith<CancellationException> { model.retry(context { _, _ -> throw CancellationException("Navigation") }) }
        assertFalse(model.loaded); assertFailsWith<IllegalArgumentException> { model.open("status") }; assertFailsWith<IllegalArgumentException> { model.beginDelete("status") }
    }
}
