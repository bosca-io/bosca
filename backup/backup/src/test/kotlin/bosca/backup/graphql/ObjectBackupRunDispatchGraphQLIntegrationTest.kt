package bosca.backup.graphql

import bosca.backup.BackupJobQueueNames
import bosca.backup.ObjectBackupDestinationBusyException
import bosca.backup.ObjectBackupDispatchService
import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupRun
import bosca.backup.ObjectBackupRunStatus
import bosca.backup.ObjectBackupStorageService
import bosca.db.afterCommit
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedNatsContainer
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Authorized GraphQL controls through generated services, PostgreSQL, NATS and real workers. */
@OptIn(InternalDI::class)
class ObjectBackupRunDispatchGraphQLIntegrationTest : ObjectBackupGraphQLTest() {
    private lateinit var nats: SharedNatsContainer
    private lateinit var natsPool: NatsConnectionPool
    private lateinit var locks: NatsDistributedLockFactory
    private lateinit var queue: JobQueue
    private lateinit var runner: JobRunner
    private var publicationFailure = false

    override fun applicationConfig(): ApplicationConfig {
        val configurations = mapOf(
            "source-1" to StorageConfiguration("fs", "", basePath = directory.resolve("source").toString()),
            "destination-1" to StorageConfiguration("fs", "", basePath = directory.resolve("destination").toString()),
            "target-1" to StorageConfiguration("fs", "", basePath = directory.resolve("target").toString()),
            "source.σ/path" to StorageConfiguration("fs", "", basePath = directory.resolve("source").toString()),
            "s3-retained" to StorageConfiguration(
                "s3", "", bucket = "backup", region = "us-east-1", endpoint = "http://127.0.0.1:1",
                accessKeyId = "private-key-for-api-test", accessKeySecret = "private-secret-for-api-test",
            ),
        )
        val catalog = JsonObject(configurations.mapValues {
            Json.encodeToJsonElement(StorageConfiguration.serializer(), it.value)
        })
        val root = JsonObject(mapOf("backup" to JsonObject(mapOf("objectStores" to catalog))))
        return ApplicationConfig.load(Json.encodeToString(JsonObject.serializer(), root).byteInputStream())
    }

    override suspend fun registerQueue() {
        publicationFailure = false
        nats = SharedNatsContainer().apply { start() }
        natsPool = nats.newConnectionPool(2)
        locks = NatsDistributedLockFactory(natsPool)
        val factory = NatsJobQueueFactory(
            natsPool, json, locks, enqueueEventChannel = null,
            enqueueCallbacks = emptyList(), processExpiredJobs = false,
        )
        val physicalQueue = "graphql-object-run-" + UUID.random().toString().take(8)
        provides<JobQueueFactory> {
            object : JobQueueFactory {
                override fun create(name: String): JobQueue {
                    assertEquals(BackupJobQueueNames.backupQueue, name)
                    val delegate = factory.create(physicalQueue)
                    return object : JobQueue by delegate {
                        override suspend fun enqueueIfAbsent(job: Job): UUID {
                            afterCommit {
                                if (publicationFailure) throw IOException("broker publication unavailable")
                                delegate.enqueueIfAbsent(job)
                            }
                            return job.getId()
                        }
                    }
                }
            }
        }
    }

    @BeforeTest
    fun initializeQueue(): Unit = runBlocking {
        queue = provide(name = BackupJobQueueNames.backupJobQueue)
        assertNull(queue.dequeue(25.milliseconds))
        runner = JobRunner(queue, 1, locks)
    }

    override suspend fun stopQueue() {
        if (::runner.isInitialized) runner.shutdown()
        if (::nats.isInitialized) nats.stop()
    }

    @Test
    fun `binding returns immutable locations without credentials or opening stores`(): Unit = runBlocking {
        val response = bind(listOf("source-1", "destination-1", "s3-retained"))
        val values = data(response).getValue("bindStorage").jsonArray.map { it.jsonObject }
        assertEquals(listOf("destination-1", "s3-retained", "source-1"), values.map { it.string("key") }.sorted())
        val s3 = values.single { it.string("key") == "s3-retained" }
        assertEquals("s3", s3.string("type"))
        assertEquals("backup", s3.string("bucket"))
        assertEquals("us-east-1", s3.string("region"))
        assertEquals("http://127.0.0.1:1", s3.string("endpoint"))
        assertEquals(JsonNull, s3["basePath"])
        assertFalse(response.toString().contains("private-key-for-api-test"))
        assertFalse(response.toString().contains("private-secret-for-api-test"))
        assertEquals(values, data(bind(listOf("source-1", "destination-1", "s3-retained")))
            .getValue("bindStorage").jsonArray.map { it.jsonObject })
        assertEquals(s3, data(readStorage("s3-retained")).getValue("storage"))
        assertTrue(directory.resolve("destination").toFile().listFiles().isNullOrEmpty())
        assertTrue(queuedKeys().isEmpty())
    }

