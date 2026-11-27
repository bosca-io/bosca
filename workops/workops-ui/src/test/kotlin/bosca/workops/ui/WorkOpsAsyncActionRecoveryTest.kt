package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.design.model.SchemaFormOption
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/** Transport failures after suspension must preserve editable values and inspected revisions. */
class WorkOpsAsyncActionRecoveryTest {
    private val failed = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
            yield()
            error("Transport failed after suspension")
        }
    })

    @Test fun `automation retains edits and inspected targets after suspended transport failures`() = runTest {
        for (id in listOf("", "rule")) {
            val row = WorkOpsAutomationRuleRecord(id, "Original", scope = "PROJECT", scopeId = "project", runAsId = "human", version = 7)
            val model = WorkOpsAutomationModel("PROJECT", "project", rows = listOf(row), loaded = true, editing = true, draft = row)
            model.reloadDraft("Unsent name", "Unsent notes", false, "PROJECT", "project", row.trigger, "[]", "[]", "CONTINUE", "5", "30", "human", failed)
            assertEquals("Unsent name", model.draft.name); assertEquals(7L, model.draft.version); assertTrue(model.profileFeedback.failed)
            model.loaded = true
            model.chooseTrigger("TaskUpdated", "Unsent name", "Unsent notes", false, "PROJECT", "project", row.trigger, "[]", "[]", "CONTINUE", "5", "30", "human", failed)
            assertEquals("TaskUpdated", model.draft.triggerType)
            model.save("Unsent name", "", false, "PROJECT", "project", model.draft.trigger, "[]", "[]", "CONTINUE", "5", "30", "human", failed)
            assertTrue(model.editing); assertTrue(model.feedback.failed); assertEquals(7L, model.draft.version)
            model.loaded = true
            model.open(id, failed); assertTrue(model.editing); assertEquals(id.isNotBlank(), model.profileFeedback.failed)
            model.loaded = true
            model.toggle(id, true, 7, failed); assertTrue(model.feedback.failed); assertEquals(listOf(row), model.rows)
            model.beginDelete(id); model.delete(true, failed); assertEquals(row, model.deleting)
            model.openHistory(id, failed); assertEquals(row, model.historyTarget); assertFalse(model.historyLoaded); assertTrue(model.historyFeedback.failed)
        }
    }

    @Test fun `permission writes preserve group action and removal target after suspended failures`() = runTest {
        for (kind in listOf("portfolio", "program", "project")) {
            val row = WorkOpsGrantRecord("group", "Group", "VIEW")
            val model = WorkOpsPermissionsModel(kind, "entity", entities = listOf(WorkOpsPlanningRow("entity", "ENT", "Entity")), choicesLoaded = true,
                groups = listOf(SchemaFormOption("group", "Group")), groupsLoaded = true, rows = listOf(row), loaded = true)
            model.open(); model.save("group", "VIEW", failed)
            assertTrue(model.editing); assertTrue(model.feedback.failed); assertEquals("group", model.groupDraft); assertEquals("VIEW", model.actionDraft)
            model.beginDelete(row.key); model.delete(true, failed); assertEquals(row, model.deleting)
            model.retry(failed); assertFalse(model.loaded); assertEquals(listOf(row), model.rows)
            model.retryChoices(failed); assertFalse(model.choicesLoaded); assertTrue(model.choiceFeedback.failed)
            model.retryGroups(failed); assertFalse(model.groupsLoaded); assertTrue(model.groupFeedback.failed)
        }
    }

    @Test fun `catalog writes keep drafts dates and inspected versions after suspended failures`() = runTest {
        for (kind in listOf("components", "labels", "versions")) {
            val row = WorkOpsProjectCatalogRow("item", "Original", version = 7)
            val model = WorkOpsProjectCatalogModel("project", kind, rows = listOf(row), loaded = true, delivery = kind == "versions")
            model.open(); model.create("Unsent name", "", "UNASSIGNED", "", "2026-11-01", "2026-12-01", failed)
            assertTrue(model.creating); assertTrue(model.feedback.failed); assertEquals("Unsent name", model.nameDraft); assertEquals("2026-12-01", model.releaseDateDraft)
            model.beginDelete(row.id); model.delete(true, failed); assertEquals(row, model.deleteTarget)
            if (kind == "versions") { model.beginRelease(row.id); model.release(true, failed); assertEquals(row, model.releaseTarget) }
            model.reloadDraft("Unsent name", "Notes", "UNASSIGNED", "", "2026-11-01", "2026-12-01", failed)
            assertFalse(model.loaded); assertEquals("Notes", model.descriptionDraft); assertEquals(7L, model.rows.single().version)
        }
    }
}
