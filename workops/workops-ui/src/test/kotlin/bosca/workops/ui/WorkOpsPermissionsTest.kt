package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.design.model.SchemaFormOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsPermissionsTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: buildJsonObject {})
    })
    private fun response(kind: String, field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject(kind + "s") { put(field, value) } } }
    private fun grant(n: Int, action: String = "VIEW") = buildJsonObject { put("groupId", "group-$n"); put("action", action); putJsonObject("group") { put("id", "group-$n"); put("name", "Group " + n.toString().padStart(3, '0')) } }
    private fun grants(kind: String, count: Int = 1) = response(kind, kind, buildJsonObject { put("id", "entity"); put("permissions", JsonArray((0 until count).map { grant(it) })) })
    private fun entities(kind: String, start: Int = 0, count: Int = 1) = response(kind, "all", JsonArray((start until start + count).map { buildJsonObject { put("id", "entity-$it"); put("key", "E$it"); put("name", "Entity $it") } }))
    private fun groups(start: Int, count: Int): JsonObject = buildJsonObject {
        putJsonObject("security") {
            putJsonObject("groups") {
                putJsonArray("all") {
                    repeat(count) { n ->
                        add(buildJsonObject { put("id", "group-${start + n}"); put("name", "Group ${start + n}") })
                    }
                }
            }
        }
    }
    private fun model(kind: String = "portfolio") = WorkOpsPermissionsModel(kind, "entity", entities = listOf(WorkOpsPlanningRow("entity", "KEY", "Selected entity")), choicesLoaded = true, groups = listOf(SchemaFormOption("group-0", "Group 000")), groupsLoaded = true, rows = listOf(WorkOpsGrantRecord("group-0", "Group 000", "VIEW")), loaded = true)

    @Test fun `authorized choices use each owning namespace and portfolio offsets without resetting an explicit selection`() = runTest {
        for (kind in listOf("portfolio", "program", "project")) {
            val model = WorkOpsPermissionsModel(kind, "unavailable")
            val offsets = mutableListOf<Long>()
            model.retryChoices(context { operation, variables ->
                when (kind) {
                    "portfolio" -> { assertEquals("WorkOpsPermissionPortfolioChoices", operation); val offset = variables.getValue("offset").jsonPrimitive.long; offsets += offset; entities(kind, offset.toInt(), if (offset == 0L) 101 else 3) }
                    "program" -> { assertEquals("WorkOpsPermissionProgramChoices", operation); entities(kind) }
                    else -> { assertEquals("WorkOpsVersionProjects", operation); entities(kind) }
                }
            })
            assertTrue(model.choicesLoaded); assertEquals("unavailable", model.entityId); assertNull(model.selected)
            if (kind == "portfolio") { assertEquals(listOf(0L, 100L), offsets); assertEquals(103, model.entities.size); assertEquals(103, model.entities.map { it.id }.distinct().size) }
            model.retryChoices(context { _, _ -> error("Choices denied") }); assertFalse(model.choicesLoaded); assertTrue(model.choiceFeedback.failed)
        }
    }

    @Test fun `group discovery pages the actual catalog and retains draft selection independently from grants`() = runTest {
        val model = model(); model.open(); val offsets = mutableListOf<Long>()
        model.reloadDraft("selected-removed", "MANAGE", context { operation, variables ->
            assertEquals("WorkOpsPermissionGroups", operation); val offset = variables.getValue("offset").jsonPrimitive.long; offsets += offset; groups(offset.toInt(), if (offset == 0L) 101 else 3)
        })
        assertEquals(listOf(0L, 100L), offsets); assertEquals(103, model.groups.size); assertEquals("Group unavailable", model.groupChoices.single { it.value == "selected-removed" }.label)
        model.retryGroups(context { _, _ -> error("Catalog denied") }); assertFalse(model.groupsLoaded); assertTrue(model.groupFeedback.failed); assertTrue(model.loaded); assertTrue(model.editing)
        assertEquals("selected-removed", model.groupDraft); assertEquals("MANAGE", model.actionDraft); assertEquals("Group 000", model.rows.single().groupName)
    }

    @Test fun `direct reads retain returned labels and action rows without inferring inherited access from an empty result`() = runTest {
        for (kind in listOf("portfolio", "program", "project")) {
            val model = model(kind)
            model.retry(context { operation, variables -> assertEquals("WorkOps${kind.replaceFirstChar(Char::uppercase)}Grants", operation); assertEquals("entity", variables.getValue("id").jsonPrimitive.content); grants(kind) })
            assertTrue(model.loaded); assertEquals("Group 000", model.rows.single().groupName)
            model.retry(context { _, _ -> grants(kind, 0) }); assertTrue(model.loaded); assertTrue(model.rows.isEmpty()); assertFalse(model.feedback.failed)
            model.retry(context { _, _ -> response(kind, kind, JsonNull) }); assertFalse(model.loaded); assertTrue(model.feedback.failed)
            assertTrue(model.groupsLoaded); assertTrue(model.choicesLoaded)
        }
    }

    @Test fun `direct grants paginate by offset and retain scope entity and result targets`() = runTest {
        val model = WorkOpsPermissionsModel("project", "entity", targetId = "group-26-VIEW", entities = listOf(WorkOpsPlanningRow("entity")), choicesLoaded = true)
        model.retry(context { _, _ -> grants("project", 28) })
        assertEquals(25L, model.offset); assertEquals(3, model.pageRows.size); assertEquals("", model.nextHref)
        assertEquals("/workops/settings/permissions?kind=project&entityId=entity&offset=0", model.previousHref)
        model.resultId = "group-26-VIEW"; assertTrue("&grant=group-26-VIEW#workops-grant-group-26-VIEW" in model.resultHref)
    }

    @Test fun `grant writes retain the exact scope group and action through rejection and catalog failure`() = runTest {
        for (kind in listOf("portfolio", "program", "project")) {
            val model = model(kind); model.open(); model.retryGroups(context { _, _ -> error("Catalog denied") })
            model.save("group-0", "EDIT", context { operation, variables ->
                assertEquals("WorkOps${kind.replaceFirstChar(Char::uppercase)}Grant", operation)
                assertEquals(setOf("id", "group", "action"), variables.keys); assertEquals("entity", variables.getValue("id").jsonPrimitive.content)
                assertEquals("group-0", variables.getValue("group").jsonPrimitive.content); assertEquals("EDIT", variables.getValue("action").jsonPrimitive.content); error("Manage denied")
            })
            assertTrue(model.editing); assertTrue(model.feedback.failed); assertEquals("group-0", model.groupDraft); assertEquals("EDIT", model.actionDraft); assertFalse(model.groupsLoaded)
        }
    }

    @Test fun `invalid grant drafts never write and an accepted grant cannot repeat after failed readback`() = runTest {
        val model = model(); model.open(); var writes = 0
        val denied = context { _, _ -> writes++; error("Must not write") }
        model.save("", "VIEW", denied); assertEquals("Choose a group.", model.feedback.message)
        model.save("group-0", "EXECUTE", denied); assertEquals("Choose an action.", model.feedback.message); assertEquals(0, writes)
        val ctx = context { operation, _ -> if (operation == "WorkOpsPortfolioGrant") { writes++; response("portfolio", "addPermission", JsonPrimitive(true)) } else error("Read unavailable") }
        model.save("group-0", "VIEW", ctx); assertFalse(model.editing); assertFalse(model.loaded); assertTrue(model.feedback.failed); assertTrue("grant=group-0-VIEW" in model.resultHref)
        model.save("group-0", "VIEW", ctx); assertEquals(1, writes)
    }

    @Test fun `confirmed revocation retains the inspected row and handles stored actions outside the new-grant menu`() = runTest {
        for (kind in listOf("portfolio", "program", "project")) {
            val model = model(kind); val row = WorkOpsGrantRecord("stored-group", "Stored group", "EXECUTE"); model.rows = listOf(row); model.beginDelete(row.key)
            model.rows = listOf(row.copy(groupName = "New label")); model.delete(false, context { _, _ -> error("Must not write") }); assertEquals("Confirm removal.", model.feedback.message)
            model.delete(true, context { operation, variables ->
                assertEquals("WorkOps${kind.replaceFirstChar(Char::uppercase)}Revoke", operation); assertEquals("stored-group", variables.getValue("group").jsonPrimitive.content); assertEquals("EXECUTE", variables.getValue("action").jsonPrimitive.content); assertEquals("entity", variables.getValue("id").jsonPrimitive.content); error("Denied")
            })
            assertEquals(row, model.deleting); assertTrue(model.feedback.failed)
            var writes = 0; val ctx = context { operation, _ -> if (operation.endsWith("Revoke")) { writes++; response(kind, "removePermission", JsonPrimitive(true)) } else error("Read failed") }
            model.delete(true, ctx); assertNull(model.deleting); assertFalse(model.loaded); model.delete(true, ctx); assertEquals(1, writes)
        }
    }

    @Test fun `false mutation acknowledgements retain drafts and cancellation propagates through each read boundary`() = runTest {
        val model = model(); model.open()
        model.save("group-0", "VIEW", context { _, _ -> response("portfolio", "addPermission", JsonPrimitive(false)) }); assertTrue(model.editing); assertEquals("The permission was not added.", model.feedback.message)
        model.beginDelete(model.rows.single().key); model.delete(true, context { _, _ -> response("portfolio", "removePermission", JsonPrimitive(false)) }); assertNotNull(model.deleting); assertEquals("The permission was not removed.", model.feedback.message)
        val cancelled = context { _, _ -> throw CancellationException("Navigation") }
        assertFailsWith<CancellationException> { model.retry(cancelled) }; assertFalse(model.loaded)
        assertFailsWith<CancellationException> { model.retryGroups(cancelled) }; assertFalse(model.groupsLoaded)
        assertFailsWith<CancellationException> { model.retryChoices(cancelled) }; assertFalse(model.choicesLoaded)
    }
    @Test fun `page refresh reloads all catalogs and current grants and preserves cancellation`() = runTest {
        for (id in listOf("entity", "")) {
            val model = WorkOpsPermissionsModel("portfolio", id)
            val calls = mutableListOf<String>()
            model.refresh(context { operation, _ ->
                calls += operation
                when (operation) {
                    "WorkOpsPermissionPortfolioChoices" -> response("portfolio", "all", buildJsonArray {
                        add(buildJsonObject { put("id", "entity"); put("key", "ENT"); put("name", "Entity") })
                    })
                    "WorkOpsPermissionGroups" -> groups(0, 1)
                    else -> grants("portfolio")
                }
            })
            assertTrue(model.choicesLoaded); assertTrue(model.groupsLoaded)
            assertEquals(id.isNotBlank(), model.loaded)
            assertEquals(if (id.isBlank()) 2 else 3, calls.size)
            if (id.isNotBlank()) assertEquals("group-0", model.rows.single().groupId)
        }
        val failed = model()
        failed.refresh(context { operation, _ -> if (operation == "WorkOpsPermissionGroups") groups(0, 1) else error("Read unavailable") })
        assertFalse(failed.choicesLoaded); assertTrue(failed.choiceFeedback.failed); assertTrue(failed.groupsLoaded); assertFalse(failed.loaded)
        for (cancelAt in listOf("WorkOpsPermissionPortfolioChoices", "WorkOpsPermissionGroups", "WorkOpsPortfolioGrants")) {
            val model = WorkOpsPermissionsModel("portfolio", "entity")
            assertFailsWith<CancellationException> { model.refresh(context { operation, _ ->
                if (operation == cancelAt) throw CancellationException("Navigation")
                if (operation == "WorkOpsPermissionGroups") groups(0, 1)
                else response("portfolio", "all", buildJsonArray { add(buildJsonObject { put("id", "entity"); put("key", "ENT"); put("name", "Entity") }) })
            }) }
        }
    }

}
