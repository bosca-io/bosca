@file:OptIn(ExperimentalUuidApi::class)

package bosca.analytics.transform

import bosca.analytics.model.AnalyticsScriptBinding
import bosca.analytics.model.Event
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.service.AnalyticsScriptBindingService
import bosca.di.ObjectProvider
import bosca.scripting.context.BoscaScriptContext
import bosca.scripting.context.ScriptContext
import bosca.scripting.model.Script
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.server.Headers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AnalyticsScriptTransformTest {

    private val bindingService = mockk<AnalyticsScriptBindingService>()
    private val scriptService = mockk<ScriptService>()
    private val executionService = mockk<ScriptExecutionService>()
    private val securityService = mockk<SecurityService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val context = EventPipelineContext(Headers.Empty)

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.security.service.SecurityServiceKt")
        coEvery { securityService.impersonate(any<String>()) } returns mockk<ImpersonatedAuthenticationContext>(relaxed = true)
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.security.service.SecurityServiceKt")
    }

    private inline fun <reified T : Any> providerOf(value: T?): ObjectProvider<T> {
        val provider = mockk<ObjectProvider<T>>()
        every { provider.exists } returns (value != null)
        if (value != null) coEvery { provider.get() } returns value
        return provider
    }

    private fun transform(
        binding: AnalyticsScriptBindingService? = bindingService,
        script: ScriptService? = scriptService,
        execution: ScriptExecutionService? = executionService,
        security: SecurityService? = securityService,
        account: String = "sa",
    ) = AnalyticsScriptTransform(
        providerOf(binding), providerOf(script), providerOf(execution), providerOf(security), json, account,
    )

    private fun events(vararg types: EventType): Events {
        val eventList = types.map { Event(created = 1000L, type = it) }
        return Events(events = eventList, sent = 1000L, sentMicros = 1_000_000L)
    }

    private fun binding(scriptId: Uuid = Uuid.random(), transform: Boolean = true, ordinal: Int = 0) =
        AnalyticsScriptBinding(
            id = Uuid.random(),
            scriptId = scriptId,
            transform = transform,
            enabled = true,
            ordinal = ordinal,
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now(),
        )

    private fun script(id: Uuid, enabled: Boolean = true) =
        Script(id = id, key = "k", name = "n", source = "src", enabled = enabled)

    private fun eventsJson(events: Events) = json.encodeToJsonElement(Events.serializer(), events)

    @Test
    fun `returns events unchanged when the scripting module is not present`() = runTest {
        val input = events(EventType.Session)
        assertSame(input, transform(script = null).transform(context, input))
        assertSame(input, transform(binding = null).transform(context, input))
        assertSame(input, transform(execution = null).transform(context, input))
        assertSame(input, transform(security = null).transform(context, input))
    }

    @Test
    fun `returns events unchanged when there are no enabled bindings`() = runTest {
        coEvery { bindingService.enabledBindings() } returns emptyList()
        val input = events(EventType.Session)
        assertSame(input, transform().transform(context, input))
    }

    @Test
    fun `runs a binding and adopts the modified batch`() = runTest {
        val scriptId = Uuid.random()
        val modified = events(EventType.Session, EventType.Interaction)
        coEvery { bindingService.enabledBindings() } returns listOf(binding(scriptId))
        coEvery { scriptService.get(scriptId) } returns script(scriptId)
        coEvery { executionService.executeAsJson(any(), any()) } returns eventsJson(modified)

        val result = transform().transform(context, events(EventType.Session))

        assertEquals(2, result.events.size)
        assertEquals(EventType.Session, result.events[0].type)
        assertEquals(EventType.Interaction, result.events[1].type)
    }

    @Test
    fun `passes the whole batch to the script as its input`() = runTest {
        val scriptId = Uuid.random()
        coEvery { bindingService.enabledBindings() } returns listOf(binding(scriptId))
        coEvery { scriptService.get(scriptId) } returns script(scriptId)
        val contextSlot = slot<ScriptContext>()
        coEvery { executionService.executeAsJson(any(), capture(contextSlot)) } returns JsonNull

        transform().transform(context, events(EventType.Impression))

        val decoded = json.decodeFromJsonElement(
            Events.serializer(),
            (contextSlot.captured as BoscaScriptContext).input,
        )
        assertEquals(EventType.Impression, decoded.events.single().type)
    }

    @Test
    fun `a non-transform binding runs the script but never applies its output`() = runTest {
        val scriptId = Uuid.random()
        coEvery { bindingService.enabledBindings() } returns listOf(binding(scriptId, transform = false))
        coEvery { scriptService.get(scriptId) } returns script(scriptId)
        // The script returns a batch, but transform = false means it must be ignored.
        coEvery { executionService.executeAsJson(any(), any()) } returns
            eventsJson(events(EventType.Interaction, EventType.Error))

        val result = transform().transform(context, events(EventType.Session))

        assertEquals(1, result.events.size)
        assertEquals(EventType.Session, result.events[0].type)
        coVerify(exactly = 1) { executionService.executeAsJson(any(), any()) }
    }

    @Test
    fun `keeps the batch when a script returns JsonNull`() = runTest {
        val scriptId = Uuid.random()
        coEvery { bindingService.enabledBindings() } returns listOf(binding(scriptId))
        coEvery { scriptService.get(scriptId) } returns script(scriptId)
        coEvery { executionService.executeAsJson(any(), any()) } returns JsonNull

        val input = events(EventType.Session)
        val result = transform().transform(context, input)
        assertEquals(1, result.events.size)
        assertEquals(EventType.Session, result.events[0].type)
    }

    @Test
    fun `propagates when a transform script returns a non-Events value`() = runTest {
        val scriptId = Uuid.random()
        coEvery { bindingService.enabledBindings() } returns listOf(binding(scriptId))
        coEvery { scriptService.get(scriptId) } returns script(scriptId)
        coEvery { executionService.executeAsJson(any(), any()) } returns JsonPrimitive("nope")

        // A transform binding whose script returns a non-Events value must fail (NAK + redelivery),
        // not silently store the un-transformed batch.
        assertFails { transform().transform(context, events(EventType.Session)) }
    }

    @Test
    fun `skips a binding whose script is missing`() = runTest {
        val scriptId = Uuid.random()
        coEvery { bindingService.enabledBindings() } returns listOf(binding(scriptId))
        coEvery { scriptService.get(scriptId) } returns null

        val result = transform().transform(context, events(EventType.Session))
        assertEquals(1, result.events.size)
        coVerify(exactly = 0) { executionService.executeAsJson(any(), any()) }
    }

    @Test
    fun `skips a binding whose script is disabled`() = runTest {
        val scriptId = Uuid.random()
        coEvery { bindingService.enabledBindings() } returns listOf(binding(scriptId))
        coEvery { scriptService.get(scriptId) } returns script(scriptId, enabled = false)

        transform().transform(context, events(EventType.Session))
        coVerify(exactly = 0) { executionService.executeAsJson(any(), any()) }
    }

    @Test
    fun `chains multiple bindings threading each output into the next`() = runTest {
        val firstId = Uuid.random()
        val secondId = Uuid.random()
        coEvery { bindingService.enabledBindings() } returns listOf(
            binding(firstId, ordinal = 0),
            binding(secondId, ordinal = 1),
        )
        coEvery { scriptService.get(firstId) } returns script(firstId)
        coEvery { scriptService.get(secondId) } returns script(secondId)
        coEvery { executionService.executeAsJson(match { it.id == firstId }, any()) } returns
            eventsJson(events(EventType.Session, EventType.Interaction))
        coEvery { executionService.executeAsJson(match { it.id == secondId }, any()) } returns
            eventsJson(events(EventType.Impression))

        val result = transform().transform(context, events(EventType.Session))
        assertEquals(1, result.events.size)
        assertEquals(EventType.Impression, result.events[0].type)
    }

    @Test
    fun `impersonates the configured service account`() = runTest {
        val scriptId = Uuid.random()
        coEvery { bindingService.enabledBindings() } returns listOf(binding(scriptId))
        coEvery { scriptService.get(scriptId) } returns script(scriptId)
        coEvery { executionService.executeAsJson(any(), any()) } returns JsonNull

        val transform = transform(account = "svc")
        transform.transform(context, events(EventType.Session))
        transform.transform(context, events(EventType.Session))

        coVerify(exactly = 1) { securityService.impersonate("svc") }
    }

    @Test
    fun `propagates when script execution throws`() = runTest {
        val scriptId = Uuid.random()
        coEvery { bindingService.enabledBindings() } returns listOf(binding(scriptId))
        coEvery { scriptService.get(scriptId) } returns script(scriptId)
        coEvery { executionService.executeAsJson(any(), any()) } throws RuntimeException("boom")

        assertFailsWith<RuntimeException> { transform().transform(context, events(EventType.Session)) }
    }
}
