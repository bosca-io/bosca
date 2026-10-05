package bosca.sharedqueue.jobs

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEvent
import bosca.sharedqueue.jobs.jobs.MultiJob
import bosca.sharedqueue.jobs.jobs.MultiJobJob
import bosca.sharedqueue.jobs.listeners.JobCompleteNotification
import bosca.sharedqueue.jobs.listeners.JobStatusNotification
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Round-trip coverage for every `@Serializable` payload the queue writes to its
 * backend. Each model is exercised three ways so the generated serializer's
 * default-handling branches are all driven:
 *
 *  1. **Full form** — every field set to a non-default value. Drives the "encode
 *     this field" arm of `write$Self` and the "field present" arm of `deserialize`.
 *  2. **Minimal form** — only the required fields present in the JSON. Drives the
 *     "field absent → use default" arm of `deserialize`.
 *  3. **encodeDefaults=true** — forces `shouldEncodeElementDefault` true so the
 *     optional fields are emitted even when equal to their defaults.
 *
 * The default `Json` (encodeDefaults=false) is used for 1 and 2 so the
 * `|| value != default` short-circuit is exercised from both sides.
 */
class SerializationRoundTripTest {

    private val json = Json
    private val jsonWithDefaults = Json { encodeDefaults = true }

    /** Encode with the default encoder, decode back, and assert structural equality. */
    private fun <T> roundTrip(serializer: KSerializer<T>, value: T, encoder: Json = json): T {
        val text = encoder.encodeToString(serializer, value)
        val decoded = encoder.decodeFromString(serializer, text)
        assertEquals(value, decoded)
        return decoded
    }

    /**
     * Round-trip for non-`data` `@Serializable` classes (identity equality only):
     * encode, decode, re-encode, and assert the two encodings match — proving the
     * decode reconstructed every field the encode wrote.
     */
    private fun <T> roundTripText(serializer: KSerializer<T>, value: T, encoder: Json = json): T {
        val text = encoder.encodeToString(serializer, value)
        val decoded = encoder.decodeFromString(serializer, text)
        assertEquals(text, encoder.encodeToString(serializer, decoded))
        return decoded
    }

    // ----- JobEnqueueEvent (the widest payload — 1 required + 12 optional fields) -----

    @Test
    fun `JobEnqueueEvent full round trip`() {
        val event = JobEnqueueEvent(
            jobId = UUID.random(),
            executor = "com.example.MyExecutor",
            executorName = "my-executor",
            queue = "queue",
            enqueuedAt = OffsetDateTime.now(),
            delayed = true,
            delayedUntil = OffsetDateTime.now().plusMinutes(10),
            status = JobStatus.PENDING,
            errorMessage = "boom",
            definition = buildJsonObject { put("k", "v") },
            context = buildJsonObject { put("c", 1) },
            parentJobId = UUID.random(),
            displayName = "My Job",
        )
        roundTrip(JobEnqueueEvent.serializer(), event)
        roundTrip(JobEnqueueEvent.serializer(), event, jsonWithDefaults)
    }

    @Test
    fun `JobEnqueueEvent minimal round trip uses defaults for absent fields`() {
        val minimal = JobEnqueueEvent(jobId = UUID.random())
        // Only the required jobId is emitted; every optional field falls back to its default on decode.
        val decoded = roundTrip(JobEnqueueEvent.serializer(), minimal)
        assertEquals(null, decoded.executor)
        assertEquals(false, decoded.delayed)
        assertEquals(null, decoded.status)
        assertEquals(null, decoded.parentJobId)
        // encodeDefaults=true emits every field even at its default, exercising the other arm.
        roundTrip(JobEnqueueEvent.serializer(), minimal, jsonWithDefaults)
    }

    // ----- JobStatusNotification -----

    @Test
    fun `JobStatusNotification round trips full and minimal`() {
        val full = JobStatusNotification(
            jobId = UUID.random(),
            status = JobStatus.FAILED_AND_COMPLETE,
            context = buildJsonObject { put("k", "v") },
            errorMessage = "failed",
        )
        roundTrip(JobStatusNotification.serializer(), full)
        roundTrip(JobStatusNotification.serializer(), full, jsonWithDefaults)

        val minimal = JobStatusNotification(jobId = UUID.random(), status = JobStatus.COMPLETE)
        val decoded = roundTrip(JobStatusNotification.serializer(), minimal)
        assertEquals(JsonNull, decoded.context)
        assertEquals(null, decoded.errorMessage)
        roundTrip(JobStatusNotification.serializer(), minimal, jsonWithDefaults)
    }

    // ----- JobCompleteNotification -----

    @Test
    fun `JobCompleteNotification round trips`() {
        val n = JobCompleteNotification(jobId = UUID.random(), context = buildJsonObject { put("k", "v") })
        roundTrip(JobCompleteNotification.serializer(), n)
        roundTrip(JobCompleteNotification.serializer(), n, jsonWithDefaults)
    }

    // ----- MultiJob / MultiJobJob -----

