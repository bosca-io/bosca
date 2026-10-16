@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.transform.fums

import bosca.analytics.jobs.FumsDeliveryJob
import bosca.analytics.model.*
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import bosca.server.Headers
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class FumsAnalyticsTransformTest {
    private val queue = mockk<JobQueue>()
    private val json = Json
    private val transform = FumsAnalyticsTransform(queue)
    private val pipeline = EventPipelineContext(Headers.Empty)
    private val bibleId = UUID.random()
    private val audioId = UUID.random()
    private val jobs = mutableListOf<Job>()
    private lateinit var logs: FumsLogCapture
    private val context = EventContext("app", "1", device = Device("device", "", "", "", "", "", "", "", ""),
        sessionId = "session", userId = "reader@example.com")

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
        coEvery { queue.enqueue(any()) } coAnswers { firstArg<Job>().also(jobs::add).getId() }
        logs = FumsLogCapture(FumsAnalyticsTransform::class.java)
    }

    @AfterTest
    fun teardown() {
        logs.close()
        ProviderRegistry.clear()
    }

    private fun event(type: EventType = EventType.Impression, elementType: String = "bible-text", audio: Boolean = false,
                      reference: String = "JHN.1") = Event(100, type = type, element = Element(type = elementType,
        extras = buildJsonObject {
            put("bible_id", bibleId.toString())
            put("bible_version", 2)
            put("bible_variant", "reader")
            put("bible_usfm", reference)
            if (audio) put("bible_audio_id", audioId.toString())
        }))

    private fun batch(vararg events: Event) = Events(context, events.toList(), 100, 20)
    private fun payload(job: Job) = json.decodeFromJsonElement(FumsDeliveryJob.serializer(), job.getDefinition())

    @Test
    fun `queues text impressions and audio playback using reference-only jobs`() = runTest {
        val input = batch(event(), event(EventType.Interaction, "bible-audio", true))
        assertSame(input, transform.transform(pipeline, input))
        assertEquals(2, jobs.size)
        val text = payload(jobs[0])
        val audio = payload(jobs[1])
        assertEquals(bibleId, text.bibleId)
        assertEquals(2, text.version)
        assertEquals("reader", text.variant)
        assertEquals("JHN.1", text.usfm)
        assertNull(text.audioId)
        assertEquals(audioId, audio.audioId)
        assertEquals("session", text.sessionId)
        assertEquals(64, text.deviceId.length)
        assertEquals(64, text.userId?.length)
        assertNotEquals(context.device.installationId, text.deviceId)
        assertNotEquals(context.userId, text.userId)
        assertEquals(text.deviceId, audio.deviceId)
        assertFalse(jobs[0].getDefinition().toString().contains("fumsToken"))
        assertEquals(listOf(
            "FUMS usage submitted: jobId=${jobs[0].getId()} bibleId=$bibleId chapter=JHN.1 kind=text",
            "FUMS usage submitted: jobId=${jobs[1].getId()} bibleId=$bibleId chapter=JHN.1 kind=audio",
        ), logs.messages)
        for (privateValue in listOf(context.device.installationId, context.sessionId, context.userId.orEmpty(), text.deviceId, text.userId.orEmpty())) {
            assertFalse(logs.messages.any { privateValue in it })
        }
    }

    @Test
    fun `ignores unrelated events audio impressions and malformed references`() = runTest {
        val missing = event().copy(element = Element(type = "bible-text", extras = JsonNull))
        transform.transform(pipeline, batch(event(EventType.Interaction), event(elementType = "page"),
            event(elementType = "bible-audio", audio = true), event(EventType.Interaction, "bible-audio"),
            event(reference = "bad"), missing, event().copy(element = null)))
        coVerify(exactly = 0) { queue.enqueue(any()) }
        assertTrue(logs.messages.isEmpty())
    }

    @Test
    fun `requires device and session identifiers and omits a blank user`() = runTest {
        val input = batch(event())
        for (invalid in listOf(null, context.copy(sessionId = " "), context.copy(device = context.device.copy(installationId = "")))) {
            transform.transform(pipeline, input.copy(context = invalid))
        }
        coVerify(exactly = 0) { queue.enqueue(any()) }
        transform.transform(pipeline, input.copy(context = context.copy(userId = " ")))
        assertNull(payload(jobs.single()).userId)
        transform.transform(pipeline, input.copy(context = context.copy(userId = null)))
        assertNull(payload(jobs.last()).userId)
    }

    @Test
    fun `queue failures and cancellation propagate`() = runTest {
        val failure = IllegalStateException("queue unavailable")
        coEvery { queue.enqueue(any()) } throws failure
        assertSame(failure, assertFailsWith<IllegalStateException> { transform.transform(pipeline, batch(event())) })
        coEvery { queue.enqueue(any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { transform.transform(pipeline, batch(event())) }
        assertTrue(logs.messages.isEmpty())
    }

    @Test
    fun `malformed content identifiers do not queue reports or abort the batch`() = runTest {
        val base = event(EventType.Interaction, "bible-audio", true)
        val fields = base.element?.extras as JsonObject
        val invalid = listOf(
            "bible_id" to JsonPrimitive("invalid"),
            "bible_id" to JsonNull,
            "bible_id" to JsonPrimitive(123),
            "bible_version" to JsonPrimitive(0),
            "bible_version" to JsonPrimitive(-1),
            "bible_version" to JsonPrimitive("invalid"),
            "bible_version" to JsonObject(emptyMap()),
            "bible_variant" to JsonPrimitive(" "),
            "bible_variant" to JsonNull,
            "bible_usfm" to JsonNull,
            "bible_audio_id" to JsonPrimitive("invalid"),
        )
        for ((key, value) in invalid) {
            val input = batch(base.copy(element = base.element?.copy(extras = JsonObject(fields + (key to value)))))
            transform.transform(pipeline, input)
        }
        coVerify(exactly = 0) { queue.enqueue(any()) }
        val valid = event(reference = "GEN.intro")
        transform.transform(pipeline, batch(valid))
        assertEquals("GEN.intro", payload(jobs.single()).usfm)
    }
}