    @Test
    fun `a later binding conflict rolls back every earlier selected address`(): Unit = runBlocking {
        val existing = request {
            provide<ObjectBackupStorageService>().bind("source-1", StorageConfiguration(
                "fs", "", basePath = directory.resolve("different-source").toString(),
            ))
        }
        assertError(bind(listOf("destination-1", "source-1")), "conflicts")
        assertEquals(existing, request { provide<ObjectBackupStorageService>().get("source-1") })
        assertNull(request { provide<ObjectBackupStorageService>().get("destination-1") })
        assertEquals(run, request { runs.get(run.key) })
    }

    @Test
    fun `unknown selection leaves no partially bound locations`(): Unit = runBlocking {
        assertError(bind(listOf("source-1", "unknown")), "unknown")
        assertNull(request { provide<ObjectBackupStorageService>().get("source-1") })
        assertEquals(JsonArray(emptyList()), data(bind(emptyList())).getValue("bindStorage"))
        assertTrue(queuedKeys().isEmpty())
    }

    @Test
    fun `typed immutable mappings round trip through configuration and run reads`(): Unit = runBlocking {
        data(bind(listOf("source-1", "destination-1")))
        val configured = data(configure()).getValue("configureStorage").jsonObject
        assertEquals(run.key, configured.string("key"))
        assertEquals("destination-1", configured.string("backupKey"))
        assertEquals(mapping("content", "source-1"), configured.getValue("stores").jsonArray.single())
        assertTrue(configured.string("created").isNotBlank())
        assertEquals(configured, data(configure()).getValue("configureStorage"))
        val read = data(execute("""
            { backups { objectRuns { run(key: "${run.key}") { storage { $mappingFields } } } } }
        """))
        assertEquals(configured, read.getValue("run").jsonObject.getValue("storage"))
        assertEquals(run, request { runs.get(run.key) })
        assertTrue(queuedKeys().isEmpty())
    }

    @Test
    fun `duplicate logical mapping inputs fail before entries can collapse into a map`(): Unit = runBlocking {
        data(bind(listOf("source-1", "destination-1", "target-1")))
        for (mappings in listOf(
            listOf(mapping("content", "source-1"), mapping("content", "source-1")),
            listOf(mapping("content", "source-1"), mapping("content", "target-1")),
        )) {
            assertError(configure(mappings = mappings), "Duplicate")
            assertNull(request { runs.getStorage(run.key) })
        }
        assertEquals(run, request { runs.get(run.key) })
    }

    @Test
    fun `invalid incomplete unbound and conflicting mappings preserve intent`(): Unit = runBlocking {
        assertError(configure(), "not bound")
        data(bind(listOf("source-1", "destination-1", "target-1")))
        assertError(configure(mappings = emptyList()), "match")
        assertError(configure(mappings = listOf(mapping("other", "source-1"))), "match")
        assertError(configure(mappings = listOf(mapping("content", "destination-1"))), "independent")
        assertError(configure(backupKey = "target-1"), "destination")
        assertNull(request { runs.getStorage(run.key) })
        data(configure())
        val saved = request { runs.getStorage(run.key) }
        assertError(configure(mappings = listOf(mapping("content", "target-1"))), "Conflicting")
        assertEquals(saved, request { runs.getStorage(run.key) })
        assertTrue(queuedKeys().isEmpty())
    }

    @Test
    fun `dispatch returns a stable identity with a key and version only queue payload`(): Unit = runBlocking {
        configured()
        val dispatched = data(dispatch()).getValue("dispatch").jsonObject
        val id = UUID.parse(dispatched.string("key"))
        assertEquals(run.key, dispatched.string("runKey"))
        assertEquals(run.version, dispatched.getValue("version").jsonPrimitive.long)
        assertTrue(dispatched.string("created").isNotBlank())
        val persisted = Json.parseToJsonElement(assertNotNull(state(id))).jsonObject
        assertEquals(JsonObject(mapOf("key" to JsonPrimitive(run.key), "version" to JsonPrimitive(run.version))),
            persisted.getValue("definition"))
        assertEquals(dispatched, data(dispatch()).getValue("dispatch"))
        assertEquals(dispatched, data(readDispatch(run.key, run.version)).getValue("dispatch"))
        assertEquals(listOf(id.toString()), queuedKeys())
        assertEquals(run, request { runs.get(run.key) })
        assertTrue(directory.resolve("destination").toFile().listFiles().isNullOrEmpty())
    }

