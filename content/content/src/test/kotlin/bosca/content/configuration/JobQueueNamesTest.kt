package bosca.content.configuration

import kotlin.test.Test
import kotlin.test.assertEquals

class JobQueueNamesTest {

    @Test
    fun `contentJobQueue has expected value`() {
        assertEquals("contentQueue", JobQueueNames.contentJobQueue)
    }

    @Test
    fun `contentRunner has expected value`() {
        assertEquals("contentQueueRunner", JobQueueNames.contentRunner)
    }

    @Test
    fun `contentQueue has expected value`() {
        assertEquals("content", JobQueueNames.contentQueue)
    }

    @Test
    fun `all constants are distinct`() {
        val values = setOf(JobQueueNames.contentJobQueue, JobQueueNames.contentRunner, JobQueueNames.contentQueue)
        assertEquals(3, values.size)
    }
}
