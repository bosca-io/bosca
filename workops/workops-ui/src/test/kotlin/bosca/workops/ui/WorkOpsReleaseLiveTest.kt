package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsReleaseLiveTest {
    private val known = WorkOpsReleaseTelemetrySource("project", "APP · Application", "mobile", "version", "1.0.0")
    private fun sourceModel() = WorkOpsReleaseTelemetrySourcesModel("release", applications = listOf(known, known.copy(versionId = "unknown", versionName = "")), loaded = true)
    private fun context(read: suspend (String) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = read(requireNotNull(operationName))
    })
    private val empty = context { operation ->
        assertEquals("WorkOpsReleaseComponents", operation)
        Json.parseToJsonElement("""{"workOps":{"crossProject":{"versionsForRelease":[]}}}""")
    }

    @Test fun `the default stream scope retains real version names and never widens unknown versions`() {
        val model = WorkOpsReleaseLiveModel("release", sources = sourceModel())
        assertEquals(1, model.streams.size); assertEquals("1.0.0", model.streams.single().appVersion); assertEquals(1, model.unavailableVersions)
        assertTrue(model.subscription.startsWith("subscription WorkOpsReleaseLiveSessions"))
        model.thisReleaseOnly = false
        assertEquals(1, model.streams.size); assertNull(model.streams.single().appVersion); assertEquals(0, model.unavailableVersions)
        model.sources.loaded = false; assertTrue(model.streams.isEmpty())
    }

    @Test fun `parent metadata changes retain manual reconnect identity while explicit scope changes reauthorize sources`() = runTest {
        val model = WorkOpsReleaseLiveModel("release", "1", sourceModel())
        model.refreshContext("2", empty); assertEquals("2", model.readContext); assertEquals(0L, model.streamRevision)
        model.refreshContext("", empty); assertEquals("2", model.readContext); assertEquals(1L, model.streamRevision)
        model.toggleScope(empty); assertFalse(model.thisReleaseOnly); assertEquals(2L, model.streamRevision); assertTrue(model.sources.loaded); assertTrue(model.streams.isEmpty())
        model.openMap(); assertTrue(model.expanded); model.closeMap(); assertFalse(model.expanded)
    }

    @Test fun `denied metadata cannot leave active sources and cancellation retains its boundary`() = runTest {
        val model = WorkOpsReleaseLiveModel("release", "1", sourceModel())
        model.refreshContext("2", context { error("Permission denied") })
        assertFalse(model.sources.loaded); assertTrue(model.sources.feedback.failed); assertTrue(model.streams.isEmpty()); assertTrue(model.sources.applications.isNotEmpty())
        assertFailsWith<CancellationException> { model.refreshContext("3", context { throw CancellationException("Navigation") }) }
        assertEquals("2", model.readContext)
    }

    @Test fun `project map series contain no fabricated locations and keep labels distinct`() {
        val sources = sourceModel(); sources.applications = listOf(known, known.copy(projectId = "other", projectLabel = "OTHER · Other application"))
        val model = WorkOpsReleaseLiveModel("release", sources = sources)
        val option = model.chart.option; val series = option.getValue("series").jsonArray
        assertEquals("world", option.getValue("geo").jsonObject.getValue("map").jsonPrimitive.content)
        assertEquals(2, series.size); assertTrue(series.all { it.jsonObject.getValue("data").jsonArray.isEmpty() })
        assertEquals(2, series.map { it.jsonObject.getValue("itemStyle").jsonObject.getValue("color") }.distinct().size)
        assertTrue(model.streamsJson.contains("OTHER")); assertFalse(Json.encodeToString(WorkOpsReleaseLiveModel.serializer(), model).contains("FeatureCollection"))
    }
}