    @Test
    fun `MultiJob round trips full and minimal`() {
        val full = MultiJob(
            jobs = listOf(
                MultiJobJob(name = "a", type = "metadata", configuration = buildJsonObject { put("x", 1) }),
                MultiJobJob(name = "b", type = "collection"),
            ),
            id = UUID.random(),
            version = 3,
            languageTag = "en",
            type = "metadata",
        )
        roundTrip(MultiJob.serializer(), full)
        roundTrip(MultiJob.serializer(), full, jsonWithDefaults)

        val minimal = MultiJob(jobs = emptyList())
        val decoded = roundTrip(MultiJob.serializer(), minimal)
        assertEquals(null, decoded.id)
        assertEquals(null, decoded.version)
        assertEquals(null, decoded.languageTag)
        assertEquals(null, decoded.type)
        roundTrip(MultiJob.serializer(), minimal, jsonWithDefaults)
    }

    @Test
    fun `MultiJobJob round trips with and without configuration`() {
        val withConfig = MultiJobJob(name = "a", type = "t", configuration = buildJsonObject { put("x", 1) })
        roundTrip(MultiJobJob.serializer(), withConfig)
        roundTrip(MultiJobJob.serializer(), withConfig, jsonWithDefaults)

        val withoutConfig = MultiJobJob(name = "b", type = "t")
        val decoded = roundTrip(MultiJobJob.serializer(), withoutConfig)
        assertEquals(null, decoded.configuration)
        roundTrip(MultiJobJob.serializer(), withoutConfig, jsonWithDefaults)
    }

    // ----- SerializedJob / SerializedCallback -----

    private fun serializedCallback(withName: Boolean) = SerializedCallback(
        context = buildJsonObject { put("k", "v") },
        listener = "com.example.Listener",
        listenerName = if (withName) "listener-a" else null,
    )

    @Test
    fun `SerializedCallback round trips full and minimal`() {
        roundTripText(SerializedCallback.serializer(), serializedCallback(withName = true))
        roundTripText(SerializedCallback.serializer(), serializedCallback(withName = true), jsonWithDefaults)

        // Minimal: only the required `listener`; context defaults to JsonNull, name to null.
        val minimalText = """{"listener":"com.example.Listener"}"""
        val decoded = json.decodeFromString(SerializedCallback.serializer(), minimalText)
        assertEquals(JsonNull, decoded.context)
        assertEquals(null, decoded.listenerName)
    }

    @Test
    fun `SerializedJob round trips full and minimal`() {
        val childless = fun(runOnFailure: Boolean, withDisplayName: Boolean) = SerializedJob(
            parentId = UUID.random(),
            id = UUID.random(),
            type = "com.example.Job",
            status = JobStatus.RUNNING,
            failures = 2,
            maxFailures = 10,
            runOnFailure = runOnFailure,
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now(),
            definition = buildJsonObject { put("d", 1) },
            executor = "com.example.Executor",
            executorName = "exec",
            displayName = if (withDisplayName) "Display" else null,
            children = emptyList(),
            callbacks = listOf(serializedCallback(withName = true)),
            context = buildJsonObject { put("c", 2) },
        )

        val parent = SerializedJob(
            parentId = null,
            id = UUID.random(),
            type = "com.example.Parent",
            status = JobStatus.COMPLETE,
            failures = 0,
            maxFailures = 10,
            runOnFailure = true,
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now(),
            definition = buildJsonObject { put("d", 0) },
            executor = "com.example.Executor",
            executorName = null,
            displayName = "Parent",
            // A nested child exercises the recursive list serializer.
            children = listOf(childless(true, true), childless(false, false)),
            callbacks = emptyList(),
            context = JsonNull,
        )

        roundTripText(SerializedJob.serializer(), parent)
        roundTripText(SerializedJob.serializer(), parent, jsonWithDefaults)

        // Minimal JSON omits the defaulted runOnFailure/displayName fields.
        val minimalText = json.encodeToString(SerializedJob.serializer(), childless(false, false))
        val decoded = json.decodeFromString(SerializedJob.serializer(), minimalText)
        assertEquals(false, decoded.runOnFailure)
        assertEquals(null, decoded.displayName)
    }

    // ----- missing required field drives the deserializer's MissingFieldException arm -----

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun `decoding without required fields throws MissingFieldException`() {
        // Each serializer's generated deserialize() has a "required field absent" arm that a
        // well-formed round-trip never hits; empty JSON drives it for every model.
        assertFailsWith<MissingFieldException> { json.decodeFromString(JobEnqueueEvent.serializer(), "{}") }
        assertFailsWith<MissingFieldException> { json.decodeFromString(JobStatusNotification.serializer(), "{}") }
        assertFailsWith<MissingFieldException> { json.decodeFromString(JobCompleteNotification.serializer(), "{}") }
        assertFailsWith<MissingFieldException> { json.decodeFromString(MultiJob.serializer(), "{}") }
        assertFailsWith<MissingFieldException> { json.decodeFromString(MultiJobJob.serializer(), "{}") }
        assertFailsWith<MissingFieldException> { json.decodeFromString(SerializedJob.serializer(), "{}") }
        assertFailsWith<MissingFieldException> { json.decodeFromString(SerializedCallback.serializer(), "{}") }
    }
}