    @Test
    fun `GraphQL configuration and dispatch back up and restore on a real worker`(): Unit = runBlocking {
        configured()
        data(dispatch())
        runner.run()
        val completed = awaitCompleted(run.key)
        assertEquals(1, completed.attempts)
        assertEquals("data", destination.getString(StringObjectPath(first.payloadPath)))
        data(bind(listOf("target-1")))
        val restoreResponse = execute("""
            mutation Restore(${'$'}inventory: ObjectBackupManifestInput!) {
                backups { objectRuns { prepareRestore(inventory: ${'$'}inventory,
                    destinationId: "restored-instance", path: "${completed.path}") { key version } } }
            }
        """, variables = buildJsonObject {
            put("inventory", json.encodeToJsonElement(ObjectBackupManifest.serializer(), completed.inventory))
        })
        val restore = data(restoreResponse).getValue("prepareRestore").jsonObject
        val key = restore.string("key")
        data(configure(key, mappings = listOf(mapping("content", "target-1"))))
        data(dispatch(key, restore.getValue("version").jsonPrimitive.long))
        assertEquals(1, awaitCompleted(key).attempts)
        assertEquals("data", storage("target").getString(StringObjectPath(first.key)))
        assertEquals("more", storage("target").getString(StringObjectPath(second.key)))
    }

    @Test
    fun `saved GraphQL intent survives a failed publication and recovers without another identity`(): Unit = runBlocking {
        configured()
        publicationFailure = true
        val dispatched = try {
            data(dispatch()).getValue("dispatch").jsonObject
        } finally {
            publicationFailure = false
        }
        val id = UUID.parse(dispatched.string("key"))
        assertNull(state(id))
        assertEquals(dispatched, data(readDispatch(run.key, run.version)).getValue("dispatch"))
        assertEquals(1, provide<ObjectBackupDispatchService>().recover())
        assertNotNull(state(id))
        runner.run()
        assertEquals(1, awaitCompleted(run.key).attempts)
        assertEquals(dispatched, data(readDispatch(run.key, run.version)).getValue("dispatch"))
    }

    @Test
    fun `dispatch rejects unconfigured stale active and terminal versions without new queue identities`(): Unit = runBlocking {
        assertError(dispatch(), "not configured")
        configured()
        assertError(dispatch(version = run.version + 1), "observed pending")
        val active = request { runs.start(run.key, run.version) }
        assertError(dispatch(version = active.version), "observed pending")
        val failed = request { runs.fail(run.key, active.version, "stopped test writer") }
        assertError(dispatch(version = failed.version), "observed pending")
        val pending = request { runs.retry(run.key, failed.version) }
        val cancelled = request { runs.cancel(run.key, pending.version, "operator cancelled") }
        assertError(dispatch(version = cancelled.version), "observed pending")
        assertTrue(queuedKeys().isEmpty())
        assertEquals(cancelled, request { runs.get(run.key) })
    }

    @Test
    fun `old API dispatch cannot start a newer explicit retry`(): Unit = runBlocking {
        configured()
        val old = data(dispatch()).getValue("dispatch").jsonObject
        val retry = request {
            val cancelled = runs.cancel(run.key, run.version, "replace queued request")
            runs.retry(run.key, cancelled.version)
        }
        assertError(dispatch(), "observed pending")
        val next = data(dispatch(version = retry.version)).getValue("dispatch").jsonObject
        assertTrue(old.string("key") != next.string("key"))
        assertEquals(old, data(readDispatch(run.key, run.version)).getValue("dispatch"))
        runner.run()
        assertEquals(1, awaitCompleted(run.key).attempts)
        assertError(dispatch(version = assertNotNull(request { runs.get(run.key) }).version), "observed pending")
    }

    @Test
    fun `missing lookups and historical unconfigured mappings return null without mutation`(): Unit = runBlocking {
        assertEquals(JsonNull, data(readStorage("unknown")).getValue("storage"))
        assertEquals(JsonNull, data(readDispatch("unknown", 0)).getValue("dispatch"))
        val read = data(execute("""
            { backups { objectRuns { run(key: "${run.key}") { storage { key } } } } }
        """))
        assertEquals(JsonNull, read.getValue("run").jsonObject.getValue("storage"))
        assertError(readDispatch(run.key, -1), "version")
        assertError(dispatch(version = -1), "version")
        assertEquals(run, request { runs.get(run.key) })
    }

