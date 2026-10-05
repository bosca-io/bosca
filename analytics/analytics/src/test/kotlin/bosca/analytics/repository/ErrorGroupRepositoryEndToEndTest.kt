@file:OptIn(InternalDI::class)

package bosca.analytics.repository

import bosca.analytics.model.ErrorGroupStatus
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUIDSerializer
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration

/**
 * Integration test for [ErrorGroupRepository] against a real PostgreSQL
 * instance. Verifies the upsert semantics (count increment, timestamp
 * advancement, regression flip) that the in-memory fake used in unit
 * tests cannot fully replicate.
 */
class ErrorGroupRepositoryEndToEndTest {

    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private lateinit var connectionPool: ConnectionPool
    private lateinit var repository: ErrorGroupRepository

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()

        postgresContainer = SharedPostgreSQLContainer()
            .withExposedPorts(5432)
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("POSTGRES_DB", "test")
            .withReuse(true)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
        postgresContainer.start()

        val factory = ConnectionFactoryImpl(
            ConnectionConfig(
                url = postgresContainer.jdbcUrl,
                user = postgresContainer.username,
                password = postgresContainer.password,
            ),
            key = "test"
        )
        connectionPool = ConnectionPool(factory)

        FlywayMigration(connectionPool).migrate(listOf(CoreMigration()))

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        provides<RequestCacheSerializer>(singleton = true) { mockk(relaxed = true) }
        provides<Json>(singleton = true) { testJson }

