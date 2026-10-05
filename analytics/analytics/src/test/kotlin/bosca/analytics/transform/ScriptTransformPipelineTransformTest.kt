@file:OptIn(ExperimentalUuidApi::class, InternalDI::class)

package bosca.analytics.transform

import bosca.analytics.events.AnalyticsScriptEvent
import bosca.analytics.model.Event
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineService
import bosca.server.Headers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ScriptTransformPipelineTransformTest {

    private val service = mockk<PipelineService>()
    private val context = EventPipelineContext(Headers.Empty)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<PipelineService> { service }
        provides<Json> { Json { ignoreUnknownKeys = true } }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun events(vararg types: EventType): Events {
        val eventList = types.map { Event(created = 1000L, type = it) }
        return Events(events = eventList, sent = 1000L, sentMicros = 1000000L)
    }

    private fun pipeline(name: String = "Enrich") = Pipeline(
        id = Uuid.random(),
        name = name,
        acceptedInputType = "analytics.transform.events",
        triggered = true,
    )

    @Test
    fun `returns events unchanged when the pipelines module is not present`() = runTest {
        ProviderRegistry.clear()
        val transform = ScriptTransformPipelineTransform("analytics.transform.events")
        val input = events(EventType.Session)
        val result = transform.transform(context, input)
        assertSame(input, result)
    }

    @Test
    fun `returns events unchanged when no triggered pipelines match`() = runTest {
        coEvery { service.triggeredFor("analytics.transform.events") } returns emptyList()
        val transform = ScriptTransformPipelineTransform("analytics.transform.events")
        val input = events(EventType.Session)
        assertSame(input, transform.transform(context, input))
    }

    @Test
    fun `runs the matching pipeline with the batch and configured event name`() = runTest {
        val p = pipeline()
        coEvery { service.triggeredFor("analytics.transform.session") } returns listOf(p)
        coEvery { service.run(p, any()) } coAnswers { secondArg<PipelineValue>() }

        val transform = ScriptTransformPipelineTransform("analytics.transform.session")
        transform.transform(context, events(EventType.Session))

        coVerify(exactly = 1) { service.triggeredFor("analytics.transform.session") }
        coVerify(exactly = 1) {
            service.run(p, match { (it.value as? AnalyticsScriptEvent)?.events?.events?.size == 1 })
        }
    }

    @Test
    fun `adopts the batch returned by a transform pipeline`() = runTest {
        val p = pipeline()
        val modified = events(EventType.Session, EventType.Interaction)
        coEvery { service.triggeredFor("analytics.transform.events") } returns listOf(p)
        coEvery { service.run(p, any()) } returns
            PipelineValue.of(AnalyticsScriptEvent(modified), AnalyticsScriptEvent.serializer())

        val transform = ScriptTransformPipelineTransform("analytics.transform.events")
        val result = transform.transform(context, events(EventType.Session))
        assertEquals(2, result.events.size)
        assertEquals(EventType.Session, result.events[0].type)
        assertEquals(EventType.Interaction, result.events[1].type)
    }

    @Test
    fun `adopts a JSON output convertible back via its origin serializer`() = runTest {
        val p = pipeline()
        val modified = events(EventType.Session, EventType.Session, EventType.Session)
        val json = Json { ignoreUnknownKeys = true }
        val jsonOutput = PipelineValue
            .of(AnalyticsScriptEvent(modified), AnalyticsScriptEvent.serializer())
            .toJson(json)
        coEvery { service.triggeredFor("analytics.transform.events") } returns listOf(p)
        coEvery { service.run(p, any()) } returns jsonOutput

        val transform = ScriptTransformPipelineTransform("analytics.transform.events")
        val result = transform.transform(context, events(EventType.Session))
        assertEquals(3, result.events.size)
    }

    @Test
    fun `keeps the batch when a pipeline returns an unrelated value`() = runTest {
        val p = pipeline()
        coEvery { service.triggeredFor("analytics.transform.events") } returns listOf(p)
        coEvery { service.run(p, any()) } returns PipelineValue.ofJson(JsonPrimitive("nope"))

        val transform = ScriptTransformPipelineTransform("analytics.transform.events")
        val input = events(EventType.Session)
        val result = transform.transform(context, input)
        assertEquals(1, result.events.size)
        assertEquals(EventType.Session, result.events[0].type)
    }

    @Test
    fun `keeps the batch when a pipeline returns no output`() = runTest {
        val p = pipeline()
        coEvery { service.triggeredFor("analytics.transform.events") } returns listOf(p)
        coEvery { service.run(p, any()) } returns null

        val transform = ScriptTransformPipelineTransform("analytics.transform.events")
        val input = events(EventType.Session)
        val result = transform.transform(context, input)
        assertEquals(1, result.events.size)
    }

    @Test
    fun `chains multiple pipelines passing each the previous output`() = runTest {
        val first = pipeline("First")
        val second = pipeline("Second")
        coEvery { service.triggeredFor("analytics.transform.events") } returns listOf(first, second)
        coEvery { service.run(first, any()) } returns PipelineValue.of(
            AnalyticsScriptEvent(events(EventType.Session, EventType.Interaction)),
            AnalyticsScriptEvent.serializer(),
        )
        coEvery { service.run(second, any()) } coAnswers {
            val current = secondArg<PipelineValue>().value as AnalyticsScriptEvent
            assertEquals(2, current.events.events.size)
            PipelineValue.of(
                AnalyticsScriptEvent(events(EventType.Impression)),
                AnalyticsScriptEvent.serializer(),
            )
        }

        val transform = ScriptTransformPipelineTransform("analytics.transform.events")
        val result = transform.transform(context, events(EventType.Session))
        assertEquals(1, result.events.size)
        assertEquals(EventType.Impression, result.events[0].type)
    }

    @Test
    fun `propagates the exception when a pipeline run throws`() = runTest {
        val p = pipeline()
        coEvery { service.triggeredFor("analytics.transform.events") } returns listOf(p)
        coEvery { service.run(p, any()) } throws RuntimeException("pipeline failed")

        val transform = ScriptTransformPipelineTransform("analytics.transform.events")
        val input = events(EventType.Session, EventType.Interaction)
        assertFailsWith<RuntimeException> { transform.transform(context, input) }
    }
}
