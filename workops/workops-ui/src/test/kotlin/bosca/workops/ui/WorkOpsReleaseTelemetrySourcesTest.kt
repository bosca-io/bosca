package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsReleaseTelemetrySourcesTest {
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun components() = json("""{"workOps":{"crossProject":{"versionsForRelease":[{"releaseId":"release","projectId":"project","versionId":"v1","deploymentOrder":0,"deploymentStatus":"PENDING","deployedAt":null,"rollbackVersionId":null},{"releaseId":"release","projectId":"private","versionId":"hidden","deploymentOrder":1,"deploymentStatus":"PENDING","deployedAt":null,"rollbackVersionId":null}]}}}""")
    private fun projects() = json("""{"workOps":{"projects":{"all":[{"id":"project","key":"APP","name":"Application"}]}}}""")
    private fun versions(empty: Boolean = false) = json(if (empty) """{"workOps":{"versions":{"byProject":[]}}}""" else """{"workOps":{"versions":{"byProject":[{"id":"v1","name":"1.0.0","description":null,"released":false,"archived":false,"startDate":null,"releaseDate":null,"sequenceNumber":1,"version":0}]}}}""")
    private fun links() = json("""{"workOps":{"projects":{"project":{"id":"project","analyticsApplications":[{"id":"link","applicationId":"mobile"},{"id":"duplicate","applicationId":"mobile"}],"analyticsServices":[{"id":"service","service":"api"}]}}}}""")

    @Test fun `source resolution skips hidden projects and retains actual version identity and authorized labels`() = runTest {
        val queried = mutableListOf<String>(); val model = WorkOpsReleaseTelemetrySourcesModel("release")
        model.refresh(context { operation, variables -> when (operation) {
            "WorkOpsReleaseComponents" -> components()
            "WorkOpsVersionProjects" -> projects()
            "WorkOpsProjectTelemetry" -> { queried += variables.getValue("id").jsonPrimitive.content; links() }
            "WorkOpsDeliveryVersions" -> { queried += variables.getValue("project").jsonPrimitive.content; versions() }
            else -> error(operation)
        } })
        assertTrue(model.loaded); assertFalse(model.feedback.failed); assertEquals(listOf("project", "project"), queried)
        assertEquals(1, model.unavailableProjects); assertEquals(1, model.applications.size)
        assertEquals(WorkOpsReleaseTelemetrySource("project", "APP · Application", "mobile", "v1", "1.0.0"), model.applications.single())
        assertEquals("api", model.services.single().identifier)
    }

    @Test fun `an unavailable version name is retained as unknown rather than widened to all versions`() = runTest {
        val model = WorkOpsReleaseTelemetrySourcesModel("release")
        model.refresh(context { operation, _ -> when (operation) {
            "WorkOpsReleaseComponents" -> components(); "WorkOpsVersionProjects" -> projects(); "WorkOpsProjectTelemetry" -> links(); "WorkOpsDeliveryVersions" -> versions(true); else -> error(operation)
        } })
        assertTrue(model.loaded); assertEquals("v1", model.applications.single().versionId); assertEquals("", model.applications.single().versionName)
    }

    @Test fun `service-only links do not depend on an application version lookup`() = runTest {
        val model = WorkOpsReleaseTelemetrySourcesModel("release")
        model.refresh(context { operation, _ -> when (operation) {
            "WorkOpsReleaseComponents" -> components()
            "WorkOpsVersionProjects" -> projects()
            "WorkOpsProjectTelemetry" -> json("""{"workOps":{"projects":{"project":{"id":"project","analyticsApplications":[],"analyticsServices":[{"id":"service","service":"api"}]}}}}""")
            else -> error("HTTP health must not depend on $operation")
        } })
        assertTrue(model.loaded); assertFalse(model.feedback.failed); assertTrue(model.applications.isEmpty())
        assertEquals("api", model.services.single().identifier)
    }

    @Test fun `failed source refresh retains explicit stale facts and does not manufacture an empty result`() = runTest {
        val old = WorkOpsReleaseTelemetrySource("project", "APP · Application", "mobile", "v1", "1.0")
        val model = WorkOpsReleaseTelemetrySourcesModel("release", applications = listOf(old), loaded = true)
        model.refresh(context { _, _ -> error("Source read unavailable") })
        assertFalse(model.loaded); assertTrue(model.feedback.failed); assertEquals(listOf(old), model.applications)
        assertFailsWith<CancellationException> { model.refresh(context { _, _ -> throw CancellationException("Navigation") }) }
    }

    @Test fun `an actually empty release clears stale sources without querying unrelated project catalogs`() = runTest {
        val model = WorkOpsReleaseTelemetrySourcesModel("release", applications = listOf(WorkOpsReleaseTelemetrySource("project", "Old", "old")), loaded = true)
        model.refresh(context { operation, _ -> assertEquals("WorkOpsReleaseComponents", operation); json("""{"workOps":{"crossProject":{"versionsForRelease":[]}}}""") })
        assertTrue(model.loaded); assertTrue(model.applications.isEmpty()); assertTrue(model.services.isEmpty()); assertEquals(0, model.unavailableProjects)
    }
}