        repository = ErrorGroupRepositoryImpl()
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (::connectionPool.isInitialized) connectionPool.close()
        if (::postgresContainer.isInitialized) postgresContainer.stop()
        ProviderRegistry.clear()
    }

    private suspend fun <T> withConnection(block: suspend () -> T): T {
        val cm = ConnectionManager(connectionPool)
        return withContext(cm.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    cm.release()
                }
            }
        }
    }

    private val t1: OffsetDateTime = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    private val t2: OffsetDateTime = OffsetDateTime.of(2025, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC)
    private val t3: OffsetDateTime = OffsetDateTime.of(2025, 1, 3, 0, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `first insert creates a row`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            repository.recordOccurrence(
                fingerprint = "abc12345678901230000000000000001",
                appId = "test-app",
                type = "NullPointerException",
                message = "Something was null",
                fatal = false,
                firstSeen = t1,
                lastSeen = t1,
                count = 5,
                sampleEventId = "evt-001",
                sampleStack = "at Foo.bar(Foo.kt:42)",
            )

            val group = repository.getByFingerprint("abc12345678901230000000000000001")
            assertNotNull(group)
            assertEquals("test-app", group.appId)
            assertEquals("NullPointerException", group.type)
            assertEquals("Something was null", group.message)
            assertEquals(5L, group.eventCount)
            assertEquals(ErrorGroupStatus.OPEN, group.status)
            assertEquals("evt-001", group.sampleEventId)
        }
    }

    @Test
    fun `second insert increments event_count and advances last_seen`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            repository.recordOccurrence(
                fingerprint = "incr2345678901230000000000000002",
                appId = "test-app",
                type = "IOException",
                message = "first message",
                fatal = false,
                firstSeen = t2,
                lastSeen = t2,
                count = 3,
                sampleEventId = "evt-a",
                sampleStack = "stack-a",
            )
            repository.recordOccurrence(
                fingerprint = "incr2345678901230000000000000002",
                appId = "test-app",
                type = "IOException",
                message = "second message",
                fatal = true,
                firstSeen = t1,
                lastSeen = t3,
                count = 7,
                sampleEventId = "evt-b",
                sampleStack = "stack-b",
            )

            val group = repository.getByFingerprint("incr2345678901230000000000000002")
            assertNotNull(group)
            assertEquals(10L, group.eventCount, "event_count should be 3 + 7")
            assertEquals(t1, group.firstSeen, "first_seen should rewind to earliest via LEAST")
            assertEquals(t3, group.lastSeen, "last_seen should advance to latest via GREATEST")
            assertEquals("second message", group.message, "message should be from latest upsert")
            assertEquals("evt-b", group.sampleEventId, "sample should be from latest upsert")
        }
    }

    @Test
    fun `resolved group flips back to open on new occurrence`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            repository.recordOccurrence(
                fingerprint = "regr2345678901230000000000000003",
                appId = "test-app",
                type = "TimeoutException",
                message = "timed out",
                fatal = false,
                firstSeen = t1,
                lastSeen = t1,
                count = 1,
                sampleEventId = null,
                sampleStack = null,
            )

            val resolved = repository.setStatus("regr2345678901230000000000000003", ErrorGroupStatus.RESOLVED)
            assertNotNull(resolved)
            assertEquals(ErrorGroupStatus.RESOLVED, resolved.status)

            // New occurrence should flip it back to OPEN
            repository.recordOccurrence(
                fingerprint = "regr2345678901230000000000000003",
                appId = "test-app",
                type = "TimeoutException",
                message = "timed out again",
                fatal = false,
                firstSeen = t2,
                lastSeen = t2,
                count = 1,
                sampleEventId = null,
                sampleStack = null,
            )

            val reopened = repository.getByFingerprint("regr2345678901230000000000000003")
            assertNotNull(reopened)
            assertEquals(ErrorGroupStatus.OPEN, reopened.status, "resolved group should flip to open on regression")
            assertEquals(2L, reopened.eventCount)
        }
    }

    @Test
    fun `ignored group stays ignored on new occurrence`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            repository.recordOccurrence(
                fingerprint = "igno2345678901230000000000000004",
                appId = "test-app",
                type = "IgnoredException",
                message = "ignore me",
                fatal = false,
                firstSeen = t1,
                lastSeen = t1,
                count = 1,
                sampleEventId = null,
                sampleStack = null,
            )

            repository.setStatus("igno2345678901230000000000000004", ErrorGroupStatus.IGNORED)

            repository.recordOccurrence(
                fingerprint = "igno2345678901230000000000000004",
                appId = "test-app",
                type = "IgnoredException",
                message = "still ignored",
                fatal = false,
                firstSeen = t2,
                lastSeen = t2,
                count = 3,
                sampleEventId = null,
                sampleStack = null,
            )

            val group = repository.getByFingerprint("igno2345678901230000000000000004")
            assertNotNull(group)
            assertEquals(ErrorGroupStatus.IGNORED, group.status, "ignored group must stay ignored")
            assertEquals(4L, group.eventCount)
        }
    }

    @Test
    fun `list filters by status and appId`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            repository.recordOccurrence(
                fingerprint = "filt2345678901230000000000000005",
                appId = "app-a",
                type = "ErrorA",
                message = "msg a",
                fatal = false,
                firstSeen = t1,
                lastSeen = t1,
                count = 1,
                sampleEventId = null,
                sampleStack = null,
            )
            repository.recordOccurrence(
                fingerprint = "filt2345678901240000000000000006",
                appId = "app-b",
                type = "ErrorB",
                message = "msg b",
                fatal = true,
                firstSeen = t2,
                lastSeen = t2,
                count = 1,
                sampleEventId = null,
                sampleStack = null,
            )

            val allOpen = repository.list(
                appId = null, status = ErrorGroupStatus.OPEN, fatal = null,
                search = null, offset = 0, limit = 100,
            )
            assertEquals(2, allOpen.size)

            val appAOnly = repository.list(
                appId = "app-a", status = null, fatal = null,
                search = null, offset = 0, limit = 100,
            )
            assertEquals(1, appAOnly.size)
            assertEquals("app-a", appAOnly[0].appId)

            val fatalOnly = repository.list(
                appId = null, status = null, fatal = true,
                search = null, offset = 0, limit = 100,
            )
            assertEquals(1, fatalOnly.size)
            assertEquals("app-b", fatalOnly[0].appId)
        }
    }

    @Test
    fun `getByFingerprint returns null for nonexistent`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            assertNull(repository.getByFingerprint("00000000000000000000000000000000"))
        }
    }

    @Test
    fun `setAiSummary persists summary`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            repository.recordOccurrence(
                fingerprint = "aism2345678901230000000000000007",
                appId = "test-app",
                type = "SomeError",
                message = "some msg",
                fatal = false,
                firstSeen = t1,
                lastSeen = t1,
                count = 1,
                sampleEventId = null,
                sampleStack = null,
            )

            val updated = repository.setAiSummary("aism2345678901230000000000000007", "Root cause: null check missing")
            assertNotNull(updated)
            assertEquals("Root cause: null check missing", updated.aiSummary)
            assertNotNull(updated.aiSummaryAt)
        }
    }
}