    @Test
    fun `anonymous ordinary and restricted tokens cannot bind configure dispatch or read storage identities`(): Unit = runBlocking {
        val ordinary = ImpersonatedAuthenticationContext(Principal(), emptyList())
        for (authentication in listOf(anonymous, ordinary, scoped(listOf("content:read")),
            scoped(listOf("security:manage"), emptySet()))) {
            assertError(bind(listOf("source-1"), authentication), "Unauthorized access")
            assertError(configure(authentication = authentication), "Unauthorized access")
            assertError(dispatch(authentication = authentication), "Unauthorized access")
            assertError(readStorage("source-1", authentication), "Unauthorized access")
            assertError(readDispatch(run.key, run.version, authentication), "Unauthorized access")
        }
        assertNull(request { provide<ObjectBackupStorageService>().get("source-1") })
        assertNull(request { runs.getStorage(run.key) })
        assertEquals(run, request { runs.get(run.key) })
        assertTrue(queuedKeys().isEmpty())
    }

    @Test
    fun `super administrators and management scoped administrators can configure and dispatch`(): Unit = runBlocking {
        val sa = ImpersonatedAuthenticationContext(Principal(), listOf(
            Group(name = "sa", description = "", type = GroupType.SYSTEM),
        ))
        for ((index, authentication) in listOf(sa, scoped(listOf("security:manage"))).withIndex()) {
            data(bind(listOf("source-1", "destination-1"), authentication))
            val prepared = request { runs.prepare(inventory.copy(id = "permitted-$index"), "destination-1") }
            data(configure(prepared.key, authentication = authentication))
            val dispatched = data(dispatch(prepared.key, prepared.version, authentication)).getValue("dispatch").jsonObject
            assertEquals(prepared.key, dispatched.string("runKey"))
        }
        assertEquals(2, queuedKeys().size)
    }

    @Test
    fun `schema has typed mappings and exposes only saved address fields`(): Unit = runBlocking {
        for ((name, expected) in listOf(
            "ObjectBackupStorage" to setOf("key", "type", "endpoint", "bucket", "region", "basePath", "created"),
            "ObjectBackupRunStorage" to setOf("key", "backupKey", "stores", "created"),
            "ObjectBackupStoreMapping" to setOf("store", "storageKey"),
            "ObjectBackupDispatch" to setOf("key", "runKey", "version", "created"),
        )) {
            val result = execute("{ __type(name: \"$name\") { fields { name } } }")
            assertFalse("errors" in result, result.toString())
            val fields = result.getValue("data").jsonObject.getValue("__type").jsonObject.getValue("fields").jsonArray
            assertEquals(expected, fields.map { it.jsonObject.string("name") }.toSet())
        }
        val type = execute("""
            { __type(name: "ObjectBackupStoreMappingInput") { inputFields { name type { kind } } } }
        """).getValue("data").jsonObject.getValue("__type").jsonObject
        val inputs = type.getValue("inputFields").jsonArray
        assertEquals(setOf("store", "storageKey"), inputs.map { it.jsonObject.string("name") }.toSet())
        assertTrue(inputs.all { it.jsonObject.getValue("type").jsonObject.string("kind") == "NON_NULL" })
    }

    @Test
    fun `malformed mapping and configuration payloads are rejected before service effects`(): Unit = runBlocking {
        for (mappings in listOf(
            listOf(JsonNull),
            listOf(JsonObject(mapOf("store" to JsonPrimitive("content")))),
            listOf(JsonObject(mapping("content", "source-1") + ("credentials" to JsonPrimitive("secret")))),
        )) {
            val response = configure(mappings = mappings)
            val errors = assertNotNull(response["errors"], response.toString()).jsonArray
            assertTrue(errors.isNotEmpty())
        }
        val response = execute("""
            mutation { backups { objectRuns { bindStorage(keys: ["source-1"], configurations: {}) { key } } } }
        """)
        assertTrue(assertNotNull(response["errors"], response.toString()).jsonArray.isNotEmpty())
        assertNull(request { provide<ObjectBackupStorageService>().get("source-1") })
        assertNull(request { runs.getStorage(run.key) })
        assertTrue(queuedKeys().isEmpty())
    }

