package bosca.scripting.configuration

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigurationTest {

    @Test
    fun `JobQueueNames constants have expected values`() {
        assertEquals("scriptingQueue", JobQueueNames.scriptingJobQueue)
        assertEquals("scriptingQueueRunner", JobQueueNames.scriptingRunner)
        assertEquals("scripting", JobQueueNames.scriptingQueue)
    }
}
