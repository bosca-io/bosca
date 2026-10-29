@file:OptIn(ExperimentalUuidApi::class, InternalDI::class)

package bosca.scripting.jobs

import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCacheSerializer
import bosca.core.annotations.Internal
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import bosca.scripting.engine.KtsEngine
import bosca.scripting.engine.ScriptingSecurityConfiguration
import bosca.scripting.model.Script
import bosca.scripting.model.ScriptType
import bosca.scripting.service.LocalScriptExecutionServiceImpl
import bosca.scripting.service.ScriptService
import bosca.security.model.Group
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.sharedqueue.jobs.listeners.JOB_STATUS_CHANNEL
import bosca.sharedqueue.jobs.listeners.JobStatusNotification
import bosca.sharedqueue.jobs.listeners.NotifyJobStatusListener
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.modules.SerializersModule
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * End-to-end test verifying the remote script execution path: a [RemoteScriptExecutionJob]
 * is processed by [RemoteScriptExecutionJobExecutor] which compiles and executes a real
 * Kotlin script via [KtsEngine], and a [RemoteScriptCompileJob] is validated by
 * [RemoteScriptCompileJobExecutor].
 *
 * This simulates the worker side of the remote execution flow without requiring
 * actual job queue infrastructure.
 */
class RemoteScriptExecutionEndToEndTest {

