package bosca.workops.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkOpsDeliveryPresentationTest {
    @Test fun `deployment artifact and automation badges preserve failure rollback and unknown outcomes`() {
        for ((status, tone) in mapOf("DEPLOYED" to "success", "DEPLOYING" to "info", "FAILED" to "error", "ROLLED_BACK" to "warning", "UNKNOWN" to "neutral")) {
            assertEquals(tone, WorkOpsReleaseComponentRecord("project", "version", status = status.lowercase()).tone)
        }
        for ((status, tone) in mapOf("PUBLISHED" to "success", "FAILED" to "error", "YANKED" to "warning", "EXPECTED" to "neutral")) {
            assertEquals(tone, WorkOpsReleaseArtifactRecord("project", "oci", "images", "app:1", status = status).tone)
        }
        for ((outcome, tone) in mapOf("OK" to "success", "SKIPPED" to "neutral", "NOT_IMPLEMENTED" to "warning", "ERROR" to "error")) {
            assertEquals(tone, WorkOpsAutomationLogRecord("log", outcome, "2026-11-17").tone)
        }
        for ((status, tone) in mapOf("CURRENT" to "success", "OUTDATED" to "warning", "UNSATISFIED" to "error")) {
            assertEquals(tone, WorkOpsProjectDependencyRecord("dependency", "consumer", "provider", "*", "BUILD", status, 7).tone)
        }
    }

    @Test fun `vendor review fallbacks distinguish text numeric ratings and unknown structured values`() {
        for (value in listOf("null", "{}", "[]", "true", "3", "\"\"", "\" \"")) {
            val review = WorkOpsStoreReview(Json.parseToJsonElement("""{"body":$value,"rating":$value}""").jsonObject)
            assertEquals("No review text", review.body, value)
            if (value == "3") assertEquals(3.0, review.rating) else assertNull(review.rating, value)
        }
        val observation = WorkOpsStoreObservation("APP_STORE", "app", "1.0", "store.reviews", "2026-11-17T10:00:00Z", Json.parseToJsonElement("[]"))
        assertEquals(emptyList(), observation.reviews)
        assertNull(observation.number("crashRate"))
    }

    @Test fun `live stream and recent task count wire values retain their actual scoped identities`() {
        val stream = assertWorkOpsSerialization(WorkOpsReleaseLiveSource.serializer(), """{
          "appId":"io.app","appVersion":null,"projectId":"project","projectLabel":"Application"
        }""", """{"appId":"io.app","appVersion":"1.0","projectId":"project","projectLabel":"Application"}""")
        assertEquals("1.0", stream.appVersion)
        val count = assertWorkOpsSerialization(WorkOpsProjectStatusCount.serializer(), """{"label":"Done","count":0,"total":0}""",
            """{"label":"Done","count":3,"total":4}""")
        assertEquals(75.0, count.percent)
        assertEquals(0.0, count.copy(count = 0, total = 0).percent)
    }
}