    @Test
    fun `literal storage IDs and multiple logical stores round trip and share disjoint restore targets`(): Unit = runBlocking {
        val sourceId = "source.σ/path"
        data(bind(listOf(sourceId, "destination-1", "target-1")))
        val declared = inventory.copy(id = "literal-mappings", objects = listOf(
            first.copy(store = "z.δ"), second.copy(store = "a.assets/🖼"),
        ))
        val prepared = request { runs.prepare(declared, "destination-1") }
        val configured = data(configure(prepared.key, mappings = listOf(
            mapping("z.δ", sourceId), mapping("a.assets/🖼", sourceId),
        ))).getValue("configureStorage").jsonObject
        assertEquals(listOf("a.assets/🖼", "z.δ"), configured.getValue("stores").jsonArray.map {
            it.jsonObject.string("store")
        })
        assertEquals(sourceId, data(readStorage(sourceId)).getValue("storage").jsonObject.string("key"))
        data(dispatch(prepared.key, prepared.version))
        runner.run()
        val completed = awaitCompleted(prepared.key)
        val restore = request { runs.prepareRestore(completed.inventory, "shared-restore", completed.path) }
        data(configure(restore.key, mappings = listOf(
            mapping("z.δ", "target-1"), mapping("a.assets/🖼", "target-1"),
        )))
        data(dispatch(restore.key, restore.version))
        assertEquals(1, awaitCompleted(restore.key).attempts)
        assertEquals("data", storage("target").getString(StringObjectPath(first.key)))
        assertEquals("more", storage("target").getString(StringObjectPath(second.key)))
    }

    @Test
    fun `ambiguous shared restore target keys fail before saving a mapping`(): Unit = runBlocking {
        data(bind(listOf("destination-1", "target-1")))
        val declared = inventory.copy(id = "overlapping-targets", objects = listOf(
            first.copy(store = "primary"), first.copy(store = "secondary"),
        ))
        val restore = request { runs.prepareRestore(declared, "overlapping-restore", "input/retained.json") }
        assertError(configure(restore.key, mappings = listOf(
            mapping("primary", "target-1"), mapping("secondary", "target-1"),
        )), "same destination object")
        assertNull(request { runs.getStorage(restore.key) })
        assertEquals(restore, request { runs.get(restore.key) })
        assertTrue(queuedKeys().isEmpty())
    }

    @Test
    fun `an unconfigured pending run can be cancelled without changing its checkpoints`(): Unit = runBlocking {
        val checkpoints = request { runs.items(run.key, 0, 100) }
        val cancelled = data(cancelPending(reason = "No longer needed")).getValue("cancelPending").jsonObject
        assertEquals("CANCELLED", cancelled.string("status"))
        assertEquals(run.version + 1, cancelled.getValue("version").jsonPrimitive.long)
        assertEquals("No longer needed", cancelled.string("error"))
        val saved = assertNotNull(request { runs.get(run.key) })
        assertEquals(0, saved.attempts)
        assertEquals(2, saved.result?.pending)
        assertEquals(checkpoints, request { runs.items(run.key, 0, 100) })
        assertNull(request { runs.getStorage(run.key) })
        assertTrue(queuedKeys().isEmpty())
    }

    @Test
    fun `pending cancellation preserves dispatch history and stale delivery performs no transfer`(): Unit = runBlocking {
        configured()
        val dispatched = data(dispatch()).getValue("dispatch").jsonObject
        val checkpoints = request { runs.items(run.key, 0, 100) }
        data(cancelPending())
        val cancelled = request { runs.get(run.key) }
        assertEquals(0, provide<ObjectBackupDispatchService>().recover())
        assertEquals(dispatched, data(readDispatch(run.key, run.version)).getValue("dispatch"))
        runner.run()
        awaitDrained(UUID.parse(dispatched.string("key")))
        assertEquals(cancelled, request { runs.get(run.key) })
        assertEquals(checkpoints, request { runs.items(run.key, 0, 100) })
        assertTrue(directory.resolve("destination").toFile().listFiles().isNullOrEmpty())
    }

    @Test
    fun `retrying a cancelled run requires dispatching its new version`(): Unit = runBlocking {
        configured()
        val original = data(dispatch()).getValue("dispatch").jsonObject
        data(cancelPending())
        val cancelled = assertNotNull(request { runs.get(run.key) })
        val storage = request { runs.getStorage(run.key) }
        val checkpoints = request { runs.items(run.key, 0, 100) }
        val retried = data(retry(version = cancelled.version)).getValue("retry").jsonObject
        val version = retried.getValue("version").jsonPrimitive.long
        assertEquals("PENDING", retried.string("status"))
        assertEquals(cancelled.version + 1, version)
        assertEquals(JsonNull, retried["error"])
        assertEquals(JsonNull, retried["result"])
        assertEquals(0, request { runs.get(run.key) }?.attempts)
        assertEquals(storage, request { runs.getStorage(run.key) })
        assertEquals(checkpoints, request { runs.items(run.key, 0, 100) })
        assertEquals(listOf(original.string("key")), queuedKeys())
        runner.run()
        awaitDrained(UUID.parse(original.string("key")))
        assertEquals(ObjectBackupRunStatus.PENDING, request { runs.get(run.key) }?.status)
        assertTrue(directory.resolve("destination").toFile().listFiles().isNullOrEmpty())
        assertError(retry(version = cancelled.version), "state, version")
        assertError(dispatch(), "observed pending")
        val next = data(dispatch(version = version)).getValue("dispatch").jsonObject
        assertTrue(next.string("key") != original.string("key"))
        assertEquals(1, awaitCompleted(run.key).attempts)
        assertEquals(original, data(readDispatch(run.key, run.version)).getValue("dispatch"))
    }

