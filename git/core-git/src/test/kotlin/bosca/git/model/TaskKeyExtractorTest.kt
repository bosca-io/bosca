package bosca.git.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskKeyExtractorTest {

    @Test
    fun `extracts simple task key`() {
        assertEquals(setOf("PROJ-123"), TaskKeyExtractor.extract("Fix PROJ-123 typo"))
    }

    @Test
    fun `extracts multiple task keys`() {
        assertEquals(
            setOf("PROJ-1", "BUG-42"),
            TaskKeyExtractor.extract("Fixes PROJ-1 and BUG-42")
        )
    }

    @Test
    fun `extracts keys with underscores`() {
        assertEquals(setOf("BUG_FIX-7"), TaskKeyExtractor.extract("Resolves BUG_FIX-7"))
    }

    @Test
    fun `returns empty for no matches`() {
        assertTrue(TaskKeyExtractor.extract("just a regular commit").isEmpty())
    }

    @Test
    fun `deduplicates across multiple sources`() {
        val keys = TaskKeyExtractor.extractFromAll(
            "PROJ-1 initial work",
            "feature/PROJ-1-add-login",
            "Also references PROJ-1 and PROJ-2"
        )
        assertEquals(setOf("PROJ-1", "PROJ-2"), keys)
    }

    @Test
    fun `handles null sources`() {
        val keys = TaskKeyExtractor.extractFromAll("PROJ-1", null, "PROJ-2")
        assertEquals(setOf("PROJ-1", "PROJ-2"), keys)
    }

    @Test
    fun `extracts from branch names`() {
        assertEquals(setOf("FEAT-99"), TaskKeyExtractor.extract("refs/heads/feature/FEAT-99-add-button"))
    }
}
