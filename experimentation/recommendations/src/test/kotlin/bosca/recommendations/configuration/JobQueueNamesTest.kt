package bosca.recommendations.configuration

import kotlin.test.Test
import kotlin.test.assertTrue

class JobQueueNamesTest {

    @Test
    fun `recommendationsJobQueue is not empty`() {
        assertTrue(JobQueueNames.recommendationsJobQueue.isNotEmpty())
    }

    @Test
    fun `recommendationsRunner is not empty`() {
        assertTrue(JobQueueNames.recommendationsRunner.isNotEmpty())
    }

    @Test
    fun `recommendationsQueue is not empty`() {
        assertTrue(JobQueueNames.recommendationsQueue.isNotEmpty())
    }
}