    @Test
    fun `retrying a real partial backup preserves verified items and resumes the missing payload`(): Unit = runBlocking {
        configured()
        assertTrue(directory.resolve("source").resolve(second.key).toFile().delete())
        data(dispatch())
        runner.run()
        val partial = awaitStatus(run.key, ObjectBackupRunStatus.PARTIAL)
        assertEquals(1, partial.attempts)
        assertEquals(1, partial.result?.completed)
        assertError(cancelPending(version = partial.version), "observed pending")
        val checkpoints = request { runs.items(run.key, 0, 100) }
        val verified = checkpoints.single { it.payload.key == first.key }
        val storage = request { runs.getStorage(run.key) }
        write(source, second.key, "more")
        val retried = data(retry(version = partial.version)).getValue("retry").jsonObject
        assertEquals("PENDING", retried.string("status"))
        assertEquals(checkpoints, request { runs.items(run.key, 0, 100) })
        assertEquals(storage, request { runs.getStorage(run.key) })
        assertEquals(1, request { runs.get(run.key) }?.attempts)
        data(dispatch(version = retried.getValue("version").jsonPrimitive.long))
        assertEquals(2, awaitCompleted(run.key).attempts)
        assertEquals(verified, request { runs.items(run.key, 0, 100) }.single { it.key == verified.key })
        assertEquals("more", destination.getString(StringObjectPath(second.payloadPath)))
    }

    @Test
    fun `failed runs can be explicitly retried through GraphQL without an implicit enqueue`(): Unit = runBlocking {
        configured()
        val failed = request {
            val running = runs.start(run.key, run.version)
            runs.fail(run.key, running.version, "stopped test writer")
        }
        assertEquals(ObjectBackupRunStatus.FAILED, failed.status)
        val retried = data(retry(version = failed.version)).getValue("retry").jsonObject
        assertEquals("PENDING", retried.string("status"))
        assertTrue(queuedKeys().isEmpty())
        assertEquals(1, request { runs.get(run.key) }?.attempts)
        data(dispatch(version = retried.getValue("version").jsonPrimitive.long))
        runner.run()
        assertEquals(2, awaitCompleted(run.key).attempts)
    }

    @Test
    fun `a cancelled restore can be retried and dispatched to a real worker`(): Unit = runBlocking {
        configured()
        data(dispatch())
        runner.run()
        val completed = awaitCompleted(run.key)
        data(bind(listOf("target-1")))
        val restore = request { runs.prepareRestore(completed.inventory, "restored", completed.path) }
        data(configure(restore.key, mappings = listOf(mapping("content", "target-1"))))
        data(cancelPending(restore.key, restore.version))
        val cancelled = assertNotNull(request { runs.get(restore.key) })
        val retried = data(retry(restore.key, cancelled.version)).getValue("retry").jsonObject
        data(dispatch(restore.key, retried.getValue("version").jsonPrimitive.long))
        assertEquals(1, awaitCompleted(restore.key).attempts)
        assertEquals("data", storage("target").getString(StringObjectPath(first.key)))
        assertEquals("more", storage("target").getString(StringObjectPath(second.key)))
    }

    @Test
    fun `pending cancellation and retry cannot release an active writer`(): Unit = runBlocking {
        configured()
        val running = request { runs.start(run.key, run.version) }
        val waiting = request {
            runs.prepare(inventory.copy(id = "waiting"), "destination-1").also {
                runs.configureStorage(it.key, "destination-1", mapOf("content" to "source-1"))
            }
        }
        assertError(cancelPending(version = run.version), "observed pending")
        assertError(cancelPending(version = running.version), "observed pending")
        assertError(retry(version = running.version), "state, version")
        assertEquals(running, request { runs.get(run.key) })
        assertFailsWith<ObjectBackupDestinationBusyException> {
            request { runs.start(waiting.key, waiting.version) }
        }
        assertEquals(waiting, request { runs.get(waiting.key) })
        assertTrue(queuedKeys().isEmpty())
    }

