package bosca.ai.kit.agents

import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.chat.model.AnalyticsInvestigationStep
import bosca.ai.kit.agents.analytics.AnalyticsResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyticsResponseSerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `AnalyticsResponse decodes a pre-extension snapshot`() {
        val response = json.decodeFromString(
            AnalyticsResponse.serializer(),
            """{"summary":"There are 42 users.","query":"SELECT 42","columns":["total"],"rows":[["42"]],"visualization":"number"}""",
        )
        assertEquals("number", response.visualization)
        assertNull(response.savedQueryId)
        assertEquals(emptyList(), response.annotations)
    }

    @Test
    fun `Kit analytics response round-trips artifact references and investigation`() {
        val response = KitResponse.Analytics(
            summary = "Done",
            savedQueryId = "query-id",
            savedQueryKey = "weekly-signups",
            visualizationId = "visualization-id",
            dashboardId = "dashboard-id",
            investigation = listOf(
                AnalyticsInvestigationStep(
                    1, AnalyticsInvestigationKind.ARTIFACT, "create_saved_query", resultSummary = "saved query weekly-signups",
                    startedAt = "2026-07-21T12:00:00Z", purpose = "Persist the analysis", conclusion = "Saved",
                ),
            ),
        )

        val decoded = json.decodeFromString(KitResponse.Analytics.serializer(), json.encodeToString(KitResponse.Analytics.serializer(), response))
        assertEquals(response, decoded)
    }

    @Test
    fun `Kit analytics response decodes fields written before provenance existed`() {
        val response = json.decodeFromString(
            KitResponse.Analytics.serializer(),
            """{"summary":"Legacy","query":"SELECT 1","columns":["value"],"rows":[["1"]],"visualization":"table"}""",
        )
        assertEquals(emptyList(), response.investigation)
        assertNull(response.dashboardId)
    }
}
