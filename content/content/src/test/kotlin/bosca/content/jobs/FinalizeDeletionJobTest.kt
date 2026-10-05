package bosca.content.jobs

import bosca.queue.annotations.IJobDefinition
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class FinalizeDeletionJobTest {

    @Test
    fun `can be instantiated`() {
        val job = FinalizeDeletionJob()
        assertIs<IJobDefinition>(job)
    }

    @Test
    fun `implements IJobDefinition`() {
        val job: IJobDefinition = FinalizeDeletionJob()
        assertIs<FinalizeDeletionJob>(job)
    }

    @Test
    fun `two instances are distinct references`() {
        val a = FinalizeDeletionJob()
        val b = FinalizeDeletionJob()
        // FinalizeDeletionJob is a regular class with no fields, so instances are distinct
        assertNotEquals(System.identityHashCode(a), System.identityHashCode(b))
    }
}
