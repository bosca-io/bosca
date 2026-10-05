@file:OptIn(ExperimentalUuidApi::class, InternalDI::class)

package bosca.analytics.transform

import bosca.analytics.events.AnalyticsScriptEvent
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.Event
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineEventDispatcher
import bosca.server.Headers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi

class ScriptTriggerPipelineTransformTest {

    private val hook = mockk<PipelineEventDispatcher>(relaxed = true)
    private val context = EventPipelineContext(Headers.Empty)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<PipelineEventDispatcher> { hook }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun events(vararg types: EventType): Events {
        val eventList = types.map { Event(created = 1000L, type = it) }
        return Events(events = eventList, sent = 1000L, sentMicros = 1000000L)
    }

    @Test
    fun `returns events unchanged when the pipelines module is not present`() = runTest {
        ProviderRegistry.clear()
        val transform = ScriptTriggerPipelineTransform("analytics.notify.events")
        val input = events(EventType.Session)
        val result = transform.transform(context, input)
        assertSame(input, result)
    }

    @Test
    fun `always returns original events unmodified`() = runTest {
        val transform = ScriptTriggerPipelineTransform("analytics.notify.events")
        val input = events(EventType.Session, EventType.Interaction)
        val result = transform.transform(context, input)
        assertSame(input, result)
    }

    @Test
    fun `delegates to hook with configured event name`() = runTest {
        val transform = ScriptTriggerPipelineTransform("analytics.notify.session")
        val input = events(EventType.Session)
        transform.transform(context, input)

        coVerify(exactly = 1) {
            hook.dispatch(eq("analytics.notify.session"), any<AnalyticsScriptEvent>(), any<KSerializer<AnalyticsScriptEvent>>())
        }
    }

    @Test
    fun `passes all events to hook regardless of event types`() = runTest {
        val transform = ScriptTriggerPipelineTransform("analytics.notify.events")
        val input = events(EventType.Session, EventType.Interaction, EventType.Impression)
        transform.transform(context, input)

        coVerify {
            hook.dispatch(eq("analytics.notify.events"), match<AnalyticsScriptEvent> {
                it.events.events.size == 3
            }, any<KSerializer<AnalyticsScriptEvent>>())
        }
    }

    @Test
    fun `propagates the exception when dispatch throws`() = runTest {
        val transform = ScriptTriggerPipelineTransform("analytics.notify.events")

        coEvery {
            hook.dispatch(any(), any<AnalyticsScriptEvent>(), any<KSerializer<AnalyticsScriptEvent>>())
        } throws RuntimeException("dispatch failed")

        val input = events(EventType.Session)
        assertFailsWith<RuntimeException> { transform.transform(context, input) }
    }

    @Test
    fun `each instance uses its own event name`() = runTest {
        val eventsTransform = ScriptTriggerPipelineTransform("analytics.notify.events")
        val sessionTransform = ScriptTriggerPipelineTransform("analytics.notify.session")
        val input = events(EventType.Session)

        eventsTransform.transform(context, input)
        sessionTransform.transform(context, input)

        coVerify(exactly = 1) {
            hook.dispatch(eq("analytics.notify.events"), any(), any<KSerializer<AnalyticsScriptEvent>>())
        }
        coVerify(exactly = 1) {
            hook.dispatch(eq("analytics.notify.session"), any(), any<KSerializer<AnalyticsScriptEvent>>())
        }
    }

    @Test
    fun `handles empty events list`() = runTest {
        val transform = ScriptTriggerPipelineTransform("analytics.notify.events")
        val input = Events(events = emptyList(), sent = 1000L, sentMicros = 1000000L)
        transform.transform(context, input)

        coVerify(exactly = 1) {
            hook.dispatch(eq("analytics.notify.events"), any(), any<KSerializer<AnalyticsScriptEvent>>())
        }
    }

    @Test
    fun `wraps events in AnalyticsScriptEvent for hook`() = runTest {
        val transform = ScriptTriggerPipelineTransform("analytics.notify.events")
        val input = events(EventType.Session, EventType.Interaction)
        transform.transform(context, input)

        coVerify {
            hook.dispatch(any(), match<AnalyticsScriptEvent> {
                it.events == input
            }, any<KSerializer<AnalyticsScriptEvent>>())
        }
    }

    @Test
    fun `uses AnalyticsScriptEvent serializer`() = runTest {
        val transform = ScriptTriggerPipelineTransform("analytics.notify.events")
        val input = events(EventType.Session)
        transform.transform(context, input)

        coVerify {
            hook.dispatch(any(), any(), eq(AnalyticsScriptEvent.serializer()))
        }
    }

    @Test
    fun `multiple events of same type are all passed through`() = runTest {
        val transform = ScriptTriggerPipelineTransform("analytics.notify.session")
        val input = events(EventType.Session, EventType.Session, EventType.Session)
        val result = transform.transform(context, input)

        assertSame(input, result)
        coVerify {
            hook.dispatch(eq("analytics.notify.session"), match<AnalyticsScriptEvent> {
                it.events.events.size == 3
            }, any<KSerializer<AnalyticsScriptEvent>>())
        }
    }
}
