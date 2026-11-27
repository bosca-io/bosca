package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.InvalidBmlClientStateException
import bosca.bml.render.RenderContext
import bosca.bml.render.decodeClientState
import bosca.bml.render.encodeState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Posted island state must retain failed drafts and cannot turn an unloaded editor into a write. */
class WorkOpsStateInputTest {
    private val noWrites = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement =
            throw AssertionError("Unloaded state attempted $operationName")
    })

    private fun refused(feedback: WorkOpsFeedback, message: String) {
        assertTrue(feedback.failed)
        assertEquals(message, feedback.message)
    }

    @Test fun `state without a required entity identity is rejected at the client-state boundary`() {
        assertFailsWith<InvalidBmlClientStateException> { decodeClientState<WorkOpsTaskDetailModel>("{}") }
        assertFailsWith<InvalidBmlClientStateException> { decodeClientState<WorkOpsSpecDetailModel>("{}") }
        assertFailsWith<InvalidBmlClientStateException> { decodeClientState<WorkOpsRequirementDetailModel>("{}") }
        assertFailsWith<InvalidBmlClientStateException> { decodeClientState<WorkOpsProjectCatalogModel>("""{"kind":"versions"}""") }
    }

    @Test fun `an unloaded task state retains its submitted draft and inspected version without saving`() = runTest {
        val model = decodeClientState<WorkOpsTaskDetailModel>("""{"id":"task","editing":true,"draft":{"expectedVersion":7}}""")
        model.save("Unsaved task", "Draft body", "priority", "", "", "", "", "", noWrites)
        refused(model.feedback, "Open the task editor before saving.")
        val returned = decodeClientState<WorkOpsTaskDetailModel>(encodeState(model))
        assertEquals("Unsaved task", returned.draft.summary)
        assertEquals("Draft body", returned.draft.description)
        assertEquals(7L, returned.draft.expectedVersion)
        assertTrue(returned.editing)
    }

    @Test fun `an unloaded specification owner editor retains the requested owner without saving`() = runTest {
        val model = decodeClientState<WorkOpsSpecDetailModel>("""{"id":"spec","editingOwner":true,"editVersion":7}""")
        model.saveOwner("owner", noWrites)
        refused(model.feedback, "Open the owner editor before saving.")
        val returned = decodeClientState<WorkOpsSpecDetailModel>(encodeState(model))
        assertEquals("owner", returned.ownerDraft)
        assertEquals(7L, returned.editVersion)
        assertTrue(returned.editingOwner)
    }

    @Test fun `an unloaded requirement editor retains assignment and revision without saving`() = runTest {
        val model = decodeClientState<WorkOpsRequirementDetailModel>("""{"id":"requirement","editing":true,"editVersion":7}""")
        model.save("priority", "assignee", noWrites)
        refused(model.feedback, "Open the requirement editor before saving.")
        val returned = decodeClientState<WorkOpsRequirementDetailModel>(encodeState(model))
        assertEquals("priority", returned.priorityDraft)
        assertEquals("assignee", returned.assigneeDraft)
        assertEquals(7L, returned.editVersion)
    }

    @Test fun `catalog state without a successful read cannot overwrite an inspected entry`() = runTest {
        val model = decodeClientState<WorkOpsGlobalCatalogModel>("""{"kind":"priorities","editing":true,"draft":{"id":"priority","version":7}}""")
        model.save("Unsaved priority", "Draft", "#123456", "star", "", "3", "", "", noWrites)
        refused(model.feedback, "Reload the catalog before saving.")
        val returned = decodeClientState<WorkOpsGlobalCatalogModel>(encodeState(model))
        assertEquals("priority", returned.draft.id)
        assertEquals("Unsaved priority", returned.draft.name)
        assertEquals(7L, returned.draft.version)
        assertFalse(returned.loaded)
    }

    @Test fun `unloaded workflow scheme state retains its default workflow and version`() = runTest {
        val model = decodeClientState<WorkOpsWorkflowsModel>("""{"kind":"schemes","editing":true,"draft":{"kind":"schemes","id":"scheme","version":7}}""")
        model.save("Unsaved scheme", "Draft", "default-workflow", noWrites)
        refused(model.feedback, "Reload workflows before saving.")
        val returned = decodeClientState<WorkOpsWorkflowsModel>(encodeState(model))
        assertEquals("default-workflow", returned.draft.defaultId)
        assertEquals("Unsaved scheme", returned.draft.name)
        assertEquals(7L, returned.draft.version)
    }

    @Test fun `unloaded calendar state retains structured hours without creating a calendar`() = runTest {
        val model = decodeClientState<WorkOpsSlaModel>("""{"kind":"calendars","editing":true,"draft":{}}""")
        model.save("Unsaved calendar", "Draft", "UTC", "{}", "[]", noWrites)
        refused(model.feedback, "Reload SLA settings before saving.")
        val returned = decodeClientState<WorkOpsSlaModel>(encodeState(model))
        assertEquals("Unsaved calendar", returned.draft.name)
        assertEquals("UTC", returned.draft.timeZone)
        assertEquals("{}", returned.draft.weeklyHours)
        assertEquals("[]", returned.draft.holidays)
    }

    @Test fun `unloaded environment state cannot create a target and retains its native form values`() = runTest {
        val model = decodeClientState<WorkOpsEnvironmentsModel>("""{"programId":"program","creating":true,"draft":{}}""")
        model.save("Unsaved environment", "env", "Draft", "type", true, true, "group", noWrites)
        refused(model.feedback, "Reload an available program before saving.")
        val returned = decodeClientState<WorkOpsEnvironmentsModel>(encodeState(model))
        assertEquals("Unsaved environment", returned.draft.name)
        assertEquals("env", returned.draft.key)
        assertTrue(returned.draft.autoPromote)
        assertTrue(returned.draft.manualKey)
        assertEquals("group", returned.groupDraft)
    }

    @Test fun `permission state without its selected entity cannot submit a direct grant`() = runTest {
        val model = decodeClientState<WorkOpsPermissionsModel>("""{"kind":"project","entityId":"project","editing":true}""")
        model.save("group", "EDIT", noWrites)
        refused(model.feedback, "Choose an available project before adding a grant.")
        val returned = decodeClientState<WorkOpsPermissionsModel>(encodeState(model))
        assertEquals("group", returned.groupDraft)
        assertEquals("EDIT", returned.actionDraft)
        assertFalse(returned.loaded)
    }

    @Test fun `an unopened planning editor retains its project form and revision without submitting`() = runTest {
        val model = decodeClientState<WorkOpsPlanningModel>("""{"kind":"project","draft":{"id":"project","version":7,"taskCreationFormSchemaKey":"custom-task"}}""")
        model.save("APP", "Unsaved project", "Draft", "owner", "program", noWrites)
        refused(model.feedback, "Open the project editor first.")
        val returned = decodeClientState<WorkOpsPlanningModel>(encodeState(model))
        assertEquals("Unsaved project", returned.draft.name)
        assertEquals("custom-task", returned.draft.taskCreationFormSchemaKey)
        assertEquals(7L, returned.draft.version)
        assertFalse(returned.editing)
    }

    @Test fun `a sprint state without its board cannot create or execute a lifecycle action`() = runTest {
        val model = decodeClientState<WorkOpsSprintsModel>("""{"mode":"detail","sprintId":"sprint","creating":true}""")
        model.create("Unsaved sprint", "Sprint goal", noWrites)
        refused(model.feedback, "Choose an available board first.")
        val returned = decodeClientState<WorkOpsSprintsModel>(encodeState(model))
        assertEquals("Unsaved sprint", returned.nameDraft)
        assertEquals("Sprint goal", returned.goalDraft)
        returned.start("2026-11-01", "2026-11-15", "", "[]", noWrites)
        refused(returned.feedback, "Open the start dialog first.")
        returned.close("0", "{}", noWrites)
        refused(returned.feedback, "Open the completion dialog first.")
    }

    @Test fun `a milestone state without program choices retains its draft without creating`() = runTest {
        val model = decodeClientState<WorkOpsMilestonesModel>("""{"creating":true}""")
        model.create("Unsaved milestone", "Draft", "2026-12-01", "program", noWrites)
        refused(model.feedback, "Choose an available program.")
        val returned = decodeClientState<WorkOpsMilestonesModel>(encodeState(model))
        assertEquals("Unsaved milestone", returned.nameDraft)
        assertEquals("2026-12-01", returned.dateDraft)
        assertEquals("program", returned.createProgramId)
    }

    @Test fun `board state without inspected settings cannot submit a configuration overwrite`() = runTest {
        val model = decodeClientState<WorkOpsBoardConfigurationModel>("""{"id":"board","tab":"settings"}""")
        model.saveSettings("Unsaved board", "KANBAN", "NONE", "[]", noWrites)
        refused(model.feedback, "Reload the board settings first.")
        assertFalse(model.columnsLoaded)
    }

    @Test fun `quick add state without an open board retains the task draft without creating`() = runTest {
        val model = decodeClientState<WorkOpsBoardViewModel>("""{"id":"board","adding":true}""")
        model.quickAdd("Unsaved task", "project", noWrites)
        refused(model.feedback, "Choose an open board or sprint before adding a task.")
        val returned = decodeClientState<WorkOpsBoardViewModel>(encodeState(model))
        assertEquals("Unsaved task", returned.summaryDraft)
        assertEquals("project", returned.projectDraft)
        assertFalse(returned.cardsLoaded)
    }

    @Test fun `unloaded project catalogs retain version dates and reject creation`() = runTest {
        val model = decodeClientState<WorkOpsProjectCatalogModel>("""{"projectId":"project","kind":"versions","creating":true}""")
        model.create("Unsaved version", "Draft", "UNASSIGNED", "#123456", "2026-11-01", "2026-12-01", noWrites)
        refused(model.feedback, "Reload the catalog before creating a version.")
        val returned = decodeClientState<WorkOpsProjectCatalogModel>(encodeState(model))
        assertEquals("Unsaved version", returned.nameDraft)
        assertEquals("2026-11-01", returned.startDateDraft)
        assertEquals("2026-12-01", returned.releaseDateDraft)
    }

    @Test fun `unloaded dependency choices retain the constraint without declaring a relationship`() = runTest {
        val model = decodeClientState<WorkOpsProjectDependenciesModel>("""{"projectId":"project","creating":true}""")
        model.create("provider", "BUILD", ">=1.0", noWrites)
        refused(model.feedback, "Reload the dependency choices first.")
        val returned = decodeClientState<WorkOpsProjectDependenciesModel>(encodeState(model))
        assertEquals("provider", returned.providerDraft)
        assertEquals(">=1.0", returned.constraintDraft)
    }

    @Test fun `unloaded repository choices retain their identity without attaching a repository`() = runTest {
        val model = decodeClientState<WorkOpsProjectRepositoriesModel>("""{"projectId":"project"}""")
        model.attach("repository", noWrites)
        refused(model.feedback, "Choose an available repository.")
        val returned = decodeClientState<WorkOpsProjectRepositoriesModel>(encodeState(model))
        assertEquals("repository", returned.repositoryDraft)
        assertFalse(returned.loaded)
    }

    @Test fun `unloaded analytics associations retain their identifier without attaching it`() = runTest {
        for (kind in listOf("applications", "services")) {
            val model = decodeClientState<WorkOpsProjectTelemetryModel>("""{"projectId":"project","kind":"$kind"}""")
            model.add("identifier", noWrites)
            refused(model.feedback, "Reload the identifiers first.")
            val returned = decodeClientState<WorkOpsProjectTelemetryModel>(encodeState(model))
            assertEquals("identifier", returned.draft)
            assertFalse(returned.loaded)
        }
    }
}
