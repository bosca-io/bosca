package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsReleaseHealthTest {
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun counter(value: Long, time: String = "2027-01-01T00:00:00Z") = """{"value":$value,"values":[{"time":"$time","value":$value}]}"""
    private fun errors() = json("""{"analytics":{"errors":{"open":{"total":70,"edges":[{"lastSeen":"2027-01-01T01:00:00+01:00"}]},"fatal":{"total":60}}}}""")
    private fun http(time: String = "2027-01-01T00:00:00Z") = json("""{"analytics":{"counters":{"success":${counter(90)},"client":${counter(5, time)},"server":${counter(5)}}}}""")

    @Test fun `sessions and complete error group totals load independently without a fifty group cap`() = runTest {
        val model = WorkOpsReleaseAppHealth("mobile")
        model.refresh(context { operation, variables -> when (operation) {
            "WorkOpsReleaseAppSessions" -> { assertEquals("sessions.mobile", variables.getValue("id").jsonPrimitive.content); json("""{"analytics":{"counters":{"byId":${counter(7)}}}}""") }
            "WorkOpsReleaseAppErrors" -> { assertEquals("mobile", variables.getValue("app").jsonPrimitive.content); errors() }
            else -> error(operation)
        } })
        assertTrue(model.sessionsLoaded); assertTrue(model.errorsLoaded); assertEquals(7L, model.sessions?.value); assertEquals(70L, model.openGroups); assertEquals(60L, model.fatalGroups)
        assertEquals("2027-01-01T00:00:00Z", model.latestError); assertEquals("error", model.tone); assertTrue(model.sessionsChecked.isNotBlank()); assertTrue(model.errorsChecked.isNotBlank())
        model.refresh(context { operation, _ -> if (operation == "WorkOpsReleaseAppSessions") json("""{"analytics":{"counters":{"byId":${counter(3)}}}}""") else error("Analytics manager required") })
        assertTrue(model.sessionsLoaded); assertFalse(model.errorsLoaded); assertEquals(3L, model.sessions?.value); assertEquals(70L, model.openGroups); assertTrue(model.errorFeedback.failed); assertEquals("neutral", model.tone)
    }

    @Test fun `failed or malformed session reads retain stale history while error group reads continue`() = runTest {
        val old = WorkOpsHealthCounter(5, listOf(WorkOpsHealthSample("2027-01-01T00:00:00Z", 5)))
        val model = WorkOpsReleaseAppHealth("mobile", sessions = old, sessionsLoaded = true)
        model.refresh(context { operation, _ -> if (operation == "WorkOpsReleaseAppSessions") json("""{"analytics":{"counters":{"byId":${counter(9, "bad-time")}}}}""") else errors() })
        assertFalse(model.sessionsLoaded); assertTrue(model.sessionFeedback.failed); assertEquals(old, model.sessions); assertTrue(model.errorsLoaded)
        assertFailsWith<CancellationException> { model.refresh(context { _, _ -> throw CancellationException("Navigation") }) }
    }

    @Test fun `service aggregates keep response classes separate and invalid history cannot partially replace a snapshot`() = runTest {
        val model = WorkOpsReleaseServiceHealth("api")
        model.refresh(context { operation, variables ->
            assertEquals("WorkOpsReleaseServiceHttp", operation)
            assertEquals(listOf("http.api.2xx", "http.api.4xx", "http.api.5xx"), listOf("success", "client", "server").map { variables.getValue(it).jsonPrimitive.content }); http()
        })
        assertTrue(model.loaded); assertEquals(100L, model.total); assertEquals(10.0, model.errorRate); assertEquals("10.00%", model.errorRateLabel); assertEquals("error", model.tone)
        val before = listOf(model.success, model.client, model.server)
        model.refresh(context { _, _ -> http("bad-time") })
        assertFalse(model.loaded); assertTrue(model.feedback.failed); assertEquals(before, listOf(model.success, model.client, model.server)); assertEquals("neutral", model.tone)
        assertFailsWith<CancellationException> { model.refresh(context { _, _ -> throw CancellationException("Navigation") }) }
    }

    @Test fun `verified zero responses differ from missing counters and unknown sources do not trigger telemetry reads`() = runTest {
        val model = WorkOpsReleaseServiceHealth("api")
        assertNull(model.total); assertEquals("Unavailable", model.errorRateLabel)
        val zero = WorkOpsHealthCounter(0, emptyList()); model.success = zero; model.client = zero; model.server = zero; model.loaded = true
        assertEquals(0L, model.total); assertEquals("0.00%", model.errorRateLabel); assertEquals("neutral", model.tone)
        val health = WorkOpsReleaseHealthModel("release", "old", applications = listOf(WorkOpsReleaseAppHealth("old", sessions = zero, sessionsLoaded = true)))
        health.refreshContext("new", context { operation, _ -> assertEquals("WorkOpsReleaseComponents", operation); error("Sources unavailable") })
        assertEquals("new", health.readContext); assertFalse(health.sources.loaded); assertFalse(health.applications.single().sessionsLoaded)
        health.refreshContext("", context { _, _ -> error("Still unavailable") }); assertEquals("new", health.readContext)
    }
}