    @Test
    fun `recovery controls reject invalid input stale observations and terminal cancellation`(): Unit = runBlocking {
        assertError(cancelPending(reason = "  "), "detail")
        assertError(cancelPending(version = -1), "version")
        assertError(retry(version = -1), "version")
        assertError(cancelPending(key = "missing"), "missing")
        assertError(retry(key = "missing"), "state, version")
        assertError(cancelPending(key = ""), "key")
        assertError(retry(key = ""), "key")
        assertError(cancelPending(version = run.version + 1), "observed pending")
        assertError(retry(), "state, version")
        assertEquals(run, request { runs.get(run.key) })
        data(cancelPending())
        val cancelled = assertNotNull(request { runs.get(run.key) })
        assertError(cancelPending(version = cancelled.version), "observed pending")
        assertError(retry(version = run.version), "state, version")
        assertEquals(cancelled, request { runs.get(run.key) })
        val completed = request {
            val empty = runs.prepare(inventory.copy(id = "empty", objects = emptyList()), "destination-1")
            val running = runs.start(empty.key, empty.version)
            runs.complete(empty.key, running.version)
        }
        assertError(cancelPending(completed.key, completed.version), "observed pending")
        assertError(retry(completed.key, completed.version), "state, version")
        assertEquals(completed, request { runs.get(completed.key) })
        assertTrue(queuedKeys().isEmpty())
    }

    @Test
    fun `anonymous ordinary and restricted tokens cannot cancel pending runs or retry failed runs`(): Unit = runBlocking {
        val failed = request {
            val pending = runs.prepare(inventory.copy(id = "failed"), "destination-1")
            val running = runs.start(pending.key, pending.version)
            runs.fail(pending.key, running.version, "stopped test writer")
        }
        for (authentication in listOf(anonymous, ImpersonatedAuthenticationContext(Principal(), emptyList()),
            scoped(listOf("content:read")), scoped(listOf("security:manage"), emptySet()))) {
            assertError(cancelPending(authentication = authentication), "Unauthorized access")
            assertError(retry(failed.key, failed.version, authentication), "Unauthorized access")
        }
        assertEquals(run, request { runs.get(run.key) })
        assertEquals(failed, request { runs.get(failed.key) })
        assertTrue(queuedKeys().isEmpty())
    }

    @Test
    fun `management scoped and super administrators can cancel and retry pending restore intent`(): Unit = runBlocking {
        val sa = ImpersonatedAuthenticationContext(Principal(), listOf(
            Group(name = "sa", description = "", type = GroupType.SYSTEM),
        ))
        for ((index, authentication) in listOf(scoped(listOf("security:manage")), sa).withIndex()) {
            val restore = request { runs.prepareRestore(inventory, "restore-$index", "input/manifest.json") }
            data(cancelPending(restore.key, restore.version, authentication = authentication))
            val cancelled = assertNotNull(request { runs.get(restore.key) })
            val retried = data(retry(restore.key, cancelled.version, authentication)).getValue("retry").jsonObject
            assertEquals("PENDING", retried.string("status"))
            assertEquals(0, request { runs.get(restore.key) }?.attempts)
        }
        assertTrue(queuedKeys().isEmpty())
    }

    @Test
    fun `configured manifest preparation restores through explicit dispatch after source removal`(): Unit = runBlocking {
        val retained = complete(run)
        directory.resolve("source").toFile().deleteRecursively()
        val response = execute("""
            mutation PrepareFromRetained(${'$'}path: String!) {
                backups { objectRuns { prepareRestoreConfigured(
                    requestKey: "offline-recovery", destinationId: "recovered", backupKey: "destination-1",
                    path: ${'$'}path, stores: [{store: "content", storageKey: "target-1"}]
                ) { $controlFields } } }
            }
        """, variables = buildJsonObject { put("path", retained.path) })
        val prepared = data(response).getValue("prepareRestoreConfigured").jsonObject
        assertEquals("PENDING", prepared.string("status"))
        assertTrue(queuedKeys().isEmpty())
        assertFalse(directory.resolve("target").toFile().exists())

        data(dispatch(prepared.string("key"), prepared.getValue("version").jsonPrimitive.long))
        runner.run()
        val restored = awaitCompleted(prepared.string("key"))
        assertEquals(retained.inventory, restored.inventory)
        assertEquals("data", directory.resolve("target/items/a").toFile().readText())
        assertEquals("more", directory.resolve("target/items/b").toFile().readText())
        assertEquals(2L, restored.result?.completed)
        assertEquals(1, restored.attempts)
    }

    private suspend fun configured() {
        data(bind(listOf("source-1", "destination-1")))
        data(configure())
    }

    private suspend fun bind(keys: List<String>, authentication: AuthenticationContext = admin): JsonObject = execute("""
        mutation Bind(${'$'}keys: [String!]!) {
            backups { objectRuns { bindStorage(keys: ${'$'}keys) { $storageFields } } }
        }
    """, authentication, buildJsonObject { put("keys", JsonArray(keys.map(::JsonPrimitive))) })

