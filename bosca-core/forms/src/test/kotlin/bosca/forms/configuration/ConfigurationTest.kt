package bosca.forms.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigurationTest {

    @Test
    fun `JobQueueNames formsJobQueue has expected value`() {
        assertEquals("formsJobQueue", JobQueueNames.formsJobQueue)
    }

    @Test
    fun `JobQueueNames formsRunner has expected value`() {
        assertEquals("formsJobQueueRunner", JobQueueNames.formsRunner)
    }

    @Test
    fun `JobQueueNames formsQueueName has expected value`() {
        assertEquals("forms", JobQueueNames.formsQueueName)
    }

    @Test
    fun `JobQueueNames constants are not blank`() {
        assertTrue(JobQueueNames.formsJobQueue.isNotBlank())
        assertTrue(JobQueueNames.formsRunner.isNotBlank())
        assertTrue(JobQueueNames.formsQueueName.isNotBlank())
    }
}
