package bosca.server.installer

import bosca.backup.BackupJobQueueNames
import bosca.backup.BackupObject
import bosca.backup.ObjectBackupDispatch
import bosca.backup.ObjectBackupDispatchRecoveryJob
import bosca.backup.ObjectBackupDispatchService
import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupRunService
import bosca.backup.ObjectBackupRunStatus
import bosca.backup.ObjectBackupStorageService
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.afterCommit
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.SchedulerProviderRegistrar
import bosca.di.StorageProviderRegistrar
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provideProvider
import bosca.di.provides
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationHistory
import bosca.installer.model.PackageInstallations
import bosca.installer.repository.PackageInstallationsRepository
import bosca.installer.repository.PackageInstallationsRepositoryProvider
import bosca.installer.service.PackageInstallationService
import bosca.installer.service.PackageInstallationServiceImpl
import bosca.installer.service.PackageInstallationServiceImplProvider
import bosca.installer.service.PackageInstaller
import bosca.lock.DistributedLockFactory
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.scheduler.listeners.JobEnqueueEventForwarder
import bosca.scheduler.model.ScheduleExecutionStatus
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.runner.SchedulerRunner
import bosca.scheduler.service.SchedulerService
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.configuration.Configuration
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.configuration.JobQueueNames
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEvent
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.mockk
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Real installation history, scheduler, NATS runners and independently configured object stores. */
@OptIn(InternalDI::class)
class ObjectBackupDispatchRecoveryIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var nats: SharedNatsContainer
    private lateinit var natsPool: NatsConnectionPool
    private lateinit var pool: ConnectionPool
    private lateinit var application: BoscaApplication
    private lateinit var directory: Path
    private lateinit var backupQueue: JobQueue
    private lateinit var backupRunner: JobRunner
    private lateinit var commonRunner: JobRunner
    private lateinit var schedulerRunner: SchedulerRunner
    private lateinit var forwarder: JobEnqueueEventForwarder
    private lateinit var scheduler: SchedulerService
    private lateinit var configurations: Map<String, StorageConfiguration>
    private lateinit var events: MutableSharedFlow<JobEnqueueEvent>
    private val installationLog = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(PackageInstallationServiceImpl::class.java) as Logger
    private var publicationFailure = false
    private val payload = "portable content"
    private val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).toHexString()
    private val entry = BackupObject("content", "images/a", "revision", payload.length.toLong(), digest, "objects/$digest")

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        ProviderRegistry.clear()
        postgres = SharedPostgreSQLContainer().withDatabaseName("backup_dispatch_recovery").apply { start() }
        nats = SharedNatsContainer().apply { start() }
        natsPool = nats.newConnectionPool(2)
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 5),
            key = "backup-dispatch-recovery-test",
        ))
        directory = createTempDirectory("bosca-dispatch-recovery-")
        configurations = listOf("source", "retained").associateWith {
            StorageConfiguration("fs", "", basePath = directory.resolve(it).toString())
        }
        val catalog = JsonObject(configurations.mapValues {
            Json.encodeToJsonElement(StorageConfiguration.serializer(), it.value)
        })
        val root = JsonObject(mapOf(
            "backup" to JsonObject(mapOf("objectStores" to catalog)),
            "packages" to JsonArray(listOf(JsonPrimitive("maintenance"))),
        ))
        application = BoscaApplication(
            ApplicationConfig.load(Json.encodeToString(JsonObject.serializer(), root).byteInputStream()),
            ownsServices = false,
        )
        provides<BoscaApplication>(singleton = true) { application }
        provides<Json>(singleton = true) { application.json }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<SecurityService> { mockk(relaxed = true) }
        val locks = NatsDistributedLockFactory(natsPool)
        provides<DistributedLockFactory>(singleton = true) { locks }
        events = MutableSharedFlow(extraBufferCapacity = 256)
        provides<JobEnqueueEventChannel>(singleton = true) {
            object : JobEnqueueEventChannel {
                override suspend fun emit(event: JobEnqueueEvent) = events.emit(event)
                override fun events() = events
            }
        }
        val factory = NatsJobQueueFactory(
            natsPool, application.json, locks, enqueueEventChannel = null,
            enqueueCallbacks = emptyList(), processExpiredJobs = false,
        )
        val suffix = UUID.random().toString().take(8)
        val queues = mapOf(
            BackupJobQueueNames.backupQueue to "backup-$suffix",
            JobQueueNames.commonQueue to "recovery-$suffix",
        )
        val queueFactory = object : JobQueueFactory {
            override fun create(name: String): JobQueue {
                val delegate = factory.create(queues.getValue(name))
                if (name != BackupJobQueueNames.backupQueue) return delegate
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
        provides<JobQueueFactory>(singleton = true) { queueFactory }
        provides<JobQueue>(name = JobQueueNames.commonJobQueue, singleton = true) {
            queueFactory.create(JobQueueNames.commonQueue)
        }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        SchedulerProviderRegistrar().register()
        ProviderRegistry.register(PackageInstallationsRepository::class, PackageInstallationsRepositoryProvider(), true)
        ProviderRegistry.register(PackageInstallationService::class, PackageInstallationServiceImplProvider(), true)
        provides<PackageInstallation>(name = "maintenance") { Configuration().maintenancePackage() }
        provides<PackageInstaller>(name = "maintenance-jobs") {
            Configuration().maintenanceJobsInstaller(provide(), application.json)
        }
        val installationMigration = object : Migration {
            override val schema = "public"
            override val resources = listOf("V64__installer.sql")
        }
        FlywayMigration(pool).migrate(listOf(
            installationMigration,
            provide<Migration>(name = "backup-migrations"),
            provide<Migration>(name = "scheduler-migrations"),
        ))
        scheduler = provide()
        backupQueue = provide(name = BackupJobQueueNames.backupJobQueue)
        assertNull(backupQueue.dequeue(25.milliseconds))
        backupRunner = JobRunner(backupQueue, 1, locks)
        commonRunner = JobRunner(provide(name = JobQueueNames.commonJobQueue), 1, locks)
        schedulerRunner = provide()
        forwarder = provide()
        forwarder.start()
        withTimeout(10_000) { events.subscriptionCount.first { it > 0 } }
        request {
            provide<ObjectBackupStorageService>().bindConfigured(configurations.keys)
            val history = provide<PackageInstallationsRepository>()
            history.addHistory(PackageInstallationHistory(key = "maintenance", version = "1.4.0"))
            history.addHistory(PackageInstallationHistory(key = "maintenance:maintenance-jobs", version = "1.4.0"))
            for (name in listOf(
                "finalize-deletion", "purge-ephemeral-scripts", "purge-job-history",
                "delete-expired-security-tokens", "delete-expired-bible-chapters",
                "message-outbox-maintenance", "notification-maintenance",
            )) {
                if (!provideProvider<JobConfigurationEnqueuer>(name).exists) {
                    provides<JobConfigurationEnqueuer>(name = name) { mockk() }
                }
                scheduler.createJob(ScheduledJobInput(
                    name = name, jobName = name, cronExpression = "0 0 1 1 *",
                ), UUID.NIL)
            }
        }
        provide<ObjectStorageServiceFactory>().withStorage(configurations.getValue("source")) {
            val bytes = payload.toByteArray()
            it.setInputStream(StringObjectPath(entry.key), bytes.inputStream(), bytes.size.toLong())
        }
        publicationFailure = false
        installationLog.start()
        logger.addAppender(installationLog)
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::schedulerRunner.isInitialized) schedulerRunner.stop()
            if (::commonRunner.isInitialized) commonRunner.shutdown()
            if (::backupRunner.isInitialized) backupRunner.shutdown()
            if (::forwarder.isInitialized) {
                forwarder.stop()
                withTimeout(10_000) { events.subscriptionCount.first { it == 0 } }
            }
            if (::nats.isInitialized) nats.stop()
            if (::application.isInitialized) application.shutdown()
            if (::pool.isInitialized) pool.close()
        } finally {
            logger.detachAppender(installationLog)
            installationLog.stop()
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `scheduler repeatedly recovers lost publications before and after a backup worker starts`(): Unit = runBlocking {
        PackageInstallations.install(application)
        val installed = recoverySchedule()
        assertEquals("0 0 * * *", installed.cronExpression)
        assertEquals(false, installed.allowConcurrent)
        assertEquals(false, installed.catchUp)
        val schedule = assertNotNull(request {
            scheduler.updateJob(installed.id, ScheduledJobInput(
                name = installed.name,
                jobName = installed.jobName,
                jobParameters = application.json.encodeToJsonElement(
                    ObjectBackupDispatchRecoveryJob.serializer(), ObjectBackupDispatchRecoveryJob(1),
                ),
                cronExpression = installed.cronExpression,
            ))
        })
        val lost = (1..3).map { lostPublication("lost-$it") }
        assertTrue(lost.all { state(it.key) == null })
        assertTrue(directory.resolve("retained").toFile().listFiles().isNullOrEmpty())
        makeDue(schedule)
        commonRunner.run()
        schedulerRunner.start()
        awaitCompletedSchedules(schedule, 1)
        assertTrue(lost.all { state(it.key) != null })
        for (dispatch in lost) {
            assertEquals(ObjectBackupRunStatus.PENDING, request {
                provide<ObjectBackupRunService>().get(dispatch.runKey)
            }?.status)
        }
        assertTrue(directory.resolve("retained").toFile().listFiles().isNullOrEmpty())
        backupRunner.run()
        awaitCompletedRuns(lost)
        val later = lostPublication("lost-after-first-sweep")
        makeDue(schedule)
        awaitCompletedSchedules(schedule, 2)
        awaitCompletedRuns(listOf(later))
        provide<ObjectStorageServiceFactory>().withStorage(configurations.getValue("retained")) {
            assertEquals(payload, it.getString(StringObjectPath(entry.payloadPath)))
        }
    }

    @Test
    fun `boot upgrade passes both version gates and preserves an edited disabled schedule on rerun`(): Unit = runBlocking {
        PackageInstallations.install(application)
        val installed = recoverySchedule()
        assertEquals("1.5.0", MaintenanceJobsInstaller.VERSION)
        assertEquals("1.5.0", Configuration().maintenancePackage().versions.single().version)
        assertTrue(installationLog.list.any {
            it.formattedMessage.contains("installing: Maintenance Jobs") && it.formattedMessage.contains("1.5.0")
        })
        assertTrue(installationLog.list.any {
            it.formattedMessage.contains("executing installer: maintenance:maintenance-jobs:1.5.0")
        })
        val edited = assertNotNull(request {
            scheduler.updateJob(installed.id, ScheduledJobInput(
                name = "Edited recovery schedule", jobName = installed.jobName,
                cronExpression = "*/5 * * * *", enabled = false,
                jobParameters = application.json.encodeToJsonElement(
                    ObjectBackupDispatchRecoveryJob.serializer(), ObjectBackupDispatchRecoveryJob(3),
                ),
            ))
        })
        request {
            for (index in 1..101) {
                scheduler.createJob(ScheduledJobInput(
                    name = "Other schedule $index", jobName = "notification-maintenance",
                    cronExpression = "0 0 1 1 *", enabled = false,
                ), UUID.NIL)
            }
            assertTrue(scheduler.getJobs().none { it.id == edited.id })
        }
        PackageInstallations.install(application)
        request {
            val definition = Configuration().maintenancePackage()
            MaintenanceJobsInstaller(scheduler, application.json).install(definition, definition.versions.single())
        }
        assertEquals(edited, recoverySchedule())
        val history = request { provide<PackageInstallationsRepository>().getHistory() }
        assertEquals(1, history.count { it.historyKey == "maintenance:1.5.0" })
        assertEquals(1, history.count { it.historyKey == "maintenance:maintenance-jobs:1.5.0" })
        assertEquals(4, history.size)
    }

    private suspend fun recoverySchedule(): ScheduledJob = request {
        scheduler.getJobsByName(ObjectBackupDispatchRecoveryJob.NAME).single()
    }

    private suspend fun makeDue(schedule: ScheduledJob) = request {
        val now = OffsetDateTime.now()
        scheduler.updateJobRunTimes(schedule.id, now.minusMinutes(1), now.minusSeconds(1))
    }

    private suspend fun lostPublication(id: String): ObjectBackupDispatch {
        publicationFailure = true
        return try {
            request {
                val runs = provide<ObjectBackupRunService>()
                val manifest = ObjectBackupManifest(
                    id, "source-instance", "content", "2026-10-17T00:00:00Z", listOf(entry),
                )
                val run = runs.prepare(manifest, "retained")
                runs.configureStorage(run.key, "retained", mapOf("content" to "source"))
                provide<ObjectBackupDispatchService>().request(run.key, run.version)
            }
        } finally {
            publicationFailure = false
        }
    }

    private suspend fun awaitCompletedSchedules(schedule: ScheduledJob, count: Int) {
        withTimeout(45_000) {
            while (request {
                scheduler.getHistory(schedule.id, limit = 100).count { it.status == ScheduleExecutionStatus.COMPLETED }
            } < count) delay(25)
        }
    }

    private suspend fun awaitCompletedRuns(dispatches: List<ObjectBackupDispatch>) {
        withTimeout(30_000) {
            for (dispatch in dispatches) {
                while (request {
                    provide<ObjectBackupRunService>().get(dispatch.runKey)?.status
                } != ObjectBackupRunStatus.COMPLETED) delay(25)
                assertEquals(1, request { provide<ObjectBackupRunService>().get(dispatch.runKey) }?.attempts)
            }
        }
    }

    private suspend fun state(id: UUID): String? =
        natsPool.systemConnection().keyValue("job-state-" + backupQueue.name).get(id.toString())?.valueAsString

    private suspend fun <T> request(block: suspend () -> T): T = withConnectionManager(block)
}
