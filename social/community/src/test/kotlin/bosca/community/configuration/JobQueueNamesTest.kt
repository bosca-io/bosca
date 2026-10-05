package bosca.community.configuration

import kotlin.test.Test
import kotlin.test.assertEquals

class JobQueueNamesTest {

    @Test
    fun `communityJobQueue has expected value`() {
        assertEquals("communityJobQueue", JobQueueNames.communityJobQueue)
    }

    @Test
    fun `communityQueue has expected value`() {
        assertEquals("community", JobQueueNames.communityQueue)
    }

    @Test
    fun `communityRunner has expected value`() {
        assertEquals("communityRunner", JobQueueNames.communityRunner)
    }

    @Test
    fun `all queue name constants are distinct`() {
        val names = listOf(
            JobQueueNames.communityJobQueue,
            JobQueueNames.communityQueue,
            JobQueueNames.communityRunner
        )
        assertEquals(names.size, names.toSet().size)
    }
}
