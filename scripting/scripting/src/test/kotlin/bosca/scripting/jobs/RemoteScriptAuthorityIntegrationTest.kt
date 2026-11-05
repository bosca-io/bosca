package bosca.scripting.jobs

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.scripting.configuration.JobQueueNames
import bosca.scripting.engine.Engine
import bosca.scripting.engine.ScriptCompilationException
import bosca.scripting.model.ScriptValidationResult
import bosca.storage.service.ObjectNotFoundException
import bosca.scripting.context.DefaultScriptContext
import bosca.scripting.context.ScriptContext
import bosca.scripting.model.Script
import bosca.scripting.model.ScriptType
import bosca.scripting.service.RemoteScriptExecutionServiceImpl
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.security.model.ApiTokenCredentialAttributes
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.model.PrincipalCredential
import bosca.security.service.ApiTokenService
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.sharedqueue.jobs.listeners.JobStatusNotification
import bosca.sharedqueue.jobs.listeners.NotifyJobStatusListener
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.storage.service.ObjectStorageService
import bosca.test.resources.SharedNatsContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Exercises the generated producer, real queue serialization, and worker authority reconstruction. */
@OptIn(InternalDI::class)
class RemoteScriptAuthorityIntegrationTest {
    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(OffsetDateTime::class, OffsetDateTimeSerializer())
        }
    }
    private val principal = Principal(id = UUID.random(), anonymous = false)
    private val group = Group(UUID.random(), "script-users", "Script users", GroupType.PRINCIPAL)
    private val adminGroup = Group(UUID.random(), "admin", "Administrators", GroupType.SYSTEM)
    private val script = Script(
        id = UUID.random(), key = "authority-test", name = "Authority test", source = "main { null }",
        type = ScriptType.GENERAL, version = 7, enabled = true,
    )
    private var attributes = ApiTokenCredentialAttributes(
        identifier = "test-token", name = "Test token", tokenPrefix = "test-prefix",
        scopes = listOf("scripts:execute"), allowedGroups = listOf(group.id.toString()), createdBy = principal.id.toString(),
    )
    private val security = mockk<SecurityService>()
    private val tokens = mockk<ApiTokenService>()
    private val scripts = mockk<ScriptService>()
    private val local = mockk<ScriptExecutionService>()
    private val storage = mockk<ObjectStorageService>()
    private val pubsub = mockk<PubSubService>()
    private val notifications = MutableSharedFlow<JobStatusNotification>()
    private val objects = mutableMapOf<String, String>()
    private lateinit var container: SharedNatsContainer
    private lateinit var queue: JobQueue
    private lateinit var remote: RemoteScriptExecutionServiceImpl
    private lateinit var executor: RemoteScriptExecutionJobExecutor
    private var executedPrincipal: AuthenticatedPrincipal? = null

    @BeforeTest
    fun setup() = runBlocking {
        ProviderRegistry.clear()
        container = SharedNatsContainer().withExposedPorts(4222).withCommand("-js").withReuse(true)
        container.start()
        val pool = container.newConnectionPool(1)
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<ConnectionPool> { mockk(relaxed = true) }
        provides { json }
        queue = NatsJobQueueFactory(pool, json, NatsDistributedLockFactory(pool), null, emptyList(), processExpiredJobs = false)
            .create("script-auth-${UUID.random()}")
        provides<JobQueue>(name = JobQueueNames.scriptingJobQueue) { queue }
        provides { NotifyJobStatusListener(pubsub) }
        coEvery { security.getPrincipalById(principal.id) } returns principal
        coEvery { security.getPrincipalGroups(principal.id) } returns listOf(group, adminGroup)
        coEvery { tokens.getTokenById(42) } answers { PrincipalCredential(principal.id, attributes).copy(id = 42) }
        coEvery { scripts.get(script.id) } returns script
        every { local.isRemoteEnvironment } returns false
        coEvery { local.executeAsJson(any(), any()) } answers {
            executedPrincipal = secondArg<ScriptContext>().authentication.principal()
            JsonPrimitive("executed")
        }
        every { pubsub.subscribe(any(), JobStatusNotification.serializer()) } answers {
            notifications.map { Message(firstArg(), it) }
        }
        coEvery { pubsub.publish(any(), JobStatusNotification.serializer(), any()) } coAnswers {
            notifications.emit(thirdArg())
        }
        coEvery { storage.setInputStream(any(), any(), any()) } answers {
            val bytes = secondArg<java.io.InputStream>().readBytes()
            objects[firstArg<bosca.storage.service.ObjectPath>().toString()] = bytes.decodeToString()
            bytes.size.toLong()
        }
        coEvery { storage.getString(any()) } answers { objects[firstArg<bosca.storage.service.ObjectPath>().toString()] ?: throw ObjectNotFoundException(firstArg()) }
        coEvery { storage.delete(any()) } answers { objects.remove(firstArg<bosca.storage.service.ObjectPath>().toString()) }
        remote = RemoteScriptExecutionServiceImpl(pubsub, storage, json, security, mockk())
        executor = RemoteScriptExecutionJobExecutor(scripts, local, security, storage, json, tokens)
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        if (::container.isInitialized) container.stop()
    }

    private fun caller(scopes: List<String>? = listOf("scripts:execute")) = object : AuthenticationContext(null, null) {
        override fun principal() = ScopedAuthenticatedPrincipal(principal, listOf(group, adminGroup), scopes, setOf(group.id), 42)
    }

    private suspend fun dequeue(): Job = withTimeout(10_000) {
        var result: Job? = null
        while (result == null) result = withContext(kotlinx.coroutines.Dispatchers.IO) { queue.dequeue(200.milliseconds) }
        result
    }

    private fun roundTrip(
        caller: AuthenticationContext = caller(),
        expectedFailure: String? = null,
        beforeExecution: suspend (RemoteScriptExecutionJob) -> Unit = {},
    ) = runBlocking {
        withTimeout(15_000) {
            val response = async {
                runCatching { remote.executeAsJson(script, DefaultScriptContext(caller, this, json = json)) }
            }
            val job = dequeue()
            val definition = json.decodeFromJsonElement(RemoteScriptExecutionJob.serializer(), job.getDefinition())
            assertEquals(script.version, definition.scriptVersion)
            assertEquals(caller.principal()?.let { listOf(group.id) }, definition.groupIds)
            assertEquals((caller.principal() as? ScopedAuthenticatedPrincipal)?.credentialId, definition.credentialId)
            assertFalse(job.getDefinition().toString().contains("test-token"))
            beforeExecution(definition)
            // Ensure this test's notification consumer is ready before completing the worker job.
            notifications.subscriptionCount.first { it > 0 }
            withContext(queue.asCoroutineContext(job)) {
                if (expectedFailure == null) {
                    executor.execute()
                    queue.markComplete(job)
                } else {
                    val failure = assertFailsWith<IllegalStateException> { executor.execute() }
                    assertTrue(failure.message.orEmpty().contains(expectedFailure), failure.message)
                    queue.markFailed(job, failure, retry = false)
                }
            }
            if (expectedFailure == null) {
                assertEquals(JsonPrimitive("executed"), response.await().getOrThrow())
                assertTrue(objects.isEmpty(), "Remote results must be deleted after consumption")
            } else {
                assertTrue(response.await().exceptionOrNull()?.message.orEmpty().contains(expectedFailure))
                coVerify(exactly = 0) { local.executeAsJson(any(), any()) }
            }
        }
    }

    @Test
    fun `unsaved validation crosses real queue and reads a retained result without pubsub`() = runBlocking {
        val compiler = mockk<Engine>(relaxed = true)
        val validator = RemoteScriptExecutionServiceImpl(pubsub, storage, json, security, compiler)
        for (valid in listOf(true, false)) {
            if (!valid) coEvery { compiler.compile<Any>(any(), any(), any()) } throws ScriptCompilationException(listOf("syntax error"))
            val pending = async { validator.validate("main { null }", true) }
            val job = dequeue()
            withContext(queue.asCoroutineContext(job)) {
                assertFailsWith<DelayException> { RemoteScriptCompileJobExecutor(scripts, local, compiler, storage, json).execute() }
                queue.markComplete(job)
            }
            val result = withTimeout(5000) { pending.await() }
            assertEquals(valid, result.valid)
            assertTrue(result.compiled)
            assertTrue(objects.isEmpty())
        }
        coVerify(exactly = 0) { local.executeAsJson(any(), any()) }
        coVerify(exactly = 0) { pubsub.publish(any(), JobStatusNotification.serializer(), any()) }
    }

    @Test
    fun `unsaved validation keeps polling while the retained result is only partly written`() = runBlocking {
        val compiler = mockk<Engine>(relaxed = true)
        val validator = RemoteScriptExecutionServiceImpl(pubsub, storage, json, security, compiler)
        val pending = async { validator.validate("main { null }", true) }
        val job = dequeue()
        val path = "script-validation/${job.getId()}"
        objects[path] = ""
        delay(600)
        objects[path] = "{\"valid\":tr"
        delay(600)
        assertTrue(pending.isActive, "A partly written result must not fail validation")
        withContext(queue.asCoroutineContext(job)) {
            assertFailsWith<DelayException> { RemoteScriptCompileJobExecutor(scripts, local, compiler, storage, json).execute() }
            queue.markComplete(job)
        }
        val result = withTimeout(5000) { pending.await() }
        assertTrue(result.valid)
        assertTrue(objects.isEmpty())
    }

    @Test
    fun `an unread validation result is removed when its retained job returns after expiry`() = runBlocking {
        val compiler = mockk<Engine>(relaxed = true)
        RemoteScriptCompileJob(source = "main { null }", expiresAt = OffsetDateTime.now().minusSeconds(1).toString()).enqueue()
        val job = dequeue()
        val path = "script-validation/${job.getId()}"
        objects[path] = "{}"
        withContext(queue.asCoroutineContext(job)) {
            RemoteScriptCompileJobExecutor(scripts, local, compiler, storage, json).execute()
            queue.markComplete(job)
        }
        assertTrue(objects.isEmpty())
        coVerify(exactly = 0) { compiler.compile<Any>(any(), any(), any()) }
    }

    @Test
    fun `source-only remote validation does not enqueue compilation and transport errors propagate`() = runBlocking {
        val compiler = mockk<Engine>(relaxed = true)
        val validator = RemoteScriptExecutionServiceImpl(pubsub, storage, json, security, compiler)
        assertEquals(ScriptValidationResult(true, false, emptyList()), validator.validate("main { null }", false))
        assertNull(queue.dequeue(200.milliseconds))
        coEvery { storage.getString(any()) } throws IllegalStateException("storage unavailable")
        assertFailsWith<IllegalStateException> { validator.validate("main { null }", true) }
        coVerify(exactly = 0) { compiler.compile<Any>(any(), any(), any()) }
    }

    @Test
    fun `queue preserves scope and group ceiling even if token access later expands`() {
        roundTrip { definition ->
            assertEquals(listOf("scripts:execute"), definition.scopes)
            attributes = attributes.copy(scopes = null, allowedGroups = null)
        }
        val actual = assertIs<ScopedAuthenticatedPrincipal>(executedPrincipal)
        assertEquals(42L, actual.credentialId)
        assertTrue(actual.hasScope("scripts:execute"))
        assertFalse(actual.hasScope("security:manage"))
        assertTrue(actual.hasGroup(group.id))
        assertFalse(actual.hasGroup(adminGroup.id))
    }

    @Test
    fun `anonymous execution stays anonymous on the worker`() {
        roundTrip(AuthenticationContext(null, null))
        assertNull(executedPrincipal)
        coVerify(exactly = 0) { security.getPrincipalByIdentifier(any()) }
        coVerify(exactly = 0) { tokens.getTokenById(any()) }
    }

    @Test
    fun `non token caller retains only submitted groups`() {
        roundTrip(ImpersonatedAuthenticationContext(principal, listOf(group)))
        val actual = kotlin.test.assertNotNull(executedPrincipal)
        assertFalse(actual is ScopedAuthenticatedPrincipal)
        assertTrue(actual.hasGroup(group.id))
        assertFalse(actual.hasGroup(adminGroup.id))
        coVerify(exactly = 0) { tokens.getTokenById(any()) }
    }

    @Test
    fun `retired session-bound credential keeps the captured limits`() {
        val bound = object : AuthenticationContext(null, null) {
            override fun principal() = ScopedAuthenticatedPrincipal(principal, listOf(group, adminGroup), listOf("scripts:execute"), setOf(group.id), 42, sessionBoundCredential = true)
        }
        roundTrip(bound) { definition ->
            assertTrue(definition.sessionBoundCredential)
            coEvery { tokens.getTokenById(42) } returns null
        }
        val actual = assertIs<ScopedAuthenticatedPrincipal>(executedPrincipal)
        assertTrue(actual.sessionBoundCredential)
        assertTrue(actual.hasScope("scripts:execute"))
        assertFalse(actual.hasScope("security:manage"))
        assertFalse(actual.hasGroup(adminGroup.id))
    }

    @Test
    fun `session-bound execution still ends when the caller loses a group`() {
        val bound = object : AuthenticationContext(null, null) {
            override fun principal() = ScopedAuthenticatedPrincipal(principal, listOf(group, adminGroup), listOf("scripts:execute"), setOf(group.id), 42, sessionBoundCredential = true)
        }
        roundTrip(bound, "membership changed") { coEvery { security.getPrincipalGroups(principal.id) } returns listOf(adminGroup) }
    }

    @Test
    fun `missing credential prevents execution`() = roundTrip(expectedFailure = "no longer exists") {
        coEvery { tokens.getTokenById(42) } returns null
    }

    @Test
    fun `revoked credential prevents execution`() = roundTrip(expectedFailure = "revoked or expired") {
        attributes = attributes.copy(revokedAt = OffsetDateTime.now().toString())
    }

    @Test
    fun `expired credential prevents execution`() = roundTrip(expectedFailure = "revoked or expired") {
        attributes = attributes.copy(expiresAt = OffsetDateTime.now().minusSeconds(1).toString())
    }

    @Test
    fun `removed scope prevents execution`() = roundTrip(expectedFailure = "scopes changed") {
        attributes = attributes.copy(scopes = emptyList())
    }

    @Test
    fun `unrestricted token that becomes restricted prevents execution`() = roundTrip(caller(null), "scopes changed")

    @Test
    fun `removed credential group prevents execution`() = roundTrip(expectedFailure = "credential groups changed") {
        attributes = attributes.copy(allowedGroups = emptyList())
    }

    @Test
    fun `removed principal membership prevents execution`() = roundTrip(expectedFailure = "membership changed") {
        coEvery { security.getPrincipalGroups(principal.id) } returns listOf(adminGroup)
    }

    @Test
    fun `deleted principal prevents execution`() = roundTrip(expectedFailure = "caller was deleted") {
        coEvery { security.getPrincipalById(principal.id) } returns principal.copy(deletedAt = OffsetDateTime.now())
    }

    @Test
    fun `credential owner mismatch prevents execution`() = roundTrip(expectedFailure = "another principal") {
        coEvery { tokens.getTokenById(42) } returns PrincipalCredential(UUID.random(), attributes).copy(id = 42)
    }

    @Test
    fun `changed script version prevents execution`() = roundTrip(expectedFailure = "Script changed") {
        coEvery { scripts.get(script.id) } returns script.copy(version = script.version + 1)
    }

    @Test
    fun `deleted script prevents execution`() = roundTrip(expectedFailure = "disabled or deleted") {
        coEvery { scripts.get(script.id) } returns script.copy(deletedAt = OffsetDateTime.now())
    }

    @Test
    fun `jobs without an authority snapshot do not deserialize as unrestricted`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(RemoteScriptExecutionJob.serializer(), """{"scriptId":"${script.id}","contextType":"DEFAULT","principalId":"${principal.id}"}""")
        }
    }
}
