package bosca.backup

import bosca.backup.pipeline.PostgresBackupNode
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.withConnectionManager
import bosca.di.CorePipelinesProviderRegistrar
import bosca.di.PipelinesProviderRegistrar
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.repository.PipelineRecord
import bosca.pipelines.repository.PipelineRepository
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.pubsub.PubSubService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.sharedqueue.jobs.configuration.RegisterJobsConfiguration
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.test.resources.SharedNatsContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real Pipeline persistence and NATS dispatch around native backup and restore services. */
@OptIn(InternalDI::class)
internal class PostgresBackupPipelineFixture private constructor(
    private val nats: SharedNatsContainer,
    val queue: JobQueue,
    private val staging: ObjectStorageService,
    private val json: Json,
    private val locks: NatsDistributedLockFactory,
) : AutoCloseable {
    private val security = mockk<SecurityService>()
    val caller = Principal(id = UUID.random())
    private val administrator = Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
    var denied = false
    private lateinit var runs: PipelineRunService
    private lateinit var pipelines: PipelineService
    private lateinit var pipeline: Pipeline

    suspend fun register() {
        coEvery { security.getPrincipalById(caller.id) } returns caller
        coEvery { security.getPrincipalGroups(any<UUID>()) } answers {
            if (denied) emptyList() else listOf(administrator)
        }
        provides<SecurityService>(singleton = true, overrideExisting = true) { security }
        provides<GroupEvaluator>(singleton = true, overrideExisting = true) { GroupEvaluator(security) }
        provides<CacheManager>(overrideExisting = true) { mockk(relaxed = true) }
        provides<RequestCacheSerializer>(overrideExisting = true) { mockk(relaxed = true) }
        provides<PubSubService>(overrideExisting = true) { mockk(relaxed = true) }
        CorePipelinesProviderRegistrar().register()
        PipelinesProviderRegistrar().register()
        RegisterJobsConfiguration()
        provides<JobQueue>(name = PipelinesJobQueueNames.jobQueue, singleton = true, overrideExisting = true) { queue }
        provides<ObjectStorageService>(singleton = true, overrideExisting = true) { staging }
        runs = provide()
        pipelines = provide()
    }

    suspend fun start(input: PipelineValue = PipelineValue.ofJson(JsonPrimitive("snapshot"))): PipelineRun = withConnectionManager {
        assertNotNull(runs.start(pipeline, input, "", OffsetDateTime.now(),
            ImpersonatedAuthenticationContext(caller, listOf(administrator))))
    }

    suspend fun get(id: UUID): PipelineRun = withConnectionManager { assertNotNull(runs.get(id)) }

    suspend fun next(): Job = assertNotNull(queue.dequeue(5.seconds))

    suspend fun execute(job: Job) = withConnectionManager {
        withContext(queue.asCoroutineContext(job)) {
            job.newExecutor().execute()
            queue.markComplete(job)
        }
    }

    suspend fun fail(job: Job, retry: Boolean) = withConnectionManager {
        withContext(queue.asCoroutineContext(job)) {
            val failure = assertFailsWith<Exception> { job.newExecutor().execute() }
            queue.markFailed(job, failure, retry)
        }
    }

    suspend fun retry(): Job = assertNotNull(queue.dequeue(40.seconds))

    suspend fun redeliver(job: Job) {
        queue.enqueueLater(job, 1.seconds)
        assertTrue(locks.forceRelease("job-${job.getId()}"))
    }

    fun runner() = JobRunner(queue, 2, locks)

    suspend fun awaitComplete(id: UUID): PipelineRun = withTimeout(30_000) {
        var current = get(id)
        while (!current.status.isTerminal) {
            delay(25)
            current = get(id)
        }
        current
    }

    fun verifyNoServiceAccountFallback() {
        coVerify(exactly = 0) { security.getPrincipalByIdentifier(any()) }
    }

    override fun close() {
        try {
            staging.close()
        } finally {
            nats.stop()
        }
    }

    companion object {
        suspend fun create(
            application: BoscaApplication,
            pool: ConnectionPool,
            directory: Path,
            includeContentReferences: Boolean = false,
            contentSource: String = "",
            graph: Pipeline? = null,
        ): PostgresBackupPipelineFixture {
            val json = application.json
            val nats = SharedNatsContainer().apply { start() }
            var staging: ObjectStorageService? = null
            try {
                val natsPool = nats.newConnectionPool(1)
                val locks = NatsDistributedLockFactory(natsPool)
                val queue = NatsJobQueueFactory(natsPool, json, locks, null, emptyList(),
                    processExpiredJobs = false).create("postgres-backup-${UUID.random()}")
                staging = provide<ObjectStorageServiceFactory>().create(
                    StorageConfiguration("fs", "", basePath = directory.resolve("pipeline-results").toString()),
                )
                val fixture = PostgresBackupPipelineFixture(nats, queue, staging, json, locks)
                fixture.register()
                withConnectionManager {
                    connection().useStatement("create table if not exists groups (id uuid primary key)") { it.execute() }
                    val hasPermissionAction = connection().useStatement("select to_regtype('permission_action') is not null") {
                        it.executeQuery().use { rows ->
                            check(rows.next())
                            rows.getBoolean(1)
                        }
                    }
                    if (!hasPermissionAction) {
                        connection().useStatement("create type permission_action as enum ('view', 'edit', 'manage', 'delete', 'execute')") { it.execute() }
                    }
                }
                FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "pipelines-migrations")))
                val declared = graph ?: Pipeline(id = UUID.NIL, name = "native database backup", acceptedInputType = "JSON",
                    nodes = listOf(InputNode("in", acceptedType = "JSON"),
                        PostgresBackupNode("backup", sourceId = "instance", storageKey = "retained",
                            includeContentReferences = includeContentReferences, contentSource = contentSource), OutputNode("out")),
                    edges = listOf(PipelineEdge("result", source = "backup", target = "out")))
                fixture.pipeline = withConnectionManager {
                    val saved = provide<PipelineRepository>().add(PipelineRecord(name = declared.name,
                        acceptedInputType = declared.acceptedInputType, graph = fixture.pipelines.graphAsJsonElement(declared)))
                    assertNotNull(fixture.pipelines.get(saved.id))
                }
                return fixture
            } catch (failure: Throwable) {
                try {
                    staging?.close()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                try {
                    nats.stop()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }
    }
}
