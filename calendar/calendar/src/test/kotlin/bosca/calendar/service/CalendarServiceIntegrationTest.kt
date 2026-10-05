package bosca.calendar.service

import bosca.calendar.configuration.CalendarMigration
import bosca.calendar.model.CalendarEventInput
import bosca.calendar.model.CalendarInput
import bosca.calendar.model.OccurrenceInput
import bosca.calendar.repository.CalendarEventRepositoryImpl
import bosca.calendar.repository.CalendarRepositoryImpl
import bosca.calendar.repository.EventAttachmentRepository
import bosca.calendar.repository.EventAttachmentRepositoryImpl
import bosca.calendar.repository.EventParticipantRepository
import bosca.calendar.repository.EventParticipantRepositoryImpl
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.toJavaUuid

/**
 * Hits a real PostgreSQL via TestContainers to verify the V1 migration, the
 * generated repository impls, and the recurrence semantics end-to-end.
 *
 * The metadata, MetadataService, and MetadataPermissionEvaluator stand-ins
 * are stubbed: we want to validate the SQL and service logic, not re-test
 * the metadata permission system.
 */
@OptIn(InternalDI::class)
class CalendarServiceIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5
                ),
                key = "test"
            )
        )

        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking {
                pool.close()
            }
            postgres.stop()
        }
    }

    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val metadataPermissions = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)

    private val metadataId = UUID.random()
    private val version = 1
    private lateinit var service: CalendarServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }

        if (!schemaInitialized) {
            withDb {
                // Stand-in for public.metadata so the FK from calendar.calendars resolves.
                connection().useStatement(
                    """
                    CREATE TABLE IF NOT EXISTS public.metadata (
                        id uuid PRIMARY KEY,
                        version int NOT NULL DEFAULT 1
                    )
                    """
                ) { it.execute() }
                connection().useStatement(
                    """
                    CREATE TABLE IF NOT EXISTS public.collections (
                        id uuid PRIMARY KEY
                    )
                    """
                ) { it.execute() }
            }
            runBlocking { FlywayMigration(pool).migrate(listOf(CalendarMigration())) }
            schemaInitialized = true
        }

        withDb {
            bosca.db.transaction {
                connection().useStatement("DELETE FROM calendar.events") { it.execute() }
                connection().useStatement("DELETE FROM calendar.calendars") { it.execute() }
                connection().useStatement("DELETE FROM public.metadata") { it.execute() }
                connection().useStatement("INSERT INTO public.metadata (id, version) VALUES (?, ?)") {
                    it.setObject(1, metadataId.toJavaUuid())
                    it.setInt(2, version)
                    it.execute()
                }
            }
        }

        // Stub metadata + permissions so the service treats the test principal as fully authorized.
        val metadata = mockk<Metadata>(relaxed = true)
        coEvery { metadata.id } returns metadataId
        coEvery { metadata.version } returns version
        coEvery { metadataService.getById(metadataId, version) } returns metadata
        coEvery { metadataPermissions.isAllowed(authentication, metadata, PermissionAction.VIEW) } returns true
        coEvery { metadataPermissions.verifyAllowed(authentication, metadata, PermissionAction.EDIT) } returns Unit

        service = CalendarServiceImpl(
            CalendarRepositoryImpl(),
            CalendarEventRepositoryImpl(),
            EventParticipantRepositoryImpl(),
            EventAttachmentRepositoryImpl(),
            metadataService,
            metadataPermissions
        )
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) { block() }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9): OffsetDateTime =
        OffsetDateTime.of(year, month, day, hour, 0, 0, 0, ZoneOffset.UTC)

    private suspend fun seedCalendar() = service.createCalendar(authentication, metadataId, version, CalendarInput(color = "#3b82f6"))

    // --- calendar CRUD ---

    @Test
    fun `createCalendar inserts a row and is idempotent`() = withDb {
        val first = seedCalendar()
        val again = seedCalendar()
        assertEquals(first.metadataId, again.metadataId)
        val all = service.getAllCalendars(authentication)
        assertEquals(1, all.size)
    }

    @Test
    fun `editCalendar updates color and description`() = withDb {
        seedCalendar()
        val out = service.editCalendar(authentication, metadataId, version, CalendarInput(color = "#000", description = "team"))
        assertEquals("#000", out.color)
        assertEquals("team", out.description)
    }

    @Test
    fun `getAllCalendars filters out calendars on metadata the caller cannot VIEW`() = withDb {
        seedCalendar()
        val hiddenMetaId = UUID.random()
        val hiddenMeta = mockk<Metadata>(relaxed = true)
        coEvery { metadataService.getById(hiddenMetaId, 1) } returns hiddenMeta
        coEvery { metadataPermissions.isAllowed(authentication, hiddenMeta, PermissionAction.VIEW) } returns false
        coEvery { metadataPermissions.verifyAllowed(authentication, hiddenMeta, PermissionAction.EDIT) } returns Unit
        // Insert a second row directly so we don't have to grant EDIT then revoke VIEW.
        bosca.db.transaction {
            connection().useStatement("INSERT INTO public.metadata (id, version) VALUES (?, 1)") {
                it.setObject(1, hiddenMetaId.toJavaUuid())
                it.execute()
            }
            connection().useStatement(
                "INSERT INTO calendar.calendars (metadata_id, version, color) VALUES (?, 1, '#fff')"
            ) {
                it.setObject(1, hiddenMetaId.toJavaUuid())
                it.execute()
            }
        }

        val visible = service.getAllCalendars(authentication)
        assertEquals(1, visible.size)
        assertEquals(metadataId, visible.single().metadataId)
    }

    // --- single events ---

    @Test
    fun `addEvent then deleteEvent round-trips a single`() = withDb {
        seedCalendar()
        val ev = service.addEvent(
            authentication,
            CalendarEventInput(
                metadataId = metadataId, version = version,
                title = "lunch", startsAt = at(2026, 1, 5, 12), endsAt = at(2026, 1, 5, 13)
            )
        )
        val occ = service.getOccurrences(authentication, metadataId, version, at(2026, 1, 1), at(2026, 2, 1))
        assertEquals(1, occ.size)
        assertEquals(ev.id, occ.single().event.id)

        service.deleteEvent(authentication, ev.id)
        assertTrue(service.getOccurrences(authentication, metadataId, version, at(2026, 1, 1), at(2026, 2, 1)).isEmpty())
    }

    // --- recurring events ---

    @Test
    fun `recurring weekly master expands into Mondays`() = withDb {
        seedCalendar()
        service.addEvent(
            authentication,
            CalendarEventInput(
                metadataId = metadataId, version = version,
                title = "standup",
                startsAt = at(2026, 1, 5),
                endsAt = at(2026, 1, 5, 10),
                rrule = "FREQ=WEEKLY;BYDAY=MO"
            )
        )
        val occ = service.getOccurrences(authentication, metadataId, version, at(2026, 1, 1), at(2026, 2, 1))
        assertEquals(4, occ.size)
        assertTrue(occ.all { it.isRecurring })
    }

    @Test
    fun `editOccurrence creates an override that supplants the master at that time`() = withDb {
        seedCalendar()
        val master = service.addEvent(
            authentication,
            CalendarEventInput(
                metadataId = metadataId, version = version,
                title = "standup",
                startsAt = at(2026, 1, 5),
                endsAt = at(2026, 1, 5, 10),
                rrule = "FREQ=WEEKLY;BYDAY=MO"
            )
        )
        service.editOccurrence(
            authentication, master.id, at(2026, 1, 12),
            OccurrenceInput(title = "with VP", startsAt = at(2026, 1, 12, 14), endsAt = at(2026, 1, 12, 15))
        )

        val occ = service.getOccurrences(authentication, metadataId, version, at(2026, 1, 1), at(2026, 2, 1))
        // 4 Mondays — Jan 12 9am suppressed, Jan 12 2pm appears as override
        assertEquals(4, occ.size)
        val moved = occ.first { it.startsAt == at(2026, 1, 12, 14) }
        assertTrue(moved.isException)
        assertEquals("with VP", moved.event.title)
        // Master's natural Jan 12 9am is gone
        assertTrue(occ.none { it.startsAt == at(2026, 1, 12) })
    }

    @Test
    fun `cancelOccurrence appends an EXDATE so future expansions skip it`() = withDb {
        seedCalendar()
        val master = service.addEvent(
            authentication,
            CalendarEventInput(
                metadataId = metadataId, version = version,
                title = "standup",
                startsAt = at(2026, 1, 5),
                endsAt = at(2026, 1, 5, 10),
                rrule = "FREQ=WEEKLY;BYDAY=MO"
            )
        )
        service.cancelOccurrence(authentication, master.id, at(2026, 1, 12))

        val occ = service.getOccurrences(authentication, metadataId, version, at(2026, 1, 1), at(2026, 2, 1))
        assertEquals(3, occ.size)
        assertTrue(occ.none { it.startsAt == at(2026, 1, 12) })
    }

    @Test
    fun `cancelOccurrence after editOccurrence removes the override too`() = withDb {
        seedCalendar()
        val master = service.addEvent(
            authentication,
            CalendarEventInput(
                metadataId = metadataId, version = version,
                title = "standup",
                startsAt = at(2026, 1, 5),
                endsAt = at(2026, 1, 5, 10),
                rrule = "FREQ=WEEKLY;BYDAY=MO"
            )
        )
        service.editOccurrence(
            authentication, master.id, at(2026, 1, 12),
            OccurrenceInput(title = "moved", startsAt = at(2026, 1, 12, 14), endsAt = at(2026, 1, 12, 15))
        )
        service.cancelOccurrence(authentication, master.id, at(2026, 1, 12))

        val occ = service.getOccurrences(authentication, metadataId, version, at(2026, 1, 1), at(2026, 2, 1))
        // Only 3 Mondays; the override is gone too
        assertEquals(3, occ.size)
    }

    @Test
    fun `splitSeriesAt caps the original and creates a successor with new properties`() = withDb {
        seedCalendar()
        val master = service.addEvent(
            authentication,
            CalendarEventInput(
                metadataId = metadataId, version = version,
                title = "standup",
                startsAt = at(2026, 1, 5),
                endsAt = at(2026, 1, 5, 10),
                rrule = "FREQ=WEEKLY;BYDAY=MO"
            )
        )
        service.splitSeriesAt(
            authentication, master.id, at(2026, 1, 19),
            CalendarEventInput(
                metadataId = metadataId, version = version,
                title = "Daily Sync",
                startsAt = at(2026, 1, 19),
                endsAt = at(2026, 1, 19, 11),
                rrule = "FREQ=WEEKLY;BYDAY=MO"
            )
        )

        val occ = service.getOccurrences(authentication, metadataId, version, at(2026, 1, 1), at(2026, 2, 1))
        // Old master kept Jan 5 + Jan 12; new master takes Jan 19 + Jan 26
        val byTitle = occ.groupBy { it.event.title }
        assertEquals(2, byTitle["standup"]?.size)
        assertEquals(2, byTitle["Daily Sync"]?.size)
    }

    @Test
    fun `endSeriesAt prevents future occurrences but keeps prior ones`() = withDb {
        seedCalendar()
        val master = service.addEvent(
            authentication,
            CalendarEventInput(
                metadataId = metadataId, version = version,
                title = "standup",
                startsAt = at(2026, 1, 5),
                endsAt = at(2026, 1, 5, 10),
                rrule = "FREQ=WEEKLY;BYDAY=MO"
            )
        )
        service.endSeriesAt(authentication, master.id, at(2026, 1, 19))

        val occ = service.getOccurrences(authentication, metadataId, version, at(2026, 1, 1), at(2026, 2, 1))
        assertEquals(2, occ.size)
        assertTrue(occ.none { !it.startsAt.isBefore(at(2026, 1, 19)) })
    }

    @Test
    fun `editEvent on master with new rrule prunes orphan overrides`() = withDb {
        seedCalendar()
        val master = service.addEvent(
            authentication,
            CalendarEventInput(
                metadataId = metadataId, version = version,
                title = "standup",
                startsAt = at(2026, 1, 5),
                endsAt = at(2026, 1, 5, 10),
                rrule = "FREQ=WEEKLY;BYDAY=MO"
            )
        )
        // Override Monday Jan 12
        service.editOccurrence(
            authentication, master.id, at(2026, 1, 12),
            OccurrenceInput(title = "moved", startsAt = at(2026, 1, 12, 14), endsAt = at(2026, 1, 12, 15))
        )
        // Switch the master to Tuesdays — the Monday override is now an orphan and must go.
        service.editEvent(
            authentication, master.id,
            CalendarEventInput(
                metadataId = metadataId, version = version,
                title = "standup",
                startsAt = at(2026, 1, 6),
                endsAt = at(2026, 1, 6, 10),
                rrule = "FREQ=WEEKLY;BYDAY=TU"
            )
        )
        val occ = service.getOccurrences(authentication, metadataId, version, at(2026, 1, 1), at(2026, 2, 1))
        // Only Tuesdays; no leftover Monday override
        assertTrue(occ.none { it.startsAt.dayOfWeek.value == 1 })
        assertTrue(occ.all { it.startsAt.dayOfWeek.value == 2 })
    }

    // --- permission gates ---

    @Test
    fun `getCalendar returns null when caller lacks VIEW`() = withDb {
        seedCalendar()
        val meta = mockk<Metadata>(relaxed = true)
        coEvery { metadataService.getById(metadataId, version) } returns meta
        coEvery { metadataPermissions.isAllowed(authentication, meta, PermissionAction.VIEW) } returns false
        assertNull(service.getCalendar(authentication, metadataId, version))
    }

    @Test
    fun `addEvent throws when caller lacks EDIT`() = withDb {
        seedCalendar()
        val meta = mockk<Metadata>(relaxed = true)
        coEvery { metadataService.getById(metadataId, version) } returns meta
        coEvery { metadataPermissions.verifyAllowed(authentication, meta, PermissionAction.EDIT) } throws SecurityException("no")
        assertFailsWith<SecurityException> {
            service.addEvent(
                authentication,
                CalendarEventInput(
                    metadataId = metadataId, version = version,
                    title = "x", startsAt = at(2026, 1, 1), endsAt = at(2026, 1, 1, 10)
                )
            )
        }
    }

    @Test
    fun `getEvent returns null when caller lacks VIEW`() = withDb {
        seedCalendar()
        val ev = service.addEvent(
            authentication,
            CalendarEventInput(
                metadataId = metadataId, version = version,
                title = "x", startsAt = at(2026, 1, 1), endsAt = at(2026, 1, 1, 10)
            )
        )
        val meta = mockk<Metadata>(relaxed = true)
        coEvery { metadataService.getById(metadataId, version) } returns meta
        coEvery { metadataPermissions.isAllowed(authentication, meta, PermissionAction.VIEW) } returns false
        assertNull(service.getEvent(authentication, ev.id))
    }

    // --- override metadata round-trip ---

    @Test
    fun `override row exposes originalEventId and recurrenceId`() = withDb {
        seedCalendar()
        val master = service.addEvent(
            authentication,
            CalendarEventInput(
                metadataId = metadataId, version = version,
                title = "standup",
                startsAt = at(2026, 1, 5),
                endsAt = at(2026, 1, 5, 10),
                rrule = "FREQ=WEEKLY;BYDAY=MO"
            )
        )
        val ovr = service.editOccurrence(
            authentication, master.id, at(2026, 1, 12),
            OccurrenceInput(title = "moved", startsAt = at(2026, 1, 12, 14), endsAt = at(2026, 1, 12, 15))
        )
        val fetched = service.getEvent(authentication, ovr.id)
        assertNotNull(fetched)
        assertEquals(master.id, fetched.originalEventId)
        assertEquals(at(2026, 1, 12), fetched.recurrenceId)
    }
}