    private val engine = KtsEngine()
    private val scriptExecutionService = LocalScriptExecutionServiceImpl(engine)
    private val scriptService = mockk<ScriptService>()
    private val securityService = mockk<SecurityService>()
    private val config = ScriptingSecurityConfiguration()
    private val storageData = ConcurrentHashMap<String, ByteArray>()
    private val objectStorageService = object : ObjectStorageService {
        override suspend fun getPath(metadata: bosca.content.metadata.model.Metadata, supplementaryId: UUID?) = error("unused")
        override suspend fun getPath(collection: bosca.content.collection.model.Collection, supplementaryId: UUID?) = error("unused")
        override suspend fun getString(path: ObjectPath) = storageData[path.toString()]?.decodeToString() ?: error("Not found: $path")
        override suspend fun getInputStream(path: ObjectPath): InputStream = (storageData[path.toString()] ?: error("Not found: $path")).inputStream()
        override suspend fun getInputStreamRange(path: ObjectPath, range: LongRange) = error("unused")
        override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
            val bytes = stream.readBytes()
            storageData[path.toString()] = bytes
            return bytes.size.toLong()
        }
        override suspend fun delete(path: ObjectPath) { storageData.remove(path.toString()) }
        override suspend fun getSignedDownloadUrl(path: ObjectPath, principal: bosca.security.model.AuthenticatedPrincipal?, metadata: bosca.content.metadata.model.Metadata, supplementaryId: UUID?, filename: Boolean) = error("unused")
        override suspend fun getSignedUploadUrl(path: ObjectPath, principal: bosca.security.model.AuthenticatedPrincipal, metadata: bosca.content.metadata.model.Metadata, supplementaryId: UUID?) = error("unused")
        override suspend fun getSignedDownloadUrl(path: ObjectPath, principal: bosca.security.model.AuthenticatedPrincipal?, collection: bosca.content.collection.model.Collection, supplementaryId: UUID?, filename: Boolean) = error("unused")
        override suspend fun getSignedUploadUrl(path: ObjectPath, principal: bosca.security.model.AuthenticatedPrincipal, collection: bosca.content.collection.model.Collection, supplementaryId: UUID?) = error("unused")
    }
    private val mockQueue = mockk<JobQueue>(relaxed = true)
    private val publishedMessages = mutableListOf<String>()
    private val pubsubFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTime::class, OffsetDateTimeSerializer())
            contextual(UUID::class, UUIDSerializer())
        }
    }

    private val pubsub = object : PubSubService {
        override suspend fun <T> publish(channel: String, serializer: SerializationStrategy<T>, message: T) {
            val encoded = json.encodeToString(serializer, message)
            publishedMessages.add(encoded)
            pubsubFlow.emit(encoded)
        }

        override fun <T> subscribe(channel: String, deserializer: DeserializationStrategy<T>): Flow<Message<T>> {
            return pubsubFlow.map { Message(channel, json.decodeFromString(deserializer, it)) }
        }
    }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        val connectionPool = mockk<ConnectionPool>(relaxed = true)
        val cacheManager = mockk<CacheManager>(relaxed = true)
        val requestCacheSerializer = mockk<RequestCacheSerializer>(relaxed = true)
        val remoteCache = mockk<Cache<Any>>(relaxed = true)
        coEvery { cacheManager.maybeAddCache<Any>(any(), any()) } returns remoteCache
        coEvery { cacheManager.getCache<Any>(any()) } returns remoteCache
        every { remoteCache.keySerializer } returns mockk(relaxed = true)
        coEvery { remoteCache.get(any()) } returns mockk<CacheValue> {
            every { exists } returns false
            every { value } returns null
        }
        provides { connectionPool }
        provides { cacheManager }
        provides { requestCacheSerializer }
        provides { json }

        val principal = mockk<Principal>(relaxed = true) {
            every { id } returns UUID.random()
        }
        coEvery { securityService.getPrincipalById(any()) } returns principal
        coEvery { securityService.getPrincipalByIdentifier(any()) } returns principal
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns listOf(mockk<Group>(relaxed = true))
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        ProviderRegistry.clear()
    }

    private fun testScript(source: String): Script {
        return Script(
            id = Uuid.random(),
            key = "e2e-test-${Uuid.random()}",
            name = "E2E Test Script",
            source = source,
            type = ScriptType.GENERAL,
            version = 1,
            enabled = true
        )
    }

    @OptIn(Internal::class)
    private fun createExecutionJob(script: Script, contextType: RemoteScriptContextType = RemoteScriptContextType.DEFAULT): Pair<Job, RemoteScriptExecutionJob> {
        val jobDef = RemoteScriptExecutionJob(
            scriptId = script.id,
            contextType = contextType
        )
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(jobDef),
            executor = RemoteScriptExecutionJobExecutor::class
        )
        job.addCallback(JobCallback(listener = NotifyJobStatusListener::class))
        return job to jobDef
    }

    @OptIn(Internal::class)
    private fun createCompileJob(script: Script): Job {
        val jobDef = RemoteScriptCompileJob(scriptId = script.id)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(jobDef),
            executor = RemoteScriptCompileJobExecutor::class
        )
        return job
    }

    @Test
    fun `remote execution of script returning string`() = runBlocking {
        val script = testScript("""main { "hello from remote" }""")
        coEvery { scriptService.get(script.id) } returns script

        val executor = RemoteScriptExecutionJobExecutor(
            scriptService, scriptExecutionService, securityService, objectStorageService, json, config
        )
        val (job, _) = createExecutionJob(script)

        withContext(mockQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        val resultPath = job.getContext().jsonObject["resultPath"]?.jsonPrimitive?.content
        assertNotNull(resultPath)
        val storedResult = Json.parseToJsonElement(storageData[resultPath]!!.decodeToString())
        assertEquals(JsonPrimitive("hello from remote"), storedResult)
    }

    @Test
    fun `remote execution of script returning integer`() = runBlocking {
        val script = testScript("""main { 42 }""")
        coEvery { scriptService.get(script.id) } returns script

        val executor = RemoteScriptExecutionJobExecutor(
            scriptService, scriptExecutionService, securityService, objectStorageService, json, config
        )
        val (job, _) = createExecutionJob(script)

        withContext(mockQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        val resultPath = job.getContext().jsonObject["resultPath"]?.jsonPrimitive?.content
        assertNotNull(resultPath)
        val storedResult = Json.parseToJsonElement(storageData[resultPath]!!.decodeToString())
        assertEquals(JsonPrimitive(42), storedResult)
    }

    @Test
    fun `remote execution of script returning json object`() = runBlocking {
        val script = testScript("""
            main {
                kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive("test"))
                    put("value", kotlinx.serialization.json.JsonPrimitive(123))
                }
            }
        """.trimIndent())
        coEvery { scriptService.get(script.id) } returns script

        val executor = RemoteScriptExecutionJobExecutor(
            scriptService, scriptExecutionService, securityService, objectStorageService, json, config
        )
        val (job, _) = createExecutionJob(script)

        withContext(mockQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        val resultPath = job.getContext().jsonObject["resultPath"]?.jsonPrimitive?.content
        assertNotNull(resultPath)
        val storedResult = Json.parseToJsonElement(storageData[resultPath]!!.decodeToString()).jsonObject
        assertEquals(JsonPrimitive("test"), storedResult["name"])
        assertEquals(JsonPrimitive(123), storedResult["value"])
    }

    @Test
    fun `remote execution of script returning null`() = runBlocking {
        val script = testScript("""main { null }""")
        coEvery { scriptService.get(script.id) } returns script

        val executor = RemoteScriptExecutionJobExecutor(
            scriptService, scriptExecutionService, securityService, objectStorageService, json, config
        )
        val (job, _) = createExecutionJob(script)

        withContext(mockQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        val resultPath = job.getContext().jsonObject["resultPath"]?.jsonPrimitive?.content
        assertNotNull(resultPath)
        val storedResult = Json.parseToJsonElement(storageData[resultPath]!!.decodeToString())
        assertEquals(JsonNull, storedResult)
    }

    @Test
    fun `remote execution of disabled script is skipped`() = runTest {
        val script = testScript("""main { "should not run" }""").copy(enabled = false)
        coEvery { scriptService.get(script.id) } returns script

        val executor = RemoteScriptExecutionJobExecutor(
            scriptService, scriptExecutionService, securityService, objectStorageService, json, config
        )
        val (job, _) = createExecutionJob(script)

        withContext(mockQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        // Context should not be set for disabled scripts
        assertEquals(JsonNull, job.getContext())
    }

    @Test
    fun `remote execution with trigger context`() = runBlocking {
        val script = testScript("""
            main {
                val event = context.get<String>("eventName")
                kotlinx.serialization.json.JsonPrimitive("triggered: ${'$'}event")
            }
        """.trimIndent())
        coEvery { scriptService.get(script.id) } returns script

        val executor = RemoteScriptExecutionJobExecutor(
            scriptService, scriptExecutionService, securityService, objectStorageService, json, config
        )

        val jobDef = RemoteScriptExecutionJob(
            scriptId = script.id,
            contextType = RemoteScriptContextType.TRIGGER,
            eventName = "test-event",
            eventPayload = JsonPrimitive("payload-data")
        )
        @OptIn(Internal::class)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(jobDef),
            executor = RemoteScriptExecutionJobExecutor::class
        )

        withContext(mockQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        val resultPath = job.getContext().jsonObject["resultPath"]?.jsonPrimitive?.content
        assertNotNull(resultPath)
        val storedResult = Json.parseToJsonElement(storageData[resultPath]!!.decodeToString())
        assertEquals(JsonPrimitive("triggered: test-event"), storedResult)
    }

    @Test
    fun `remote compile job succeeds for valid script`() = runBlocking {
        val script = testScript("""main { "compiles fine" }""")
        coEvery { scriptService.get(script.id) } returns script

        val executor = RemoteScriptCompileJobExecutor(scriptService, scriptExecutionService, engine)
        val job = createCompileJob(script)

        withContext(mockQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        val context = job.getContext().jsonObject
        assertEquals(JsonPrimitive(true), context["success"])
    }

    @Test
    fun `remote compile job fails for invalid script`() = runBlocking {
        val script = testScript("""this is not valid kotlin""")
        coEvery { scriptService.get(script.id) } returns script

        val executor = RemoteScriptCompileJobExecutor(scriptService, scriptExecutionService, engine)
        val job = createCompileJob(script)

        var failed = false
        try {
            withContext(mockQueue.asCoroutineContext(job)) {
                executor.execute()
            }
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed, "compilation of invalid script should throw")
    }

    @Test
    fun `notify job status listener publishes on complete`() = runBlocking {
        val script = testScript("""main { "notify test" }""")
        coEvery { scriptService.get(script.id) } returns script

        val executor = RemoteScriptExecutionJobExecutor(
            scriptService, scriptExecutionService, securityService, objectStorageService, json, config
        )
        val (job, _) = createExecutionJob(script)

        withContext(mockQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        // Simulate what JobRunner does after markComplete: fire callbacks
        val listener = NotifyJobStatusListener(pubsub)
        listener.onStatusChanged(job, JobStatus.COMPLETE)

        // Verify the notification was published
        val notifications = publishedMessages.map { raw ->
            json.decodeFromString(JobStatusNotification.serializer(), raw)
        }
        assertEquals(1, notifications.size)
        assertEquals(job.getId(), notifications[0].jobId)
        assertEquals(JobStatus.COMPLETE, notifications[0].status)
        val resultPath = notifications[0].context.jsonObject["resultPath"]?.jsonPrimitive?.content
        assertNotNull(resultPath)
        val storedResult = Json.parseToJsonElement(storageData[resultPath]!!.decodeToString())
        assertEquals(JsonPrimitive("notify test"), storedResult)
    }
}
