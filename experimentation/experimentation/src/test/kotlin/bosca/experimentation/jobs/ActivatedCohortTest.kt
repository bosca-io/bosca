package bosca.experimentation.jobs

import bosca.serialization.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActivatedCohortTest {
    @Test
    fun `large cohorts retain every subject and timestamp in bounded SQL literals`() {
        val subjects = List(9_000) { index ->
            ActivatedSubject(UUID.random().toString(), if (index % 2 == 0) "control" else "treat'\"\\é",
                "2026-08-28 10:00:10.123456")
        }
        val cohort = ActivatedCohort(subjects)
        assertTrue(cohort.batches.size > 1)
        val decoded = cohort.batches.flatMap { batch ->
            val literal = batch.encodedSubjects.replace("'", "''")
            assertTrue(literal.length <= MAX_ACTIVATED_COHORT_LITERAL_LENGTH)
            Json.decodeFromString(ListSerializer(ActivatedSubject.serializer()), batch.encodedSubjects)
        }
        assertEquals(subjects, decoded)
        assertEquals(subjects.map { it.subjectId }.toSet(), cohort.subjectIds)
        assertEquals(mapOf("control" to 4_500L, "treat'\"\\é" to 4_500L), cohort.countsByVariation)
    }

    @Test
    fun `empty cohort retains an empty gated batch`() {
        val cohort = ActivatedCohort(emptyList())
        assertEquals("[]", cohort.batches.single().encodedSubjects)
        assertTrue(cohort.subjectIds.isEmpty())
        assertTrue(cohort.countsByVariation.isEmpty())
    }

    @Test
    fun `one subject can fill a batch exactly but cannot exceed its literal limit`() {
        val base = ActivatedSubject(UUID.random().toString(), "", "2026-08-28 10:00:10.123456")
        val overhead = Json.encodeToString(ActivatedSubject.serializer(), base).length + 2
        val subject = base.copy(variationKey = "x".repeat(MAX_ACTIVATED_COHORT_LITERAL_LENGTH - overhead))
        val cohort = ActivatedCohort(listOf(subject, base))
        assertEquals(2, cohort.batches.size)
        assertEquals(MAX_ACTIVATED_COHORT_LITERAL_LENGTH, cohort.batches.first().encodedSubjects.length)
        assertFailsWith<IllegalArgumentException> {
            ActivatedCohort(listOf(subject.copy(variationKey = subject.variationKey + "'")))
        }
    }
}
