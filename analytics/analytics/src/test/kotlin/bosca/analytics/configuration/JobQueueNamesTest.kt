package bosca.analytics.configuration

import kotlin.test.Test
import kotlin.test.assertEquals

class JobQueueNamesTest {

    @Test
    fun `analyticsJobQueue has expected value`() {
        assertEquals("analytics", JobQueueNames.analyticsJobQueue)
    }

    @Test
    fun `analyticsRunner has expected value`() {
        assertEquals("analyticsQueueRunner", JobQueueNames.analyticsRunner)
    }

    @Test
    fun `analyticsQueue has expected value`() {
        assertEquals("analytics", JobQueueNames.analyticsQueue)
    }

    @Test
    fun `analyticsJobQueue and analyticsQueue are equal`() {
        assertEquals(JobQueueNames.analyticsJobQueue, JobQueueNames.analyticsQueue)
    }
}
