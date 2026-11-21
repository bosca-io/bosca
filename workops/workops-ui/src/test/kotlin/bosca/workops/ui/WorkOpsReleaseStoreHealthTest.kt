package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsReleaseStoreHealthTest {
    @Test fun `unknown and nonfinite rollout and empty review history stay unavailable`() = runTest {
        val model = WorkOpsReleaseStoreHealthModel("release")
        model.refreshContext("1", successful(response(observations = listOf(
            observation("store.reviews", "2027-01-01T10:00:00Z", """{"reviews":[{"rating":null},{"rating":"5"}]}""")
        ))))
        val row = model.rows.single()
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals("Unavailable", row.copy(state = row.state.copy(rolloutPercentage = value)).rolloutLabel)
        }
        assertEquals("Unavailable", row.ratingLabel)
        assertEquals(emptyList(), row.ratingPoints)
        assertEquals("Unavailable", row.crashRateLabel)
        assertEquals("Unavailable", row.anrRateLabel)
    }
    private fun json(source: String) = Json.parseToJsonElement(source)
    private fun context(read: suspend (String) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = read(requireNotNull(operationName))
    })
    private fun state(store: String = "GOOGLE_PLAY", project: String = "project") = json("""{
        "projectId":"$project","versionId":"version","environmentId":"environment","environmentKey":"production",
        "store":"$store","applicationId":"io.app","appVersion":"1.0.0","buildNumber":"42","track":"production","rolloutPercentage":0.0,
        "releaseState":"halted","reviewState":"WAITING_FOR_REVIEW","betaReviewState":"APPROVED","buildProcessingState":"VALID","phasedReleaseState":"ACTIVE",
        "testFlightGroups":["Internal"],"testFlightCrashFeedback":[{"id":"feedback","comment":"<tester comment>","email":null,"deviceModel":"Device","osVersion":"1","createdAt":"2027-01-01T10:00:00Z"}]
    }""")
    private fun observation(type: String, time: String, payload: String, store: String = "GOOGLE_PLAY", app: String = "io.app", version: String = "1.0.0") = buildJsonObject {
        put("store", store); put("applicationId", app); put("appVersion", version); put("telemetryType", type); put("observedAt", time); put("payload", json(payload))
    }
    private fun response(states: List<JsonElement> = listOf(state()), observations: List<JsonElement> = emptyList()) = buildJsonObject {
        putJsonObject("workOps") { putJsonObject("multiRepo") { putJsonObject("releaseStoreTelemetry") { put("states", JsonArray(states)); put("observations", JsonArray(observations)) } } }
    }
    private val projects get() = json("""{"workOps":{"projects":{"all":[{"id":"project","key":"APP","name":"Application"}]}}}""")
    private fun successful(response: JsonElement) = context { operation -> when (operation) {
        "WorkOpsReleaseStoreTelemetry" -> response; "WorkOpsVersionProjects" -> projects; else -> error(operation)
    } }

    @Test fun `store histories match actual identities and timestamps with authorized labels and finite numeric metrics`() = runTest {
        val model = WorkOpsReleaseStoreHealthModel("release")
        model.refreshContext("1", successful(response(listOf(state(), state("APP_STORE", "private")), listOf(
            observation("store.vitals", "2027-01-01T11:00:00Z", """{"crashRate":0.0,"anrRate":"0.2"}"""),
            observation("store.vitals", "2027-01-01T09:00:00Z", """{"crashRate":0.8,"anrRate":0.1}"""),
            observation("store.vitals", "2027-01-01T12:00:00+01:00", """{"crashRate":0.5,"anrRate":0.2}""", "APP_STORE"),
            observation("store.vitals", "2027-01-01T13:00:00Z", """{"crashRate":999}""", app = "other"),
            observation("store.vitals", "2027-01-01T13:00:00Z", """{"crashRate":888}""", version = "2.0.0"),
            observation("store.reviews", "2027-01-01T12:00:00Z", """{"reviews":[{"rating":4,"body":"<review>"},{"rating":5},{"rating":"1","body":true},null,false]}"""),
            observation("store.reviews", "2027-01-01T09:00:00Z", """{"reviews":[{"rating":3}]}"""),
        ))))
        assertTrue(model.loaded); assertFalse(model.feedback.failed); assertTrue(model.checked.isNotBlank()); assertEquals("1", model.readContext)
        val play = model.rows[0]; val apple = model.rows[1]
        assertEquals("APP · Application", play.projectLabel); assertEquals("Project unavailable", apple.projectLabel)
        assertEquals("0.000%", play.crashRateLabel); assertEquals("Unavailable", play.anrRateLabel); assertEquals("0.00%", play.rolloutLabel)
        assertEquals("0.500%", apple.crashRateLabel); assertEquals("0.200%", apple.anrRateLabel)
        assertEquals("4.50", play.ratingLabel); assertEquals(3, play.reviews.size); assertEquals("<review>", play.reviews.first().body)
        assertEquals("Unavailable", play.reviews.last().ratingLabel); assertEquals("No review text", play.reviews.last().body)
        assertEquals(listOf("Internal"), apple.state.testFlightGroups); assertEquals("<tester comment>", apple.state.testFlightCrashFeedback.single().comment)
        assertEquals("APP_STORE", apple.state.store); assertEquals("APPROVED", apple.state.betaReviewState)
        val points = play.crashChart.option.getValue("series").jsonArray.single().jsonObject.getValue("data").jsonArray
        assertEquals(2, points.size); assertEquals("2027-01-01T09:00:00Z", points.first().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(0.0, points.last().jsonObject.getValue("value").jsonArray[1].jsonPrimitive.double)
        assertEquals(1, apple.crashPoints.size); assertEquals("2027-01-01T11:00:00Z", apple.observations.single().time)
    }

    @Test fun `unknown payloads and nonnumeric metrics do not manufacture zero observations`() {
        val observation = WorkOpsStoreObservation("GOOGLE_PLAY", "app", "1", "store.vitals", "2027-01-01T00:00:00Z", json("""{"crashRate":"0","anrRate":false}"""))
        assertNull(observation.number("crashRate")); assertNull(observation.number("anrRate")); assertNull(observation.number("missing"))
        assertTrue(observation.reviews.isEmpty())
        assertNull(observation.copy(payload = JsonArray(emptyList())).number("crashRate"))
        assertNull(observation.copy(payload = buildJsonObject { put("crashRate", Double.POSITIVE_INFINITY) }).number("crashRate"))
    }

    @Test fun `a malformed observation or denied read retains one stale snapshot and propagates cancellation`() = runTest {
        val model = WorkOpsReleaseStoreHealthModel("release")
        model.refreshContext("1", successful(response())); val previous = model.rows; val checked = model.checked
        model.refreshContext("2", successful(response(observations = listOf(observation("store.vitals", "invalid", "{}")))))
        assertFalse(model.loaded); assertTrue(model.feedback.failed); assertSame(previous, model.rows); assertEquals(checked, model.checked); assertEquals("2", model.readContext)
        model.refreshContext("", context { error("Permission denied") })
        assertFalse(model.loaded); assertSame(previous, model.rows); assertEquals("2", model.readContext)
        assertFailsWith<CancellationException> { model.refreshContext("3", context { throw CancellationException("Navigation") }) }
        assertEquals("2", model.readContext)
    }

    @Test fun `label failure cannot publish a partially updated telemetry snapshot`() = runTest {
        val model = WorkOpsReleaseStoreHealthModel("release")
        model.refreshContext("1", successful(response())); val previous = model.rows
        model.refreshContext("2", context { operation -> when (operation) {
            "WorkOpsReleaseStoreTelemetry" -> response(listOf(state("APP_STORE")))
            "WorkOpsVersionProjects" -> error("Project names unavailable")
            else -> error(operation)
        } })
        assertFalse(model.loaded); assertSame(previous, model.rows); assertEquals("GOOGLE_PLAY", model.rows.single().state.store)
    }

    @Test fun `verified empty store targets clear stale rows without unrelated catalog reads`() = runTest {
        val model = WorkOpsReleaseStoreHealthModel("release")
        model.refreshContext("1", successful(response())); assertTrue(model.rows.isNotEmpty())
        model.refreshContext("2", context { operation -> assertEquals("WorkOpsReleaseStoreTelemetry", operation); response(emptyList()) })
        assertTrue(model.loaded); assertFalse(model.feedback.failed); assertTrue(model.rows.isEmpty()); assertEquals("2", model.readContext)
    }
}
