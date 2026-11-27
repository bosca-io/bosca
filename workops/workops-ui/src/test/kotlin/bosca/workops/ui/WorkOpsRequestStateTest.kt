package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.withRenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WorkOpsRequestStateTest {
    private fun context(query: Map<String, String>) = RenderContext(query = query, gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
            assertFalse(query.trimStart().startsWith("mutation"), "A page load attempted $operationName")
            throw IllegalStateException("Read unavailable")
        }
    })

    @Test fun `page loaders normalize missing malformed negative and overflowing offsets consistently`() = runTest {
        for ((raw, expected) in listOf(null to 0L, "invalid" to 0L, "-25" to 0L, "9999999999999999999999" to 0L, "25" to 25L)) {
            val query = if (raw == null) emptyMap() else listOf("offset", "childrenOffset", "generationsOffset", "consumersOffset", "requirementsOffset", "applicationOffset", "serviceOffset", "memberOffset").associateWith { raw }
            withRenderContext(context(query)) {
                val specification = workOpsSpecDetail()
                assertEquals(expected, specification.offset)
                assertEquals(expected, specification.childrenOffset)
                assertEquals(expected, specification.requirementsOffset)
                assertEquals(expected, specification.generationsOffset)
                assertEquals(expected, workOpsRequirementDetail().offset)
                assertEquals(expected, workOpsTaskDetail().activityOffset)
                assertEquals(expected, workOpsTaskDetail().requirementsOffset)
                assertEquals(expected, workOpsProjectDependencies("project").offset)
                assertEquals(expected, workOpsProjectDependencies("project").consumersOffset)
                assertEquals(expected, workOpsProjectRepositories("project").offset)
                assertEquals(expected, workOpsProjectCatalog("project", "versions").offset)
                assertEquals(expected, workOpsDeliveryVersionCatalog("project").offset)
                assertEquals(expected, workOpsComponentCatalog("project").offset)
                assertEquals(expected, workOpsProjectTelemetry("project", "applications").offset)
                assertEquals(expected, workOpsProjectTelemetry("project", "services").otherOffset)
                assertEquals(expected, workOpsPlanning("program").offset)
                assertEquals(expected, workOpsMilestones().offset)
                assertEquals(expected, workOpsWorkflows().offset)
                assertEquals(expected, workOpsEnvironments().offset)
                assertEquals(expected, workOpsSprints("board", "board").memberOffset)
                assertEquals(expected, workOpsRequirements("task", "/workops/tasks/task", "TASK").offset)
                assertEquals(expected, workOpsFilters().offset)
                assertEquals(expected, workOpsReleaseList().offset)
                assertEquals(expected, workOpsAutomation().offset)
                assertEquals(expected, workOpsSla().offset)
                assertEquals(expected, workOpsPermissions().offset)
                assertEquals(expected, workOpsNotifications().offset)
                assertEquals(expected, workOpsEnvironmentTypes().offset)
                assertEquals(expected, workOpsInbox().offset)
            }
        }
    }

    @Test fun `unresolved task specification and requirement keys produce not found without dependent reads`() = runTest {
        val gql = object : GraphQLClient {
            override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement =
                kotlinx.serialization.json.Json.parseToJsonElement(when (operationName) {
                    "WorkOpsTaskIdByKey" -> """{"workOps":{"tasks":{"taskByKey":null}}}"""
                    "WorkOpsSpecIdByKey" -> """{"workOps":{"specs":{"specByKey":null}}}"""
                    "WorkOpsRequirementIdByKey" -> """{"workOps":{"requirements":{"requirementByKey":null}}}"""
                    else -> throw AssertionError("Missing key attempted $operationName")
                })
        }
        val task = RenderContext(gql = gql, params = mapOf("id" to "APP-1"))
        withRenderContext(task) { assertFalse(workOpsTaskDetail().feedback.failed) }
        kotlin.test.assertTrue(task.notFound)
        val spec = RenderContext(gql = gql, params = mapOf("id" to "APP-SPEC-1"))
        withRenderContext(spec) { assertFalse(workOpsSpecDetail().feedback.failed) }
        kotlin.test.assertTrue(spec.notFound)
        val requirement = RenderContext(gql = gql, params = mapOf("id" to "APP-REQ-1"))
        withRenderContext(requirement) { assertFalse(workOpsRequirementDetail().feedback.failed) }
        kotlin.test.assertTrue(requirement.notFound)
    }

    @Test fun `unknown tabs and scopes retain the existing page defaults after read failure`() = runTest {
        withRenderContext(context(mapOf("tab" to "unknown", "state" to "unknown", "kind" to "unknown", "new" to "1"))) {
            val planning = workOpsPlanning("project")
            assertEquals("active", planning.tab)
            assertFalse(planning.editing)
            assertFalse(workOpsMilestones().creating)
            assertEquals("board", workOpsBoardConfiguration().tab)
            assertEquals("overview", workOpsProjectDetail().tab)
            assertEquals("workflows", workOpsWorkflows().kind)
            assertEquals("policies", workOpsSla().kind)
            assertEquals("portfolio", workOpsPermissions().kind)
            assertEquals("schemes", workOpsNotifications().tab)
            assertEquals("unread", workOpsInbox().tab)
            val sprint = workOpsSprints("board", "board")
            assertEquals("tasks", sprint.tab)
            assertEquals("open", sprint.state)
            assertNull(workOpsSelectedBoard(""))
        }
    }

    @Test fun `telemetry deep links select only their own row and preserve the other pane page`() = runTest {
        val query = mapOf("kind" to "services", "item" to "service", "applicationOffset" to "25", "serviceOffset" to "50")
        withRenderContext(context(query)) {
            val applications = workOpsProjectTelemetry("project", "applications")
            assertEquals("", applications.targetId)
            assertEquals(25L, applications.offset)
            assertEquals(50L, applications.otherOffset)
            val services = workOpsProjectTelemetry("project", "services")
            assertEquals("service", services.targetId)
            assertEquals(50L, services.offset)
            assertEquals(25L, services.otherOffset)
        }
    }
}