    private suspend fun configure(
        key: String = run.key,
        backupKey: String = "destination-1",
        mappings: List<JsonElement> = listOf(mapping("content", "source-1")),
        authentication: AuthenticationContext = admin,
    ): JsonObject = execute("""
        mutation Configure(${'$'}key: String!, ${'$'}backupKey: String!, ${'$'}stores: [ObjectBackupStoreMappingInput!]!) {
            backups { objectRuns { configureStorage(key: ${'$'}key, backupKey: ${'$'}backupKey, stores: ${'$'}stores) {
                $mappingFields
            } } }
        }
    """, authentication, buildJsonObject {
        put("key", key)
        put("backupKey", backupKey)
        put("stores", JsonArray(mappings))
    })

    private suspend fun dispatch(
        key: String = run.key,
        version: Long = run.version,
        authentication: AuthenticationContext = admin,
    ): JsonObject = execute("""
        mutation Dispatch(${'$'}key: String!, ${'$'}version: Long!) {
            backups { objectRuns { dispatch(key: ${'$'}key, expectedVersion: ${'$'}version) { $dispatchFields } } }
        }
    """, authentication, buildJsonObject {
        put("key", key)
        put("version", version)
    })

    private suspend fun readStorage(key: String, authentication: AuthenticationContext = admin): JsonObject = execute("""
        query Storage(${'$'}key: String!) { backups { objectRuns { storage(key: ${'$'}key) { $storageFields } } } }
    """, authentication, buildJsonObject { put("key", key) })

    private suspend fun readDispatch(
        key: String,
        version: Long,
        authentication: AuthenticationContext = admin,
    ): JsonObject = execute("""
        query Dispatch(${'$'}key: String!, ${'$'}version: Long!) {
            backups { objectRuns { dispatch(key: ${'$'}key, version: ${'$'}version) { $dispatchFields } } }
        }
    """, authentication, buildJsonObject {
        put("key", key)
        put("version", version)
    })

    private suspend fun cancelPending(
        key: String = run.key,
        version: Long = run.version,
        reason: String = "Operator cancelled",
        authentication: AuthenticationContext = admin,
    ): JsonObject = execute("""
        mutation Cancel(${'$'}key: String!, ${'$'}version: Long!, ${'$'}reason: String!) {
            backups { objectRuns { cancelPending(key: ${'$'}key, expectedVersion: ${'$'}version, reason: ${'$'}reason) {
                $controlFields
            } } }
        }
    """, authentication, buildJsonObject {
        put("key", key)
        put("version", version)
        put("reason", reason)
    })

    private suspend fun retry(
        key: String = run.key,
        version: Long = run.version,
        authentication: AuthenticationContext = admin,
    ): JsonObject = execute("""
        mutation Retry(${'$'}key: String!, ${'$'}version: Long!) {
            backups { objectRuns { retry(key: ${'$'}key, expectedVersion: ${'$'}version) { $controlFields } } }
        }
    """, authentication, buildJsonObject {
        put("key", key)
        put("version", version)
    })

    private fun mapping(store: String, storageKey: String) = JsonObject(mapOf(
        "store" to JsonPrimitive(store), "storageKey" to JsonPrimitive(storageKey),
    ))

    private fun assertError(response: JsonObject, detail: String) {
        val errors = assertNotNull(response["errors"], response.toString()).jsonArray
        assertTrue(errors.any { it.jsonObject.string("message").contains(detail) }, response.toString())
    }

    private suspend fun state(id: UUID): String? =
        natsPool.systemConnection().keyValue("job-state-" + queue.name).get(id.toString())?.valueAsString

    private suspend fun queuedKeys(): List<String> =
        natsPool.systemConnection().keyValue("job-state-" + queue.name).keys().orEmpty().sorted()

    private suspend fun awaitCompleted(key: String): ObjectBackupRun = awaitStatus(key, ObjectBackupRunStatus.COMPLETED)

    private suspend fun awaitStatus(key: String, status: ObjectBackupRunStatus): ObjectBackupRun {
        withTimeout(30_000) {
            while (request { runs.get(key) }?.status != status) delay(25)
        }
        return assertNotNull(request { runs.get(key) })
    }

    private suspend fun awaitDrained(id: UUID) {
        withTimeout(30_000) {
            while (state(id) != null) delay(25)
        }
    }

    companion object {
        private const val storageFields = "key type endpoint bucket region basePath created"
        private const val mappingFields = "key backupKey created stores { store storageKey }"
        private const val dispatchFields = "key runKey version created"
        private const val controlFields = "key status version attempts error result { total pending completed failed }"
    }
}
