@file:OptIn(bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)
package bosca.sharedqueue.jobs.postgres

import bosca.db.*
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.*
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PostgresJobQueueIntegrationTest {
    private lateinit var fixture: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var factory: ConnectionFactory
    private lateinit var queue: PostgresJobQueue
    @BeforeTest fun setup() {
        bosca.di.ProviderRegistry.clear()
        fixture = SharedPostgreSQLContainer().withDatabaseName("postgres_jobs"); fixture.start()
        factory = ConnectionFactoryImpl(ConnectionConfig(fixture.jdbcUrl, fixture.username, fixture.password), "postgres")
        factory.create().use { connection -> connection.createStatement().use { it.execute(listOf("V200__postgres_infrastructure.sql", "V201__postgres_cache_counters.sql", "V202__postgres_locks.sql").joinToString("\n") { resource -> checkNotNull(javaClass.getResourceAsStream("/db/migrations/$resource")).bufferedReader().use { it.readText() } }) } }
        for (migration in PostgresJobQueueMigration().resources) {
            factory.create().use { connection -> connection.createStatement().use { it.execute(checkNotNull(javaClass.getResourceAsStream("/db/migrations/$migration")).bufferedReader().use { r -> r.readText() }) } }
        }
        pool = ConnectionPool(factory); queue = PostgresJobQueue("test", pool, Json)
        bosca.di.provides<ConnectionPool> { pool }
        bosca.di.provides<bosca.cache.CacheManager> { bosca.cache.postgres.PostgresCacheManager(pool) }
        bosca.di.provides<bosca.cache.RequestCacheSerializer> { bosca.cache.RequestCacheSerializerImpl(Json) }
    }
    @AfterTest fun teardown() = runBlocking { pool.close(); fixture.stop(); bosca.di.ProviderRegistry.clear() }
    private fun job() = InternalJobConstructor(Json.parseToJsonElement("{}"), PostgresTestExecutor::class)

    @Test fun `competing workers claim each job once and completion removes state`(): Unit = runBlocking {
        repeat(20) { queue.enqueue(job()) }
        val workers = List(4) { PostgresJobQueue("test", pool, Json) }
        val results = coroutineScope { workers.map { worker -> async { buildList { while (true) { val claimed = worker.dequeue() ?: break; add(claimed.id); worker.markComplete(claimed) } } } }.awaitAll().flatten() }
        assertEquals(20, results.size); assertEquals(20, results.toSet().size)
        assertNull(queue.dequeue()); queue.getJob(results.first()) { assertNull(it) }
    }
    @Test fun `enqueue after commit is discarded on rollback and persistent IDs are idempotent`(): Unit = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) {
                manager.beginTransaction(); queue.enqueue(job()); manager.rollbackTransaction()
            }
        } finally { manager.release() }
        assertNull(queue.dequeue())
        val persistent = job().apply { setId(UUID.random()) }
        queue.enqueueIfAbsent(persistent); queue.enqueueIfAbsent(persistent)
        val claimed = assertNotNull(queue.dequeue())
        assertEquals(persistent.id, claimed.id)
        assertNull(queue.dequeue())
        queue.markComplete(claimed)
    }
    @Test fun `delayed delivery cancellation and nonterminal failure retain expected state`(): Unit = runBlocking {
        val id = queue.enqueueLater(job(), 100.milliseconds)
        assertNull(queue.dequeue())
        val delayed = assertNotNull(queue.dequeue(500.milliseconds))
        assertEquals(id, delayed.id)
        queue.markFailed(delayed, IllegalStateException("retry"), true)
        assertNull(queue.dequeue())
        queue.getJob(id) { assertEquals(JobStatus.FAILED, it?.status); assertEquals(1, it?.failures) }
        queue.markCancelled(id); queue.getJob(id) { assertNull(it) }
    }
    @Test fun `expired delivery recovers and stale owner cannot complete or release current lease`(): Unit = runBlocking {
        val id = queue.enqueue(job())
        val stale = assertNotNull(queue.dequeue())
        factory.create().use { connection -> connection.createStatement().use { it.executeUpdate("update infrastructure.locks set expires = clock_timestamp() - interval '1 second'"); it.executeUpdate("update sharedqueue.jobs set claimed_until = clock_timestamp() - interval '1 second'") } }
        val recovered = assertNotNull(PostgresJobQueue("test", pool, Json).dequeue())
        assertEquals(id, recovered.id)
        assertFailsWith<IllegalStateException> { queue.markComplete(stale) }
        assertTrue(queue.checkin(recovered, 5000))
        queue.markComplete(recovered)
    }
    @Test fun `polling reclaims expired claims at most once per interval per instance`(): Unit = runBlocking {
        val id = queue.enqueue(job())
        assertNotNull(queue.dequeue())
        factory.create().use { connection -> connection.createStatement().use { it.executeUpdate("update infrastructure.locks set expires = clock_timestamp() - interval '1 second'"); it.executeUpdate("update sharedqueue.jobs set claimed_until = clock_timestamp() - interval '1 second'") } }
        assertNull(queue.dequeue(), "A poll inside the interval must not rescan expired claims")
        queue.checkForExpiredJobs()
        val recovered = assertNotNull(queue.dequeue())
        assertEquals(id, recovered.id)
        queue.markComplete(recovered)
    }
    @Test fun `job state changes complete while a transaction holds every main connection`(): Unit = runBlocking {
        val main = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(fixture.jdbcUrl, fixture.username, fixture.password, maxConnections = 1), "postgres"))
        val id = queue.enqueue(job())
        val claimed = assertNotNull(queue.dequeue())
        val manager = main.connection()
        try {
            withContext(manager.asCoroutineContext()) {
                manager.beginTransaction()
                manager.useStatement("select 1") { it.executeQuery().close() }
                withTimeout(2_000) {
                    queue.setJob(claimed)
                    assertTrue(queue.checkin(claimed, 5000))
                    queue.markComplete(claimed)
                    queue.enqueue(job())
                }
                manager.rollbackTransaction()
            }
        } finally { manager.release(); main.close() }
        queue.getJob(id) { assertNull(it) }
        assertNull(queue.dequeue(), "An enqueue inside the rolled back transaction must not become visible")
    }
    @Test fun `transactional enqueue becomes visible to another worker only after commit`(): Unit = runBlocking {
        val manager = pool.connection()
        val pending = job()
        try {
            withContext(manager.asCoroutineContext()) {
                manager.beginTransaction()
                queue.enqueue(pending)
                assertNull(PostgresJobQueue("test", pool, Json).dequeue())
                manager.commitTransaction()
            }
        } finally { manager.release() }
        val received = assertNotNull(PostgresJobQueue("test", pool, Json).dequeue())
        assertEquals(pending.id, received.id)
        queue.markComplete(received)
    }

    @Test fun `factory registers queues and completion notifies a real listener`(): Unit = runBlocking {
        var observed: JobStatus? = null
        bosca.di.provides<JobListener>("postgres-test-listener") { object : JobListener {
            override suspend fun onStatusChanged(job: bosca.sharedqueue.jobs.Job, status: JobStatus, errorMessage: String?) { observed = status }
        } }
        val channel = bosca.sharedqueue.jobs.enqueue.DefaultJobEnqueueEventChannel()
        val factory = PostgresJobQueueFactory(pool, Json, channel, listOf(JobCallback(JobListener::class, "postgres-test-listener")))
        val created = factory.create("factory")
        assertSame(created, JobQueueRegistry.find("factory"))
        val id = created.enqueue(job())
        val running = assertNotNull(created.dequeue())
        assertEquals(id, running.id)
        created.markComplete(running)
        assertEquals(JobStatus.COMPLETE, observed)
        val undecorated = PostgresJobQueueFactory(pool, Json, null, emptyList()).create("plain")
        assertEquals("plain", undecorated.name)
    }
    @Test fun `definition changes terminal failure and administrative lease recovery persist correctly`(): Unit = runBlocking {
        val id = queue.enqueue(job())
        val running = assertNotNull(queue.dequeue())
        val definition = Json.parseToJsonElement("{\"changed\":true}")
        queue.setDefinition(running, definition)
        queue.releaseJobLock(running)
        queue.getJob(id) { assertEquals(definition, it?.definition) }
        queue.clearAllJobLocks()
        queue.expireAllJobs()
        val recovered = assertNotNull(queue.dequeue())
        assertEquals(id, recovered.id)
        queue.markFailed(recovered, IllegalStateException("terminal"), false)
        queue.getJob(id) { assertNull(it) }
        assertNull(queue.dequeue(1.milliseconds))
    }

    @Test fun `child completion joins a parent on another queue through the real listener`(): Unit = runBlocking {
        bosca.di.provides<bosca.sharedqueue.jobs.listeners.NotifyParentListener> { bosca.sharedqueue.jobs.listeners.NotifyParentListener() }
        val factory = PostgresJobQueueFactory(pool, Json, null, emptyList())
        val parents = factory.create("parents"); val children = factory.create("children")
        val parentId = parents.enqueue(job()); val parent = assertNotNull(parents.dequeue())
        val child = job(); parent.addChild(child, runOnParentComplete = false)
        children.enqueue(child); parents.setJob(parent); parents.markComplete(parent)
        parents.getJob(parentId) { assertNotNull(it); assertFalse(it.isFullyComplete()) }
        val runningChild = assertNotNull(children.dequeue())
        withContext(children.asCoroutineContext(runningChild)) { children.markComplete(runningChild) }
        parents.getJob(parentId) { assertNull(it) }
    }
    @Test fun `invalid persistent ID and lost lease cannot mutate queue state`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { queue.enqueueIfAbsent(job()) }
        assertFailsWith<IllegalStateException> { queue.setJob(job()) }
        assertFalse(queue.checkin(job(), 1000))
        val id = queue.enqueue(job()); val running = assertNotNull(queue.dequeue())
        assertTrue(queue.checkin(running, 100))
        delay(150)
        assertFailsWith<IllegalStateException> { queue.setJob(running) }
        assertFalse(queue.checkin(running, 1000))
        queue.markCancelled(id)
    }
    @Test fun `last allowed retry becomes terminal rather than returning to pending`(): Unit = runBlocking {
        val limited = bosca.sharedqueue.jobs.Job(definition = Json.parseToJsonElement("{}"), executor = PostgresTestExecutor::class, maxFailures = 1)
        val id = queue.enqueue(limited); val running = assertNotNull(queue.dequeue())
        queue.markFailed(running, IllegalStateException("exhausted"), true)
        queue.getJob(id) { assertNull(it) }
        assertEquals(JobStatus.FAILED_AND_COMPLETE, running.status)
    }
    @Test fun `a delayed job contended by its releasing owner is retried promptly instead of after claim expiry`(): Unit = runBlocking {
        val id = queue.enqueue(job())
        val owner = assertNotNull(queue.dequeue())
        // JobRunner's DelayException path re-enqueues before its finally block releases the lease.
        queue.enqueueLater(owner, kotlin.time.Duration.ZERO)
        val worker = PostgresJobQueue("test", pool, Json)
        assertNull(worker.dequeue(), "The lease is still held by the owner")
        owner.lock?.release()
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val retried = assertNotNull(worker.dequeue(5.seconds))
        assertEquals(id, retried.id)
        assertTrue(started.elapsedNow() < 5.seconds)
        worker.markComplete(retried)
        queue.getJob(id) { assertNull(it) }
    }
    @Test fun `completion after the local lease deadline reclaims an unowned lease`(): Unit = runBlocking {
        val id = queue.enqueue(job())
        val running = assertNotNull(queue.dequeue())
        assertTrue(queue.checkin(running, 100))
        delay(150)
        assertFalse(running.lock?.isHeld == true)
        queue.markComplete(running)
        queue.getJob(id) { assertNull(it) }
    }
    @Test fun `failure after the local lease deadline renews a lease the database still grants`(): Unit = runBlocking {
        val id = queue.enqueue(job())
        val running = assertNotNull(queue.dequeue())
        assertTrue(queue.checkin(running, 100))
        factory.create().use { connection -> connection.createStatement().use { it.executeUpdate("update infrastructure.locks set expires = clock_timestamp() + interval '1 minute'") } }
        delay(150)
        assertFalse(running.lock?.isHeld == true)
        queue.markFailed(running, IllegalStateException("retry"), true)
        queue.getJob(id) { assertEquals(JobStatus.FAILED, it?.status); assertEquals(1, it?.failures) }
        queue.markCancelled(id)
    }
}
