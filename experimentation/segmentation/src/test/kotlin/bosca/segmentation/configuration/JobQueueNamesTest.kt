package bosca.segmentation.configuration

import kotlin.test.Test
import kotlin.test.assertEquals

class JobQueueNamesTest {

    @Test
    fun segmentationJobQueueValue() {
        assertEquals("segmentationQueue", JobQueueNames.segmentationJobQueue)
    }

    @Test
    fun segmentationRunnerValue() {
        assertEquals("segmentationQueueRunner", JobQueueNames.segmentationRunner)
    }

    @Test
    fun segmentationQueueValue() {
        assertEquals("segmentation", JobQueueNames.segmentationQueue)
    }

    @Test
    fun allConstantsAreDistinct() {
        val values = setOf(
            JobQueueNames.segmentationJobQueue,
            JobQueueNames.segmentationRunner,
            JobQueueNames.segmentationQueue
        )
        assertEquals(3, values.size)
    }
}
