package bosca.scheduler.annotations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SchedulableAnnotationTest {

    @Schedulable(name = "daily-cleanup", description = "Removes expired items daily")
    class DailyCleanupSchedule

    @Schedulable(name = "simple-task")
    class SimpleTaskSchedule

    @Test
    fun `Schedulable annotation stores name`() {
        val annotation = DailyCleanupSchedule::class.java.getAnnotation(Schedulable::class.java)
        assertNotNull(annotation)
        assertEquals("daily-cleanup", annotation.name)
    }

    @Test
    fun `Schedulable annotation stores description`() {
        val annotation = DailyCleanupSchedule::class.java.getAnnotation(Schedulable::class.java)
        assertNotNull(annotation)
        assertEquals("Removes expired items daily", annotation.description)
    }

    @Test
    fun `Schedulable annotation default description is empty string`() {
        val annotation = SimpleTaskSchedule::class.java.getAnnotation(Schedulable::class.java)
        assertNotNull(annotation)
        assertEquals("", annotation.description)
    }
}
