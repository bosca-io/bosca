package bosca.profile.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobQueueNamesTest {

    @Test
    fun `profileJobQueue has expected value`() {
        assertEquals("profileQueue", JobQueueNames.profileJobQueue)
    }

    @Test
    fun `profileRunner has expected value`() {
        assertEquals("profileQueueRunner", JobQueueNames.profileRunner)
    }

    @Test
    fun `profileQueue has expected value`() {
        assertEquals("profile", JobQueueNames.profileQueue)
    }

    @Test
    fun `all queue name constants are distinct`() {
        val names = listOf(
            JobQueueNames.profileJobQueue,
            JobQueueNames.profileRunner,
            JobQueueNames.profileQueue
        )
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `none of the queue name constants are blank`() {
        assertTrue(JobQueueNames.profileJobQueue.isNotBlank())
        assertTrue(JobQueueNames.profileRunner.isNotBlank())
        assertTrue(JobQueueNames.profileQueue.isNotBlank())
    }
}
