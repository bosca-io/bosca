package bosca.backup.pipeline

import bosca.backup.ObjectBackupCaptureInventory
import bosca.backup.ObjectBackupCapturePlan
import bosca.backup.ObjectBackupCaptureService
import bosca.backup.ObjectBackupCaptureRepository
import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupProgressService
import bosca.backup.ObjectBackupOperation
import bosca.backup.objectBackupIdentity
import bosca.backup.ObjectBackupStorageService
import bosca.backup.ObjectBackupRunService
import bosca.backup.ObjectBackupRunStatus
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.CorePipelinesProviderRegistrar
import bosca.di.PipelinesProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.StorageProviderRegistrar
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.pipelines.builtin.ToStringNode
import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.model.NodeExecutionStatus
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineResumeCorrelation
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.repository.PipelineRecord
import bosca.pipelines.repository.PipelineRepository
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.pipelines.trigger.ExecuteNodeInJob
import bosca.pubsub.PubSubService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.sharedqueue.jobs.serialize
import bosca.sharedqueue.jobs.configuration.RegisterJobsConfiguration
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Path
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration

/** Real stored Pipelines, PostgreSQL progress, NATS child jobs and signed version-specific S3 reads. */
@OptIn(InternalDI::class)
class ObjectBackupCapturePipelineIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var nats: SharedNatsContainer
    private lateinit var queue: JobQueue
    private lateinit var locks: NatsDistributedLockFactory
    private var runner: JobRunner? = null
    private lateinit var server: HttpServer
    private lateinit var directory: Path
    private lateinit var application: BoscaApplication
    private lateinit var staging: ManagedObjectStorageService
    private lateinit var json: Json
    private lateinit var captures: ObjectBackupCaptureService
    private lateinit var runs: PipelineRunService
    private lateinit var pipelines: PipelineService
    private lateinit var plan: ObjectBackupCapturePlan
    private lateinit var pipeline: Pipeline
    private val security = mockk<SecurityService>()
    private val caller = Principal(id = UUID.random())
    private val serviceAccount = Principal(id = UUID.random())
    private val administrator = Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
    private val requests = CopyOnWriteArrayList<String>()
    private val signatures = CopyOnWriteArrayList<String>()
    private val discoveryRequests = CopyOnWriteArrayList<String>()
    private var currentVersion = "v1"
    private var unversioned = false
    private val restoreWrites = CopyOnWriteArrayList<String>()
    private var failedRestoreKey: String? = null
    private var stoppedRestoreKey: String? = null
    private var denied = false
    private var failedKey: String? = null
    private val selection = ObjectBackupCaptureInventory(
        "selected", "source-instance", "content", "2026-10-18T00:00:00Z",
        mapOf("content" to listOf("a", "b").map { ObjectStorageEntry(it, ObjectStorageInfo(4, version = "v1")) }),
    )

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("capture_pipeline").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 4),
            key = "capture-pipeline-test",
        ))
        nats = SharedNatsContainer().apply { start() }
        directory = createTempDirectory("bosca-capture-pipeline-")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                assertEquals(0, pool.activeConnections)
                val query = exchange.requestURI.rawQuery.orEmpty().split('&').associate {
                    URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                        URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                }
                val key = exchange.requestURI.path.substringAfterLast('/')
                if (query["list-type"] == "2") {
                    discoveryRequests.add("LIST")
                    val xml = "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                        listOf("a", "b").joinToString("") { "<Contents><Key>$it</Key><Size>4</Size></Contents>" } +
                        "</ListBucketResult>"
                    val bytes = xml.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                    return@use
                }
                if (exchange.requestMethod == "HEAD") {
                    discoveryRequests.add("HEAD $key")
                    if (!unversioned) exchange.responseHeaders.add("x-amz-version-id", if (key == "b") currentVersion else "v1")
                    exchange.responseHeaders.add("ETag", "\"opaque-$key-${if (key == "b") currentVersion else "v1"}\"")
                    exchange.responseHeaders.add("Content-Length", "4")
                    exchange.sendResponseHeaders(200, -1)
                    return@use
                }
                val selectedVersion = query["versionId"]
                val version = if (key == "b") currentVersion else "v1"
                requests.add("$key@${if (unversioned) null else version}")
                signatures.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
                val success = key != failedKey && version in setOf("v1", "v2") && key in setOf("a", "b") &&
                    selectedVersion == null
                val bytes = if (success) (if (version == "v2") "next" else if (key == "a") "data" else "more").toByteArray()
                    else "<Error><Code>AccessDenied</Code></Error>".toByteArray()
                if (success && !unversioned) exchange.responseHeaders.add("x-amz-version-id", version)
                exchange.sendResponseHeaders(if (success) 200 else 403, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        val configurations = mapOf(
            "source" to StorageConfiguration("s3", "", bucket = "content", region = "us-east-1", forcePathStyle = true,
                endpoint = "http://127.0.0.1:${server.address.port}", accessKeyId = "PIPELINE_CAPTURE_KEY", accessKeySecret = "test-secret"),
            "retained" to StorageConfiguration("fs", "", basePath = directory.resolve("retained").toString()),
        )
        val config = buildJsonObject {
            put("backup", buildJsonObject {
                put("objectStores", JsonObject(configurations.mapValues { (_, value) ->
                    Json.encodeToJsonElement(StorageConfiguration.serializer(), value)
                }))
            })
            put("pipelines", buildJsonObject { put("onDemandRunMaxBlockMillis", 0) })
        }
        application = BoscaApplication(ApplicationConfig.load(Json.encodeToString(JsonObject.serializer(), config).byteInputStream()),
            ownsServices = false)
        json = application.json
        coEvery { security.getPrincipalById(caller.id) } returns caller
        coEvery { security.getPrincipalByIdentifier("sa") } returns serviceAccount
        coEvery { security.getPrincipalGroups(any<UUID>()) } answers {
            if (firstArg<UUID>() == caller.id && denied) emptyList() else listOf(administrator)
        }
        val natsPool = nats.newConnectionPool(1)
        locks = NatsDistributedLockFactory(natsPool)
        queue = NatsJobQueueFactory(natsPool, json, locks, null, emptyList(),
            processExpiredJobs = false).create("capture-pipeline-${UUID.random()}")
        ProviderRegistry.clear()
        StorageProviderRegistrar().register()
        staging = provide<ObjectStorageServiceFactory>().create(
            StorageConfiguration("fs", "", basePath = directory.resolve("pipeline-results").toString()),
        )
        install()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use {
                it.execute("create table groups (id uuid primary key)")
                it.execute("create type permission_action as enum ('view', 'edit', 'manage', 'delete', 'execute')")
            }
        }
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations"),
            provide<Migration>(name = "pipelines-migrations")))
        plan = withConnectionManager {
            provide<ObjectBackupStorageService>().bindConfigured(configurations.keys)
            captures.prepareInventory(selection, "retained", "payloads", "manifests/selected.json").also {
                captures.configureStorage(it.key, mapOf("content" to "source"))
            }
        }
        val declared = Pipeline(id = UUID.NIL, name = "capture", acceptedInputType = "JSON", nodes = listOf(
            InputNode("in", acceptedType = "JSON"), ToStringNode("key"), ObjectBackupCaptureNode("capture"), OutputNode("out"),
        ), edges = listOf(
            PipelineEdge("e1", source = "in", target = "key"),
            PipelineEdge("e2", source = "key", target = "capture", targetPort = "planKey"),
            PipelineEdge("e3", source = "capture", target = "out"),
        ))
        pipeline = withConnectionManager {
            val saved = provide<PipelineRepository>().add(PipelineRecord(name = declared.name,
                acceptedInputType = declared.acceptedInputType, graph = pipelines.graphAsJsonElement(declared)))
            assertNotNull(pipelines.get(saved.id))
        }
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            runner?.shutdown()
            if (::staging.isInitialized) staging.close()
            if (::application.isInitialized) application.shutdown()
            if (::server.isInitialized) server.stop(0)
            if (::nats.isInitialized) nats.stop()
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `manual capture parks under its caller and resumes with the published manifest`(): Unit = runBlocking {
        val initial = start()
        assertEquals(caller.id, initial.principalId)
        assertTrue(requests.isEmpty())
        val parent = executeNext()
        val parked = get(initial.id)
        assertEquals(PipelineRunStatus.SUSPENDED, parked.status)
        assertTrue(requests.isEmpty())
        val child = assertNotNull(queue.dequeue(5.seconds))
        assertEquals(parent.getId(), child.getParentId())
        assertEquals(initial.id, json.decodeFromJsonElement(PipelineResumeCorrelation.serializer(), child.getContext()).runId)
        val definition = json.decodeFromJsonElement(ExecuteNodeInJob.serializer(), child.getDefinition())
        assertEquals(JsonPrimitive(plan.key), definition.inputs["planKey"])
        assertFalse(child.getDefinition().toString().contains("test-secret"))
        execute(child)
        val complete = get(initial.id)
        assertEquals(PipelineRunStatus.OK, complete.status)
        val manifest = json.decodeFromJsonElement(ObjectBackupManifest.serializer(), assertNotNull(complete.output))
        assertEquals(2, manifest.objects.size)
        assertEquals(load(), manifest)
        assertEquals(listOf("a@v1", "b@v1"), requests.toList())
        assertTrue(signatures.all { "Credential=PIPELINE_CAPTURE_KEY/" in it })
        assertNull(withConnectionManager { captures.getWriter(plan.key) })
        coVerify(exactly = 0) { security.getPrincipalByIdentifier(any()) }
        assertEquals(NodeExecutionStatus.OK, withConnectionManager { runs.nodeTimeline(initial.id) }
            .last { it.nodeId == "capture" }.status)
    }

    @Test
    fun `queue retry survives service restart and reads only unfinished source revisions`(): Unit = runBlocking {
        failedKey = "b"
        val initial = start()
        executeNext()
        val failed = failNext(retry = true)
        assertEquals(PipelineRunStatus.SUSPENDED, get(initial.id).status)
        assertNull(get(initial.id).output)
        assertFalse(directory.resolve("retained").resolve(plan.path).exists())
        assertEquals(listOf("a@v1", "b@v1"), requests.toList())
        assertEquals(1, withConnectionManager {
            provide<ObjectBackupProgressService>().list(ObjectBackupOperation.BACKUP, selection.sourceId, "retained", selection.scope, 0, 20)
        }.size)
        assertNull(withConnectionManager { captures.getWriter(plan.key) })
        failedKey = null
        requests.clear()
        install()
        val retry = assertNotNull(queue.dequeue(40.seconds))
        assertEquals(failed.first.getId(), retry.getId())
        execute(retry)
        assertEquals(PipelineRunStatus.OK, get(initial.id).status)
        assertEquals(listOf("b@v1"), requests.toList())
        assertEquals(load(), json.decodeFromJsonElement(ObjectBackupManifest.serializer(), assertNotNull(get(initial.id).output)))
        assertEquals(1, withConnectionManager { runs.nodeTimeline(initial.id) }.count {
            it.nodeId == "capture" && it.status == NodeExecutionStatus.FAILED
        })
        coVerify(exactly = 0) { security.getPrincipalByIdentifier(any()) }
    }

    @Test
    fun `redelivered capture job resumes its own claim and completed object checkpoint`(): Unit = runBlocking {
        val initial = start()
        executeNext()
        val original = assertNotNull(queue.dequeue(5.seconds))
        val checkpoint = objectBackupIdentity(ObjectBackupOperation.BACKUP, listOf(original.getId().toString(), "capture"))
        val addresses = provide<ObjectBackupStorageService>()
        val configurations = addresses.configurations(setOf("source", "retained"))
        val completed = addresses.withStorages(configurations) { opened ->
            captures.captureTracked(
                selection.sourceId, "retained", selection.scope, "content", selection.stores.getValue("content").first(),
                opened.getValue("source"), opened.getValue("retained"), "payloads/completed-a", OffsetDateTime.MIN,
            )
        }
        withConnectionManager {
            assertEquals(1, provide<ObjectBackupCaptureRepository>().claimWriter(plan.key, "retained", "$checkpoint:stopped"))
        }
        queue.enqueueLater(original, 1.seconds)
        assertTrue(locks.forceRelease("job-${original.getId()}"))
        install()
        requests.clear()
        val redelivered = withTimeout(10_000) {
            var next = queue.dequeue(1.seconds)
            while (next == null) {
                delay(50)
                next = queue.dequeue(1.seconds)
            }
            next
        }
        assertEquals(original.getId(), redelivered.getId())
        assertEquals(original.getContext(), redelivered.getContext())
        execute(redelivered)
        assertEquals(0, redelivered.serialize().failures)
        assertEquals(listOf("b@v1"), requests.toList())
        val result = get(initial.id)
        assertEquals(PipelineRunStatus.OK, result.status)
        val manifest = json.decodeFromJsonElement(ObjectBackupManifest.serializer(), assertNotNull(result.output))
        assertEquals(completed.payload, manifest.objects.single { it.key == "a" })
        assertEquals(load(), manifest)
        assertNull(withConnectionManager { captures.getWriter(plan.key) })
        assertTrue(withConnectionManager { runs.nodeTimeline(initial.id) }.none {
            it.nodeId == "capture" && it.status == NodeExecutionStatus.FAILED
        })
    }

    @Test
    fun `real runner defers a busy destination without consuming failures then completes the same child`(): Unit = runBlocking {
        withConnectionManager {
            assertEquals(1, provide<ObjectBackupCaptureRepository>().claimWriter(plan.key, "retained", "active-writer"))
        }
        val owner = assertNotNull(withConnectionManager { captures.getWriter(plan.key) })
        val initial = start()
        executeNext()
        val delayed = CompletableDeferred<UUID>()
        val completed = CompletableDeferred<Unit>()
        val delayCount = AtomicInteger()
        val childIds = CopyOnWriteArrayList<UUID>()
        val observed = object : JobQueue by queue {
            override suspend fun markComplete(job: Job) {
                queue.markComplete(job)
                if (get(initial.id).status == PipelineRunStatus.OK) completed.complete(Unit)
            }

            override suspend fun enqueueLater(job: Job, timeout: Duration): UUID {
                assertEquals(5.seconds, timeout)
                assertEquals(0, job.serialize().failures)
                childIds.add(job.getId())
                val id = queue.enqueueLater(job, timeout)
                if (delayCount.incrementAndGet() >= 2) delayed.complete(id)
                return id
            }
        }
        runner = JobRunner(observed, 1, locks).also { it.run() }
        val child = withTimeout(20_000) { delayed.await() }
        assertEquals(setOf(child), childIds.toSet())
        assertEquals(PipelineRunStatus.SUSPENDED, get(initial.id).status)
        assertTrue(withConnectionManager { runs.nodeTimeline(initial.id) }.none { it.status == NodeExecutionStatus.FAILED })
        assertEquals(owner, withConnectionManager { captures.getWriter(plan.key) })
        assertTrue(requests.isEmpty())
        assertFalse(directory.resolve("retained").exists())
        withConnectionManager { captures.releaseWriter(plan.key, owner.key) }
        withTimeout(20_000) { completed.await() }
        assertEquals(PipelineRunStatus.OK, get(initial.id).status)
        assertEquals(listOf("a@v1", "b@v1"), requests.toList())
        assertEquals(load(), json.decodeFromJsonElement(ObjectBackupManifest.serializer(), assertNotNull(get(initial.id).output)))
        assertNull(withConnectionManager { captures.getWriter(plan.key) })
        assertTrue(withConnectionManager { runs.nodeTimeline(initial.id) }.none { it.status == NodeExecutionStatus.FAILED })
    }

    @Test
    fun `terminal backing failure fails the pipeline without publishing partial output`(): Unit = runBlocking {
        failedKey = "b"
        val initial = start()
        executeNext()
        val backingFailure = failNext(retry = false).second
        val failed = get(initial.id)
        assertEquals(PipelineRunStatus.FAILED, failed.status)
        assertNull(failed.output)
        assertEquals(backingFailure.message, failed.error)
        assertFalse(directory.resolve("retained").resolve(plan.path).exists())
        assertNull(withConnectionManager { captures.getWriter(plan.key) })
    }

    @Test
    fun `backing execution verifies the initiating principal without service-account fallback`(): Unit = runBlocking {
        denied = true
        val initial = start()
        executeNext()
        assertEquals(PipelineRunStatus.SUSPENDED, get(initial.id).status)
        failNext(retry = false)
        val failed = get(initial.id)
        assertEquals(PipelineRunStatus.FAILED, failed.status)
        assertTrue(assertNotNull(failed.error).contains("Unauthorized access"))
        assertTrue(requests.isEmpty())
        assertFalse(directory.resolve("retained").exists())
        coVerify(exactly = 0) { security.getPrincipalByIdentifier(any()) }
    }

    @Test
    fun `discovery action creates fresh inventories and unchanged later runs avoid source payloads`(): Unit = runBlocking {
        useDiscoveryPipeline()
        suspend fun runBackup(): ObjectBackupManifest {
            val initial = start()
            executeNext()
            assertEquals(PipelineRunStatus.SUSPENDED, get(initial.id).status)
            executeNext()
            val complete = get(initial.id)
            assertEquals(PipelineRunStatus.OK, complete.status)
            return json.decodeFromJsonElement(ObjectBackupManifest.serializer(), assertNotNull(complete.output))
        }
        val first = runBackup()
        assertEquals(listOf("a@v1", "b@v1"), requests.toList())
        assertTrue(discoveryRequests.containsAll(listOf("LIST", "HEAD a", "HEAD b")))
        requests.clear()
        discoveryRequests.clear()
        val unchanged = runBackup()
        assertTrue(requests.isEmpty())
        assertEquals(first.objects, unchanged.objects)
        assertTrue(first.id != unchanged.id)
        currentVersion = "v2"
        requests.clear()
        val later = runBackup()
        assertEquals(listOf("b@v2"), requests.toList())
        assertEquals(first.objects.first(), later.objects.first())
        for (manifest in listOf(first, unchanged, later)) {
            assertEquals(manifest, json.decodeFromString(ObjectBackupManifest.serializer(),
                directory.resolve("retained/manifests/${manifest.id}.json").toFile().readText()))
        }
        coVerify(exactly = 0) { security.getPrincipalByIdentifier(any()) }
    }

    @Test
    fun `unversioned Pipeline copy retries unfinished content and restores after the source is offline`(): Unit = runBlocking {
        unversioned = true
        useDiscoveryPipeline()
        failedKey = "b"
        val initial = start()
        executeNext()
        val failed = failNext(retry = true)
        assertEquals(listOf("a@null", "b@null"), requests.toList(), failed.second.stackTraceToString())
        assertNull(get(initial.id).output)
        failedKey = null
        requests.clear()
        install()
        val retry = assertNotNull(queue.dequeue(40.seconds))
        assertEquals(failed.first.getId(), retry.getId())
        execute(retry)
        assertEquals(PipelineRunStatus.OK, get(initial.id).status)
        assertEquals(listOf("b@null"), requests.toList())
        val first = json.decodeFromJsonElement(ObjectBackupManifest.serializer(), assertNotNull(get(initial.id).output))

        suspend fun runBackup(): ObjectBackupManifest {
            val run = start()
            executeNext()
            executeNext()
            assertEquals(PipelineRunStatus.OK, get(run.id).status)
            return json.decodeFromJsonElement(ObjectBackupManifest.serializer(), assertNotNull(get(run.id).output))
        }
        requests.clear()
        assertEquals(first.objects, runBackup().objects)
        assertTrue(requests.isEmpty())
        currentVersion = "v2"
        val later = runBackup()
        assertEquals(listOf("b@null"), requests.toList())
        assertEquals(first.objects.first(), later.objects.first())
        assertTrue(first.objects.last().revision != later.objects.last().revision)
        assertTrue(signatures.all { "Credential=PIPELINE_CAPTURE_KEY/" in it })
        useRestorePipeline(later)
        val restore = start("manifests/${later.id}.json")
        executeNext()
        executeNext()
        assertEquals(PipelineRunStatus.OK, get(restore.id).status)
        assertEquals("data", directory.resolve("restored/a").toFile().readText())
        assertEquals("next", directory.resolve("restored/b").toFile().readText())
        assertTrue(requests.isEmpty())
        assertTrue(discoveryRequests.isEmpty())
    }

    @Test
    fun `discovery action retry rejects changed unfinished content after restart`(): Unit = runBlocking {
        useDiscoveryPipeline()
        failedKey = "b"
        val initial = start()
        executeNext()
        val failure = failNext(retry = true)
        val discovered = withConnectionManager { captures.listPlans(selection.sourceId, "retained", selection.scope, 0, 20) }
            .single { it.inventory.id != "selected" }
        assertFalse(directory.resolve("retained").resolve(discovered.path).exists())
        assertNull(withConnectionManager { captures.getWriter(discovered.key) })
        failedKey = null
        currentVersion = "v2"
        requests.clear()
        discoveryRequests.clear()
        install()
        val retry = assertNotNull(queue.dequeue(40.seconds))
        assertEquals(failure.first.getId(), retry.getId())
        val changed = assertFailsWith<bosca.backup.BackupPayloadMismatchException> { execute(retry) }
        withConnectionManager {
            withContext(queue.asCoroutineContext(retry)) { queue.markFailed(retry, changed, false) }
        }
        assertNull(get(initial.id).output)
        assertTrue(discoveryRequests.none { it == "LIST" })
        assertTrue(requests.isEmpty())
        assertFalse(directory.resolve("retained").resolve(discovered.path).exists())
    }

    @Test
    fun `discovery action denied caller performs no inventory or payload requests`(): Unit = runBlocking {
        useDiscoveryPipeline()
        denied = true
        val initial = start()
        executeNext()
        failNext(retry = false)
        assertEquals(PipelineRunStatus.FAILED, get(initial.id).status)
        assertTrue(requests.isEmpty())
        assertTrue(discoveryRequests.isEmpty())
        assertFalse(directory.resolve("retained").exists())
    }

    @Test
    fun `restore action recovers captured assets with the source server stopped and catalog removed`(): Unit = runBlocking {
        val captured = useRestorePipeline()
        val initial = start(plan.path)
        executeNext()
        assertEquals(PipelineRunStatus.SUSPENDED, get(initial.id).status)
        executeNext()
        val complete = get(initial.id)
        assertEquals(PipelineRunStatus.OK, complete.status)
        assertEquals(captured, json.decodeFromJsonElement(ObjectBackupManifest.serializer(), assertNotNull(complete.output)))
        assertEquals("data", directory.resolve("restored/a").toFile().readText())
        assertEquals("more", directory.resolve("restored/b").toFile().readText())
        assertEquals(listOf("a", "b"), restoreWrites.toList())
        assertTrue(requests.isEmpty())
        assertTrue(discoveryRequests.isEmpty())
        assertEquals(ObjectBackupRunStatus.COMPLETED, restoreRun().status)
        coVerify(exactly = 0) { security.getPrincipalByIdentifier(any()) }
    }

    @Test
    fun `a later restore action run verifies targets and repairs a missing completed object`(): Unit = runBlocking {
        useRestorePipeline()
        suspend fun completeRestore() {
            val initial = start(plan.path)
            executeNext()
            executeNext()
            assertEquals(PipelineRunStatus.OK, get(initial.id).status)
        }
        completeRestore()
        directory.resolve("restored/b").toFile().delete()
        restoreWrites.clear()
        completeRestore()
        assertEquals(listOf("b"), restoreWrites.toList())
        assertEquals("more", directory.resolve("restored/b").toFile().readText())
        assertEquals(2, withConnectionManager {
            provide<ObjectBackupRunService>().list(ObjectBackupOperation.RESTORE, selection.sourceId, "recovery-target", selection.scope, 0, 20).size
        })
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `restore action worker retry reuses prepared input and writes only the unfinished object`(): Unit = runBlocking {
        useRestorePipeline()
        failedRestoreKey = "b"
        val initial = start(plan.path)
        executeNext()
        val failed = failNext(retry = true)
        assertEquals(PipelineRunStatus.SUSPENDED, get(initial.id).status)
        assertNull(get(initial.id).output)
        val partial = restoreRun()
        assertEquals(ObjectBackupRunStatus.PARTIAL, partial.status)
        assertEquals(listOf("a", "b"), restoreWrites.toList())
        restoreWrites.clear()
        failedRestoreKey = null
        install()
        val retry = assertNotNull(queue.dequeue(40.seconds))
        assertEquals(failed.first.getId(), retry.getId())
        execute(retry)
        assertEquals(PipelineRunStatus.OK, get(initial.id).status)
        assertEquals(listOf("b"), restoreWrites.toList())
        assertEquals(partial.key, restoreRun().key)
        assertEquals(2, restoreRun().attempts)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `redelivered restore action resumes running inventory and saved object checkpoints`(): Unit = runBlocking {
        useRestorePipeline()
        stoppedRestoreKey = "b"
        val initial = start(plan.path)
        executeNext()
        val original = assertNotNull(queue.dequeue(5.seconds))
        assertFailsWith<AssertionError> { execute(original) }
        val stopped = restoreRun()
        assertEquals(ObjectBackupRunStatus.RUNNING, stopped.status)
        assertEquals(PipelineRunStatus.SUSPENDED, get(initial.id).status)
        assertNull(get(initial.id).output)
        assertEquals(listOf("a", "b"), restoreWrites.toList())
        queue.enqueueLater(original, 1.seconds)
        assertTrue(locks.forceRelease("job-${original.getId()}"))
        stoppedRestoreKey = null
        restoreWrites.clear()
        install()
        val redelivered = withTimeout(10_000) {
            var next = queue.dequeue(1.seconds)
            while (next == null) {
                delay(50)
                next = queue.dequeue(1.seconds)
            }
            next
        }
        assertEquals(original.getId(), redelivered.getId())
        execute(redelivered)
        assertEquals(PipelineRunStatus.OK, get(initial.id).status)
        assertEquals(ObjectBackupRunStatus.COMPLETED, restoreRun().status)
        assertEquals(stopped.key, restoreRun().key)
        assertEquals(2, restoreRun().attempts)
        assertEquals(listOf("b"), restoreWrites.toList())
        assertEquals(0, redelivered.serialize().failures)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `restore action corrupt retained payload fails before target writes and emits no output`(): Unit = runBlocking {
        val captured = useRestorePipeline()
        directory.resolve("retained").resolve(captured.objects.last().payloadPath).toFile().writeText("bad")
        val initial = start(plan.path)
        executeNext()
        failNext(retry = false)
        val failed = get(initial.id)
        assertEquals(PipelineRunStatus.FAILED, failed.status)
        assertNull(failed.output)
        assertTrue(restoreWrites.isEmpty())
        assertEquals(ObjectBackupRunStatus.FAILED, restoreRun().status)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `restore action denied caller cannot prepare or write recovery targets`(): Unit = runBlocking {
        useRestorePipeline()
        denied = true
        val initial = start(plan.path)
        executeNext()
        failNext(retry = false)
        val failed = get(initial.id)
        assertEquals(PipelineRunStatus.FAILED, failed.status)
        assertTrue(assertNotNull(failed.error).contains("Unauthorized access"))
        assertTrue(restoreWrites.isEmpty())
        assertFalse(directory.resolve("restored").exists())
        assertTrue(withConnectionManager {
            provide<ObjectBackupRunService>().list(ObjectBackupOperation.RESTORE, selection.sourceId, "recovery-target", selection.scope, 0, 20)
        }.isEmpty())
    }

    private suspend fun restoreRun() = withConnectionManager {
        provide<ObjectBackupRunService>().list(ObjectBackupOperation.RESTORE, selection.sourceId, "recovery-target", selection.scope, 0, 20).single()
    }

    private suspend fun useRestorePipeline(previous: ObjectBackupManifest? = null): ObjectBackupManifest {
        val captured = previous ?: captures.captureConfigured(plan.key, null)
        server.stop(0)
        val configurations = mapOf(
            "retained" to StorageConfiguration("fs", "", basePath = directory.resolve("retained").toString()),
            "restored" to StorageConfiguration("fs", "", basePath = directory.resolve("restored").toString()),
        )
        val root = buildJsonObject {
            put("backup", buildJsonObject {
                put("objectStores", JsonObject(configurations.mapValues { (_, value) ->
                    Json.encodeToJsonElement(StorageConfiguration.serializer(), value)
                }))
            })
            put("pipelines", buildJsonObject { put("onDemandRunMaxBlockMillis", 0) })
        }
        application.shutdown()
        application = BoscaApplication(ApplicationConfig.load(root.toString().byteInputStream()), ownsServices = false)
        json = application.json
        install()
        val declared = Pipeline(id = UUID.NIL, name = "restore independent backup", acceptedInputType = "JSON", nodes = listOf(
            InputNode("in", acceptedType = "JSON"), ToStringNode("path"),
            ObjectBackupRestoreNode("restore", backupKey = "retained", destinationId = "recovery-target", targets = """{"content":"restored"}"""),
            OutputNode("out"),
        ), edges = listOf(
            PipelineEdge("input", source = "in", target = "path"),
            PipelineEdge("path", source = "path", target = "restore", targetPort = "manifestPath"),
            PipelineEdge("output", source = "restore", target = "out"),
        ))
        pipeline = withConnectionManager {
            val saved = provide<PipelineRepository>().add(PipelineRecord(name = declared.name,
                acceptedInputType = declared.acceptedInputType, graph = pipelines.graphAsJsonElement(declared)))
            assertNotNull(pipelines.get(saved.id))
        }
        requests.clear()
        discoveryRequests.clear()
        return captured
    }

    private suspend fun useDiscoveryPipeline() {
        val declared = Pipeline(id = UUID.NIL, name = "discover and back up", acceptedInputType = "JSON", nodes = listOf(
            InputNode("in", acceptedType = "JSON"), ObjectBackupNode("backup", sourceId = selection.sourceId, scope = selection.scope, destinationId = "retained",
                sources = """{"content":{"storageKey":"source"}}"""), OutputNode("out"),
        ), edges = listOf(PipelineEdge("result", source = "backup", target = "out")))
        pipeline = withConnectionManager {
            val saved = provide<PipelineRepository>().add(PipelineRecord(name = declared.name,
                acceptedInputType = declared.acceptedInputType, graph = pipelines.graphAsJsonElement(declared)))
            assertNotNull(pipelines.get(saved.id))
        }
    }

    private suspend fun install() {
        ProviderRegistry.clear()
        provides<BoscaApplication>(singleton = true) { application }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        provides<SecurityService>(singleton = true) { security }
        provides<GroupEvaluator>(singleton = true) { GroupEvaluator(security) }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<PubSubService> { mockk(relaxed = true) }
        CorePipelinesProviderRegistrar().register()
        PipelinesProviderRegistrar().register()
        StorageProviderRegistrar().register()
        val actualFactory = provide<ObjectStorageServiceFactory>()
        provides<ObjectStorageServiceFactory>(singleton = true, overrideExisting = true) {
            object : ObjectStorageServiceFactory by actualFactory {
                override suspend fun create(configuration: StorageConfiguration): ManagedObjectStorageService {
                    val actual = actualFactory.create(configuration)
                    if (configuration.basePath != directory.resolve("restored").toFile().canonicalPath) return actual
                    return object : ManagedObjectStorageService by actual {
                        override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                            assertEquals(0, pool.activeConnections)
                            restoreWrites.add(path.toString())
                            if (path.toString() == stoppedRestoreKey) throw AssertionError("restore worker stopped")
                            if (path.toString() == failedRestoreKey) {
                                actual.setInputStream(path, "x".byteInputStream(), 1)
                                throw IOException("restore write failed")
                            }
                            return actual.setInputStream(path, stream, length)
                        }
                    }
                }

                override suspend fun <T> withStorage(
                    configuration: StorageConfiguration,
                    block: suspend (ManagedObjectStorageService) -> T,
                ): T = create(configuration).use { block(it) }
            }
        }
        BackupProviderRegistrar().register()
        RegisterJobsConfiguration()
        provides<JobQueue>(name = PipelinesJobQueueNames.jobQueue, singleton = true, overrideExisting = true) { queue }
        provides<ObjectStorageService>(singleton = true, overrideExisting = true) { staging }
        captures = provide()
        runs = provide()
        pipelines = provide()
    }

    private suspend fun start(input: String = plan.key): PipelineRun = withConnectionManager {
        assertNotNull(runs.start(pipeline, PipelineValue.ofJson(JsonPrimitive(input)), "", OffsetDateTime.now(),
            ImpersonatedAuthenticationContext(caller, if (denied) emptyList() else listOf(administrator))))
    }

    private suspend fun executeNext(): Job = assertNotNull(queue.dequeue(5.seconds)).also { execute(it) }

    private suspend fun execute(job: Job) = withConnectionManager {
        withContext(queue.asCoroutineContext(job)) {
            job.newExecutor().execute()
            queue.markComplete(job)
        }
    }

    private suspend fun failNext(retry: Boolean): Pair<Job, Exception> {
        val job = assertNotNull(queue.dequeue(5.seconds))
        val failure = withConnectionManager {
            withContext(queue.asCoroutineContext(job)) {
                val failure = assertFailsWith<Exception> { job.newExecutor().execute() }
                queue.markFailed(job, failure, retry)
                failure
            }
        }
        return job to failure
    }

    private suspend fun get(id: UUID): PipelineRun = withConnectionManager { assertNotNull(runs.get(id)) }

    private fun load(): ObjectBackupManifest = json.decodeFromString(ObjectBackupManifest.serializer(),
        directory.resolve("retained").resolve(plan.path).toFile().readText())
}
