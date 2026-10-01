@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.sharedqueue.jobs

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.provides
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [AbstractJobExecutor] is the base class real executors extend. It reads/writes
 * the running job's typed definition and context through the coroutine-context
 * [Job]/[JobQueue] pair and the DI-provided [Json]. A tiny concrete subclass
 * surfaces each protected helper so the round-trip can be asserted.
 */
class AbstractJobExecutorTest {

    @Serializable
    private data class SampleDef(val value: String) : bosca.queue.annotations.IJobDefinition

    private class SampleExecutor : AbstractJobExecutor<SampleDef>(SampleDef.serializer()) {
        override suspend fun execute() {}
        suspend fun readDefinition(): SampleDef = getJobDefinition()
        suspend fun writeDefinition(def: SampleDef) = setJobDefinition(def)
        suspend fun readContext(): JsonElement = getJobContext()
        suspend fun writeContext(context: JsonObject) = setContext(context)
        fun rawDefinitionOf(job: Job): JsonElement = job.internalDefinition
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val queue = mockk<JobQueue>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        coEvery { queue.setDefinition(any(), any()) } just Runs
        coEvery { queue.setJob(any()) } just Runs
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    @OptIn(Internal::class)
    private fun jobWith(def: SampleDef): Job = InternalJobConstructor(
        definition = json.encodeToJsonElement(SampleDef.serializer(), def),
        executor = SampleExecutor::class,
    )

    @Test
    fun `getJobDefinition decodes the running job's payload`() = runTest {
        val job = jobWith(SampleDef("hello"))
        withContext(queue.asCoroutineContext(job)) {
            assertEquals(SampleDef("hello"), SampleExecutor().readDefinition())
        }
    }

    @Test
    fun `internalDefinition exposes the raw payload element`() = runTest {
        val job = jobWith(SampleDef("raw"))
        val executor = SampleExecutor()
        assertEquals(json.encodeToJsonElement(SampleDef.serializer(), SampleDef("raw")), executor.rawDefinitionOf(job))
    }

    @Test
    fun `setJobDefinition re-encodes and persists via the queue`() = runTest {
        val job = jobWith(SampleDef("before"))
        val captured = slot<JsonElement>()
        coEvery { queue.setDefinition(any(), capture(captured)) } just Runs

        withContext(queue.asCoroutineContext(job)) {
            SampleExecutor().writeDefinition(SampleDef("after"))
        }

        coVerify(exactly = 1) { queue.setDefinition(job, any()) }
        assertEquals(SampleDef("after"), json.decodeFromJsonElement(SampleDef.serializer(), captured.captured))
    }

    @Test
    fun `setContext updates the job and persists it`() = runTest {
        val job = jobWith(SampleDef("x"))
        val context = buildJsonObject { put("k", "v") }

        withContext(queue.asCoroutineContext(job)) {
            SampleExecutor().writeContext(context)
        }

        assertEquals(context, job.getContext())
        coVerify(exactly = 1) { queue.setJob(job) }
    }

    @Test
    fun `getJobContext exposes scheduler supplied context`() = runTest {
        val job = jobWith(SampleDef("x"))
        val context = buildJsonObject { put("scheduledJobId", "job-1") }
        job.setContext(context)

        withContext(queue.asCoroutineContext(job)) {
            assertEquals(context, SampleExecutor().readContext())
        }
    }
}
