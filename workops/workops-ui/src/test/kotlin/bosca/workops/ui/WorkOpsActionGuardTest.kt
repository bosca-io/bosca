package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Stale or incomplete UI state must not turn unavailable controls into API writes. */
class WorkOpsActionGuardTest {
    private var calls = 0
    private val noApi = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
            calls++
            error("No API call is allowed")
        }
    })

    @Test fun `catalog availability and kind guards reject writes before transport`() = runTest {
        for (model in listOf(
            WorkOpsProjectCatalogModel("project", "unknown", loaded = true, creating = true),
            WorkOpsProjectCatalogModel("project", "components", loaded = false, creating = true),
            WorkOpsProjectCatalogModel("project", "components", loaded = true, creating = false),
        )) {
            model.create("Draft", "Notes", "UNASSIGNED", "", "", "", noApi)
            assertTrue(model.feedback.failed); assertEquals("Draft", model.nameDraft)
        }
        for (model in listOf(
            WorkOpsProjectCatalogModel("project", "unknown"),
            WorkOpsProjectCatalogModel("project", "components", delivery = true),
            WorkOpsProjectCatalogModel("project", "versions", settings = true),
        )) { model.retry(noApi); assertFalse(model.loaded); assertTrue(model.feedback.failed) }
        val component = WorkOpsProjectCatalogModel("project", "components", loaded = true, creating = true)
        component.create("", "", "UNASSIGNED", "", "", "", noApi); assertEquals("Enter a name.", component.feedback.message)
        component.create("Draft", "", "UNKNOWN", "", "", "", noApi); assertEquals("Choose an assignee mode.", component.feedback.message)
        assertEquals("UNKNOWN", component.modeDraft); assertEquals(0, calls)
    }

    @Test fun `catalog release and delete require available eligible inspected rows`() = runTest {
        val row = WorkOpsProjectCatalogRow("item", "Version", version = 7)
        for (model in listOf(
            WorkOpsProjectCatalogModel("project", "versions", loaded = false, rows = listOf(row)),
            WorkOpsProjectCatalogModel("project", "components", loaded = true, rows = listOf(row)),
            WorkOpsProjectCatalogModel("project", "versions", loaded = true),
            WorkOpsProjectCatalogModel("project", "versions", loaded = true, rows = listOf(row.copy(released = true))),
            WorkOpsProjectCatalogModel("project", "versions", loaded = true, rows = listOf(row.copy(archived = true))),
        )) { assertFailsWith<IllegalArgumentException> { model.beginRelease("item") }; assertNull(model.releaseTarget) }
        for (model in listOf(
            WorkOpsProjectCatalogModel("project", "components", loaded = false, rows = listOf(row)),
            WorkOpsProjectCatalogModel("project", "versions", loaded = true, rows = listOf(row)),
            WorkOpsProjectCatalogModel("project", "unknown", loaded = true, rows = listOf(row)),
            WorkOpsProjectCatalogModel("project", "components", loaded = true),
        )) { assertFailsWith<IllegalArgumentException> { model.beginDelete("item") }; assertNull(model.deleteTarget) }
        val empty = WorkOpsProjectCatalogModel("project", "versions", loaded = true)
        empty.release(true, noApi); assertTrue(empty.feedback.failed)
        empty.delete(true, noApi); assertTrue(empty.feedback.failed)
        val invalid = WorkOpsProjectCatalogModel("project", "unknown", loaded = true, deleteTarget = row)
        invalid.delete(true, noApi); assertTrue(invalid.feedback.failed); assertEquals(row, invalid.deleteTarget)
        assertEquals(0, calls)
    }

    @Test fun `permission editor and removal guards retain current rows without API calls`() = runTest {
        val entity = WorkOpsPlanningRow("entity", "ENT", "Entity")
        val row = WorkOpsGrantRecord("group", "Group", "VIEW")
        for (model in listOf(
            WorkOpsPermissionsModel("project", "entity", loaded = true, groupsLoaded = true),
            WorkOpsPermissionsModel("project", "entity", entities = listOf(entity), choicesLoaded = true, loaded = false, groupsLoaded = true),
            WorkOpsPermissionsModel("project", "entity", entities = listOf(entity), choicesLoaded = true, loaded = true, groupsLoaded = false),
        )) { assertFailsWith<IllegalArgumentException> { model.open() }; assertFalse(model.editing) }
        val model = WorkOpsPermissionsModel("project", "entity", entities = listOf(entity), choicesLoaded = true, rows = listOf(row))
        assertFailsWith<IllegalArgumentException> { model.beginDelete(row.key) }
        model.loaded = true; assertFailsWith<IllegalArgumentException> { model.beginDelete("missing") }
        model.delete(true, noApi); assertTrue(model.feedback.failed); assertEquals(listOf(row), model.rows)
        model.save("group", "VIEW", noApi); assertTrue(model.feedback.failed); assertFalse(model.editing)
        assertEquals(0, calls)
    }

    @Test fun `automation rejects missing inspected rules and invalid creation controls without transport`() = runTest {
        val row = WorkOpsAutomationRuleRecord("rule", "Rule", version = 7)
        val model = WorkOpsAutomationModel(rows = listOf(row))
        assertFailsWith<IllegalArgumentException> { model.beginDelete(row.id) }
        assertFailsWith<IllegalArgumentException> { model.openHistory(row.id, noApi) }
        model.open(row.id, noApi); assertTrue(model.feedback.failed); assertFalse(model.editing)
        model.loaded = true
        assertFailsWith<IllegalArgumentException> { model.beginDelete("missing") }
        assertFailsWith<IllegalArgumentException> { model.openHistory("missing", noApi) }
        model.open("missing", noApi); assertTrue(model.feedback.failed)
        model.retryHistory(noApi); assertTrue(model.historyFeedback.failed)
        for ((name, runAs, mode) in listOf(Triple("", "human", "CONTINUE"), Triple("Draft", "", "CONTINUE"), Triple("Draft", "human", "UNKNOWN"))) {
            model.editing = true
            model.save(name, "Notes", false, "GLOBAL", "", "{}", "[]", "[]", mode, "5", "30", runAs, noApi)
            assertTrue(model.feedback.failed); assertTrue(model.editing); assertEquals(name, model.draft.name)
        }
        model.chooseTrigger("", "Draft", "Notes", false, "GLOBAL", "", "{}", "[]", "[]", "CONTINUE", "5", "30", "", noApi)
        assertTrue(model.feedback.failed)
        assertEquals("", model.label("")); assertEquals("", model.resultHref)
        assertEquals(0, calls)
    }

    @Test fun `repeated modal cancellation is idempotent and preserves inspected list data`() {
        val row = WorkOpsProjectCatalogRow("item", "Version", version = 7)
        val catalog = WorkOpsProjectCatalogModel("project", "versions", rows = listOf(row), loaded = true, delivery = true)
        catalog.beginDelete(row.id); repeat(2) { catalog.cancelDelete() }; assertNull(catalog.deleteTarget)
        catalog.beginRelease(row.id); repeat(2) { catalog.cancelRelease() }; assertNull(catalog.releaseTarget); assertEquals(listOf(row), catalog.rows)
        val rule = WorkOpsAutomationRuleRecord("rule", "Rule", version = 7)
        val automation = WorkOpsAutomationModel(rows = listOf(rule), loaded = true)
        automation.beginDelete(rule.id); repeat(2) { automation.cancelDelete() }; assertNull(automation.deleting); assertEquals(listOf(rule), automation.rows)
        val grant = WorkOpsGrantRecord("group", "Group", "VIEW")
        val permissions = WorkOpsPermissionsModel(rows = listOf(grant), loaded = true)
        permissions.beginDelete(grant.key); repeat(2) { permissions.cancelDelete() }; assertNull(permissions.deleting); assertEquals(listOf(grant), permissions.rows)
    }
    @Test fun `missing result targets keep paging and clear stale rows without inventing a match`() = runTest {
        val read = RenderContext(gql = object : GraphQLClient {
            override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement =
                Json.parseToJsonElement(when (operationName) {
                    "WorkOpsAutomationRules" -> """{"workOps":{"automation":{"rules":[]}}}"""
                    "WorkOpsProjectGrants" -> """{"workOps":{"projects":{"project":{"id":"entity","permissions":[]}}}}"""
                    "WorkOpsDeliveryVersions" -> """{"workOps":{"versions":{"byProject":[]}}}"""
                    else -> """{"workOps":{"versions":{"byProject":[]},"components":{"byProject":[]},"labels":{"byProject":[]}}}"""
                })
        })
        for (delivery in listOf(false, true)) {
            val catalog = WorkOpsProjectCatalogModel("project", "versions", offset = 25, targetId = "missing", delivery = delivery)
            catalog.reload(read); assertTrue(catalog.loaded); assertEquals(25L, catalog.offset); assertTrue(catalog.rows.isEmpty())
        }
        val automation = WorkOpsAutomationModel(offset = 25, targetId = "missing")
        automation.retry(read); assertTrue(automation.loaded); assertEquals(25L, automation.offset); assertTrue(automation.rows.isEmpty())
        val permissions = WorkOpsPermissionsModel("project", "entity", offset = 25, targetId = "missing",
            entities = listOf(WorkOpsPlanningRow("entity", "ENT", "Entity")), choicesLoaded = true)
        permissions.retry(read); assertTrue(permissions.loaded); assertEquals(25L, permissions.offset); assertTrue(permissions.rows.isEmpty())
        assertEquals("", WorkOpsPermissionsModel(kind = "").entityLabel)
        assertTrue(WorkOpsPermissionsModel(kind = "unknown").tabs.none { it.selected })
    }

    @Test fun `direct grants sort deterministically when groups share names and actions`() = runTest {
        val entity = WorkOpsPlanningRow("entity", "ENT", "Entity")
        val model = WorkOpsPermissionsModel("project", "entity", entities = listOf(entity), choicesLoaded = true)
        val read = RenderContext(gql = object : GraphQLClient {
            override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
                assertEquals("WorkOpsProjectGrants", operationName)
                return Json.parseToJsonElement("""{"workOps":{"projects":{"project":{"id":"entity","permissions":[
                  {"groupId":"g2","action":"VIEW","group":{"id":"g2","name":"Same"}},
                  {"groupId":"g1","action":"VIEW","group":{"id":"g1","name":"same"}},
                  {"groupId":"g1","action":"EDIT","group":{"id":"g1","name":"same"}},
                  {"groupId":"g3","action":"VIEW","group":{"id":"g3","name":"Alpha"}}
                ]}}}}""")
            }
        })
        repeat(2) {
            model.retry(read); assertTrue(model.loaded)
            assertEquals(listOf("g3:VIEW", "g1:EDIT", "g1:VIEW", "g2:VIEW"), model.rows.map { it.groupId + ":" + it.action })
        }
    }

    @Test fun `unsupported permission namespaces never dispatch grants or revocations`() = runTest {
        val row = WorkOpsGrantRecord("group", "Group", "VIEW")
        val model = WorkOpsPermissionsModel("unknown", "entity", entities = listOf(WorkOpsPlanningRow("entity", "ENT", "Entity")),
            choicesLoaded = true, groupsLoaded = true, loaded = true, rows = listOf(row))
        model.open(); model.save("group", "VIEW", noApi)
        assertTrue(model.feedback.failed); assertTrue(model.editing); assertEquals("group", model.groupDraft)
        model.beginDelete(row.key); model.delete(true, noApi)
        assertTrue(model.feedback.failed); assertEquals(row, model.deleting); assertEquals(0, calls)
    }

}
