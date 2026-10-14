package bosca.cli.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertFailsWith

class WorkOpsImplementationContractTest {
    @Test
    fun `coverage evidence round trips raw counters and gap review without loss`() {
        val coverage = CoverageEvidence(
            scope = "Changed production services", command = "./gradlew :cli:test :cli:koverXmlReport",
            report = "cli/build/reports/kover/report.xml", lineCovered = 990, lineMissed = 10,
            branchCovered = 970, branchMissed = 30, uncovered = "Configuration guard paths listed in report",
        )
        val quality = TestQualityEvidence("Behavior, permissions, rollback and retries reviewed", listOf(coverage))
        assertTrue(quality.issues().isEmpty())
        assertEquals(99.0, coverage.linePercent())
        assertEquals(97.0, coverage.branchPercent())
        val encoded = Json.encodeToString(TestQualityEvidence.serializer(), quality)
        assertEquals(quality, Json.decodeFromString(TestQualityEvidence.serializer(), encoded))
    }

    @Test
    fun `missing raw coverage counts cannot deserialize into assumed passing results`() {
        val measured = CoverageEvidence("Production service", "coverage command", "coverage.xml", 100, 0, 100, 0, "None")
        val encoded = Json.encodeToJsonElement(CoverageEvidence.serializer(), measured).jsonObject
        for (key in listOf("scope", "command", "report", "lineCovered", "lineMissed", "branchCovered", "branchMissed", "uncovered")) {
            assertFailsWith<SerializationException> { Json.decodeFromJsonElement(CoverageEvidence.serializer(), JsonObject(encoded - key)) }
        }
        val quality = Json.encodeToJsonElement(TestQualityEvidence.serializer(), TestQualityEvidence("Reviewed behavioral assertions", listOf(measured))).jsonObject
        for (key in listOf("review", "coverage")) {
            assertFailsWith<SerializationException> { Json.decodeFromJsonElement(TestQualityEvidence.serializer(), JsonObject(quality - key)) }
        }
    }
}
