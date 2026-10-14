package bosca.workops.model.spec

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*

class SpecEvidenceTest {
    private val run = TddRun("test", ValidationOutcome.PASSED, "Behavior assertions passed")
    private val cycle = TddEvidence(run.copy(outcome = ValidationOutcome.FAILED), run, run)
    private val measured = CoverageEvidence("Service", "coverage", "report.xml", 990, 10, 970, 30, "Remaining paths reviewed")

    @Test fun `TDD records require actual observations and the correct outcome for every phase`() {
        assertTrue(cycle.issues().isEmpty())
        for (bad in listOf(run.copy(command = ""), run.copy(evidence = " "), run.copy(outcome = ValidationOutcome.NOT_RUN))) {
            assertTrue(cycle.copy(green = bad).issues().isNotEmpty())
            assertTrue(cycle.copy(refactor = bad).issues().isNotEmpty())
            assertTrue(cycle.copy(red = bad).issues().isNotEmpty())
        }
        assertEquals(listOf("TDD RED must be FAILED"), cycle.copy(red = run).issues())
    }

    @Test fun `coverage validates raw counters provenance exact thresholds and absent branches`() {
        assertEquals(99.0, measured.linePercent()); assertEquals(97.0, measured.branchPercent())
        assertTrue(measured.issues().isEmpty())
        for (bad in listOf(measured.copy(scope = ""), measured.copy(command = ""), measured.copy(report = ""), measured.copy(uncovered = ""),
            measured.copy(lineCovered = -1), measured.copy(lineMissed = -1), measured.copy(branchCovered = -1), measured.copy(branchMissed = -1),
            measured.copy(lineCovered = 98999, lineMissed = 1001), measured.copy(branchCovered = 96999, branchMissed = 3001), measured.copy(lineCovered = 0, lineMissed = 0),
            measured.copy(branchCovered = 0, branchMissed = 0), measured.copy(branchCovered = 0, branchMissed = 0, noBranchesReason = " "))) {
            assertTrue(bad.issues().isNotEmpty(), bad.toString())
        }
        assertNull(measured.copy(lineCovered = 0, lineMissed = 0).linePercent())
        val straight = measured.copy(branchCovered = 0, branchMissed = 0, noBranchesReason = "Report has no conditionals")
        assertNull(straight.branchPercent()); assertTrue(straight.issues().isEmpty())
        assertEquals(100.0, measured.copy(lineCovered = Long.MAX_VALUE, lineMissed = 0).linePercent())
        assertTrue(TestQualityEvidence("Reviewed results and failure assertions", listOf(measured)).issues().isEmpty())
        assertTrue(TestQualityEvidence(" ", emptyList()).issues().size == 2)
        assertTrue(TestQualityEvidence("Reviewed", listOf(measured.copy(lineMissed = 100))).issues().single().startsWith("Service: Line coverage"))
        assertTrue(measured.copy(lineCovered = 90, lineMissed = 10, branchCovered = 80, branchMissed = 20).issues(90.0, 80.0).isEmpty())
    }

    @Test fun `fingerprints ignore object key order but preserve title scalars and array order`() {
        val first = ReviewDocument("Title", Json.parseToJsonElement("""{"b":[1,{"b":2,"a":3}],"a":true}"""))
        val reordered = ReviewDocument("Title", Json.parseToJsonElement("""{"a":true,"b":[1,{"a":3,"b":2}]}"""))
        assertEquals(first.fingerprint(), reordered.fingerprint())
        assertEquals(64, first.fingerprint().length)
        assertNotEquals(first.fingerprint(), first.copy(title = "Other").fingerprint())
        assertNotEquals(first.fingerprint(), ReviewDocument("Title", Json.parseToJsonElement("""{"a":true,"b":[{"a":3,"b":2},1]}""")).fingerprint())
        assertNotEquals(first.fingerprint(), first.copy(content = JsonNull).fingerprint())
    }

    @Test fun `portable evidence round trips optional defaults but missing required evidence never becomes success`() {
        val document = ReviewDocument("Plan", JsonNull)
        val requirement = ReviewRequirement("r", "REQ-1", document, listOf("Works"))
        val context = ReviewContext("URI", "target", null, null)
        val snapshot = SpecReviewSnapshot(null, document, listOf(requirement), null, null, null, null, listOf(context))
        val quality = TestQualityEvidence("Reviewed", listOf(measured))
        val check = ValidationCheck("Works", ValidationOutcome.PASSED, "Observed", cycle)
        val record = ValidationRecord(requirementId = "r", taskId = "t", taskVersion = 1, specContentHash = "s", requirementContentHash = "r", checks = listOf(check))
        fun <T> verify(serializer: KSerializer<T>, value: T, required: List<String>) {
            val encoded = Json.encodeToJsonElement(serializer, value).jsonObject
            assertEquals(value, Json.decodeFromJsonElement(serializer, encoded))
            for (field in required) assertFailsWith<SerializationException>(field) { Json.decodeFromJsonElement(serializer, JsonObject(encoded - field)) }
            val explicit = Json { encodeDefaults = true }.encodeToJsonElement(serializer, value)
            assertEquals(value, Json.decodeFromJsonElement(serializer, explicit))
        }
        verify(ReviewedInterface.serializer(), ReviewedInterface("Contract.kt", "interface Contract"), listOf("path", "declaration"))
        verify(TddRun.serializer(), run, listOf("command", "outcome", "evidence"))
        verify(TddEvidence.serializer(), cycle, listOf("red", "green", "refactor"))
        verify(CoverageEvidence.serializer(), measured, listOf("scope", "command", "report", "lineCovered", "lineMissed", "branchCovered", "branchMissed", "uncovered"))
        verify(TestQualityEvidence.serializer(), quality, listOf("review", "coverage"))
        verify(ValidationCheck.serializer(), check, listOf("criterion", "outcome", "evidence"))
        verify(ValidationCheck.serializer(), ValidationCheck("Works", ValidationOutcome.NOT_RUN, "Infrastructure unavailable"), listOf("criterion", "outcome", "evidence"))
        verify(CoverageEvidence.serializer(), measured.copy(noBranchesReason = "No branches"), listOf("scope"))
        verify(SpecReviewSnapshot.serializer(), snapshot.copy(guidanceRevision = "g"), listOf("document"))
        verify(ValidationRecord.serializer(), record, listOf("requirementId", "taskId", "taskVersion", "specContentHash", "requirementContentHash", "checks"))
        verify(ReviewDocument.serializer(), document, listOf("title", "content"))
        verify(ReviewRequirement.serializer(), requirement, listOf("id", "key", "document", "acceptanceCriteria"))
        verify(ReviewContext.serializer(), context, listOf("type", "targetId", "label", "attributes"))
        verify(SpecReviewSnapshot.serializer(), snapshot, listOf("projectId", "document", "requirements", "programId", "parentSpecId", "gitRepositoryId", "gitPath", "contexts"))
        verify(SpecReviewRecord.serializer(), SpecReviewRecord(specId = "s", note = null, snapshot = snapshot), listOf("specId", "note", "snapshot"))
        assertEquals(record.copy(interfaceReviewId = 2, testQuality = quality, guidanceRevision = "g"), Json.decodeFromString(ValidationRecord.serializer(), Json.encodeToString(ValidationRecord.serializer(), record.copy(interfaceReviewId = 2, testQuality = quality, guidanceRevision = "g"))))
    }
}
