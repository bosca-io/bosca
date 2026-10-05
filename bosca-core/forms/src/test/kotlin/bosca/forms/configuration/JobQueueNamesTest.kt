package bosca.forms.configuration

import kotlin.test.Test
import kotlin.test.assertEquals

class JobQueueNamesTest {

    @Test
    fun `formsJobQueue constant has expected value`() {
        assertEquals("formsJobQueue", JobQueueNames.formsJobQueue)
    }

    @Test
    fun `formsRunner constant has expected value`() {
        assertEquals("formsJobQueueRunner", JobQueueNames.formsRunner)
    }

    @Test
    fun `formsQueueName constant has expected value`() {
        assertEquals("forms", JobQueueNames.formsQueueName)
    }

    @Test
    fun `all constants are distinct`() {
        val values = listOf(
            JobQueueNames.formsJobQueue,
            JobQueueNames.formsRunner,
            JobQueueNames.formsQueueName
        )
        assertEquals(values.size, values.toSet().size)
    }
}
