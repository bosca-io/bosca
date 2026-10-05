package bosca.analytics.configuration

import bosca.analytics.transform.EventPipelineTransform
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.Events
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventPipelineTransformsTest {

    private val noOpTransform = object : EventPipelineTransform {
        override suspend fun transform(context: EventPipelineContext, events: Events): Events = events
    }

    @Test
    fun `EventPipelineTransforms stores transform list`() {
        val transforms = listOf(noOpTransform, noOpTransform)
        val pipeline = EventPipelineTransforms(transforms)
        assertEquals(2, pipeline.transforms.size)
    }

    @Test
    fun `EventPipelineTransforms with empty list`() {
        val pipeline = EventPipelineTransforms(emptyList())
        assertTrue(pipeline.transforms.isEmpty())
    }

    @Test
    fun `EventPipelineTransforms preserves order`() {
        val first = object : EventPipelineTransform {
            override suspend fun transform(context: EventPipelineContext, events: Events): Events = events
            override fun toString() = "first"
        }
        val second = object : EventPipelineTransform {
            override suspend fun transform(context: EventPipelineContext, events: Events): Events = events
            override fun toString() = "second"
        }
        val pipeline = EventPipelineTransforms(listOf(first, second))
        assertEquals("first", pipeline.transforms[0].toString())
        assertEquals("second", pipeline.transforms[1].toString())
    }
}
