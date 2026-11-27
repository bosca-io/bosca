package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkOpsActionSelectionTest {
    private val noRequests = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement =
            throw AssertionError("An unselected action attempted $operationName")
    })

    @Test fun `unloaded task actions preserve independent drafts and require actual choices`() = runTest {
        val model = WorkOpsTaskDetailModel("task")
        model.beginLink()
        assertEquals("", model.linkType)
        model.linkType = "retained"
        model.beginLink()
        assertEquals("retained", model.linkType)
        model.cancelLink()
        model.link("", "", noRequests)
        assertEquals("Enter a task key.", model.feedback.message)
        model.link("APP-7", "missing", noRequests)
        assertEquals("Choose a link type.", model.feedback.message)
        assertEquals("APP-7", model.linkTarget)
        model.addProject("missing", noRequests)
        assertEquals("Choose an available project.", model.feedback.message)
        model.deleteWork("missing", true, noRequests)
        assertEquals("Reload this task's work log.", model.feedback.message)
        model.completeTransition("", noRequests)
        assertEquals("Choose a transition first.", model.feedback.message)
        model.pendingTransition = "transition"
        model.completeTransition("missing", noRequests)
        assertEquals("Choose a resolution.", model.feedback.message)
        model.logWork("", "", "Retained comment", "5m", noRequests)
        assertEquals("Enter time spent, such as 1h 30m.", model.feedback.message)
        assertEquals("Retained comment", model.workComment)
        model.workStarted = "2026-11-17T10:00"
        model.beginWork()
        assertEquals("2026-11-17T10:00", model.workStarted)
        model.cancelWork()
        assertFalse(model.loggingWork)
    }

    @Test fun `unselected specification dialogs cannot rename transition or overwrite ownership`() = runTest {
        val model = WorkOpsSpecDetailModel("spec")
        model.saveOwner("owner", noRequests)
        assertEquals("Open the owner editor before saving.", model.feedback.message)
        model.rename("Retained title", noRequests)
        assertTrue(model.feedback.failed)
        model.beginRename()
        assertEquals("", model.nameDraft)
        model.rename("Retained title", noRequests)
        assertTrue(model.feedback.failed)
        assertEquals("Retained title", model.nameDraft)
        model.beginTransition("missing", noRequests)
        assertEquals("Reload the specification first.", model.feedback.message)
        model.completeTransition("", noRequests)
        assertEquals("Choose a transition first.", model.feedback.message)
        model.pendingTransition = "transition"
        model.completeTransition("missing", noRequests)
        assertEquals("Choose a resolution.", model.feedback.message)
        model.cancelRename()
        model.cancelOwner()
        model.cancelTransition()
        assertFalse(model.renaming)
        assertFalse(model.editingOwner)
        assertEquals("", model.pendingTransition)
    }

    @Test fun `unselected requirement dialogs retain title and assignment without writes`() = runTest {
        val model = WorkOpsRequirementDetailModel("requirement")
        model.save("priority", "assignee", noRequests)
        assertEquals("Open the requirement editor before saving.", model.feedback.message)
        model.rename("Retained title", noRequests)
        assertTrue(model.feedback.failed)
        model.beginRename()
        model.rename("Retained title", noRequests)
        assertEquals("Retained title", model.nameDraft)
        model.beginTransition("missing", noRequests)
        assertTrue(model.feedback.failed)
        model.completeTransition("", noRequests)
        assertEquals("Choose a transition first.", model.feedback.message)
        model.pendingTransition = "transition"
        model.completeTransition("missing", noRequests)
        assertEquals("Choose a resolution.", model.feedback.message)
        model.cancelRename()
        model.cancelEdit()
        model.cancelTransition()
        assertFalse(model.renaming)
        assertFalse(model.editing)
    }

    @Test fun `repository setup and deletion require a currently inspected association`() = runTest {
        val model = WorkOpsProjectRepositoriesModel("project")
        assertFailsWith<IllegalArgumentException> { model.beginDelete("missing", "repository") }
        assertFailsWith<IllegalArgumentException> { model.beginSetup("missing", "repository") }
        model.loaded = true
        assertFailsWith<IllegalArgumentException> { model.beginDelete("missing", "repository") }
        assertFailsWith<IllegalArgumentException> { model.beginSetup("missing", "repository") }
        model.delete(true, noRequests)
        assertEquals("Choose a repository association first.", model.feedback.message)
        model.generate("Human Author", "human@example.test", noRequests)
        assertEquals("Open deploy-config setup first.", model.feedback.message)
        assertEquals("Human Author", model.authorNameDraft)
        model.cancelDelete()
        model.cancelSetup()
        model.dismissGenerated()
        assertNull(model.setupTarget)
        assertNull(model.generated)
    }

    @Test fun `dependency and telemetry removal require selected rows from successful reads`() = runTest {
        val dependency = WorkOpsProjectDependenciesModel("project")
        assertFailsWith<IllegalArgumentException> { dependency.beginDelete("missing") }
        dependency.loaded = true
        assertFailsWith<IllegalArgumentException> { dependency.beginDelete("missing") }
        dependency.delete(true, noRequests)
        assertTrue(dependency.feedback.failed)
        dependency.cancelDelete()
        assertNull(dependency.deleteTarget)
        for (kind in listOf("applications", "services")) {
            val telemetry = WorkOpsProjectTelemetryModel("project", kind)
            assertFailsWith<IllegalArgumentException> { telemetry.beginDelete("missing", "Retained value") }
            telemetry.loaded = true
            assertFailsWith<IllegalArgumentException> { telemetry.beginDelete("missing", "Retained value") }
            telemetry.delete(true, noRequests)
            assertEquals("Choose an identifier first.", telemetry.feedback.message)
            assertEquals("Retained value", telemetry.draft)
            telemetry.cancelDelete()
            assertNull(telemetry.deleteTarget)
        }
    }

    @Test fun `catalog deletes reject absent inspected entries and workflow sections are explicit`() = runTest {
        val catalog = WorkOpsGlobalCatalogModel("priorities")
        assertFailsWith<IllegalArgumentException> { catalog.open("missing") }
        catalog.loaded = true
        assertFailsWith<IllegalArgumentException> { catalog.open("missing") }
        assertFailsWith<IllegalArgumentException> { catalog.beginDelete("missing") }
        catalog.delete(true, noRequests)
        assertTrue(catalog.feedback.failed)
        val workflow = WorkOpsWorkflowsModel()
        assertFailsWith<IllegalArgumentException> { workflow.open("workflows", "missing") }
        workflow.loaded = true
        for (section in listOf("unknown", "workflows", "schemes")) {
            assertFailsWith<IllegalArgumentException> { workflow.open(section, "missing") }
        }
        workflow.delete(true, noRequests)
        assertEquals("Choose a workflow or scheme first.", workflow.feedback.message)
        workflow.cancelDelete()
        assertNull(workflow.deleting)
    }

    @Test fun `environment and group grants cannot delete an unselected entry`() = runTest {
        val types = WorkOpsEnvironmentTypesModel()
        assertFailsWith<IllegalArgumentException> { types.open("missing") }
        assertFailsWith<IllegalArgumentException> { types.beginDelete("missing") }
        types.loaded = true
        assertFailsWith<IllegalArgumentException> { types.open("missing") }
        assertFailsWith<IllegalArgumentException> { types.beginDelete("missing") }
        types.delete(true, noRequests)
        assertEquals("Choose an environment type first.", types.feedback.message)
        val environment = WorkOpsEnvironmentsModel()
        assertFailsWith<IllegalArgumentException> { environment.beginDelete("missing") }
        environment.loaded = true
        assertFailsWith<IllegalArgumentException> { environment.beginDelete("missing") }
        environment.delete(true, noRequests)
        assertTrue(environment.feedback.failed)
        val grants = WorkOpsPermissionsModel()
        assertFailsWith<IllegalArgumentException> { grants.open() }
        assertFailsWith<IllegalArgumentException> { grants.beginDelete("missing") }
        grants.loaded = true
        assertFailsWith<IllegalArgumentException> { grants.beginDelete("missing") }
        grants.delete(true, noRequests)
        assertTrue(grants.feedback.failed)
    }
}
