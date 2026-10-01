@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.db.ConnectionConfig
import io.mockk.mockk
import bosca.db.ConnectionFactoryImpl
import io.mockk.mockk
import bosca.db.ConnectionPool
import io.mockk.mockk
import bosca.db.asCoroutineContext
import io.mockk.mockk
import bosca.db.connection
import io.mockk.mockk
import bosca.db.migrations.FlywayMigration
import io.mockk.mockk
import bosca.db.transaction
import io.mockk.mockk
import bosca.di.ProviderRegistry
import io.mockk.mockk
import bosca.di.provides
import io.mockk.mockk
import bosca.serialization.UUID
import io.mockk.mockk
import bosca.db.migrations.CoreMigration
import io.mockk.mockk
import bosca.workops.migration.WorkOpsMigration
import io.mockk.mockk
import bosca.workops.model.calendar.CalendarEventBindingKind
import io.mockk.mockk
import bosca.workops.repository.CalendarBindingRepositoryImpl
import io.mockk.mockk
import bosca.workops.repository.RecurringCeremonyRepositoryImpl
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import org.junit.AfterClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase17IntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase17_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "workops-phase17-test",
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


    private val json = Json { ignoreUnknownKeys = true }

    private val bindingRepo = CalendarBindingRepositoryImpl()
    private val ceremonyRepo = RecurringCeremonyRepositoryImpl()
    private val bindingService = CalendarBindingServiceImpl(bindingRepo, json)
    private val ceremonyService = RecurringCeremonyServiceImpl(ceremonyRepo, json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        provides<bosca.sharedqueue.jobs.JobQueue>(name = "workops", singleton = true) { mockk(relaxed = true) }
        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(CoreMigration(), WorkOpsMigration())) }
            schemaInitialized = true
        }
        withDb {
            transaction {
                connection().useStatement("DELETE FROM workops.recurring_ceremony") { it.execute() }
                connection().useStatement("DELETE FROM workops.calendar_binding") { it.execute() }
            }
        }
    }

    private fun withDb(block: suspend () -> Unit) = runBlocking {
        val mgr = pool.connection()
        try {
            withContext(mgr.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { mgr.release() }
        }
    }

    private val ownerId: UUID = UUID.parse("11111111-1111-1111-1111-111111111111")

    @Test
    fun `bind and lookup round-trip per entity and per event`() = withDb {
        val taskId = UUID.parse("22222222-2222-2222-2222-222222222222")
        val eventId = UUID.parse("33333333-3333-3333-3333-333333333333")
        bindingService.bind("task", taskId, eventId, CalendarEventBindingKind.TASK_DUE)
        // Idempotent re-bind.
        bindingService.bind("task", taskId, eventId, CalendarEventBindingKind.TASK_DUE)
        val perEntity = bindingService.listForEntity("task", taskId)
        assertEquals(1, perEntity.size)
        assertEquals("workops.task.due", perEntity.single().kind)
        val perEvent = bindingService.listForEvent(eventId)
        assertEquals(1, perEvent.size)
        bindingService.unbind("task", taskId, CalendarEventBindingKind.TASK_DUE)
        assertTrue(bindingService.listForEntity("task", taskId).isEmpty())
    }

    @Test
    fun `unbindEvent clears every binding for that event id`() = withDb {
        val taskId = UUID.parse("44444444-4444-4444-4444-444444444444")
        val sprintId = UUID.parse("55555555-5555-5555-5555-555555555555")
        val eventId = UUID.parse("66666666-6666-6666-6666-666666666666")
        bindingService.bind("task", taskId, eventId, CalendarEventBindingKind.TASK_DUE)
        bindingService.bind("sprint", sprintId, eventId, CalendarEventBindingKind.SPRINT)
        assertEquals(2, bindingService.listForEvent(eventId).size)
        bindingService.unbindEvent(eventId)
        assertTrue(bindingService.listForEvent(eventId).isEmpty())
    }

    @Test
    fun `recurring ceremony create + archive cycle`() = withDb {
        val projectId = UUID.parse("77777777-7777-7777-7777-777777777777")
        val ceremony = ceremonyService.create(
            scope = "project",
            scopeId = projectId,
            name = "Daily standup",
            description = "9am daily",
            recurrenceRule = "FREQ=DAILY;BYHOUR=9",
            durationMinutes = 15,
            participants = buildJsonArray {
                add(buildJsonObject {
                    put("kind", JsonPrimitive("ProjectRole"))
                    put("roleId", JsonPrimitive("a0000000-0000-0000-0000-000000000001"))
                })
            },
            createdByProfileId = ownerId,
        )
        assertNotNull(ceremony.id)
        assertEquals(1, ceremonyService.listForScope("project", projectId).size)
        // Archive removes from active listings; the row remains.
        val archived = ceremonyService.archive(ceremony.id)
        assertNotNull(archived.archivedAt)
        assertEquals(0, ceremonyService.listForScope("project", projectId).size)
        // Direct getById still resolves the archived row.
        assertNotNull(ceremonyService.getById(ceremony.id))
    }

    @Test
    fun `binding kinds serialize to the spec strings`() = withDb {
        val taskId = UUID.parse("88888888-8888-8888-8888-888888888888")
        val eventId = UUID.parse("99999999-9999-9999-9999-999999999999")
        for (kind in CalendarEventBindingKind.entries) {
            bindingService.bind("task", taskId, eventId, kind)
        }
        val bindings = bindingService.listForEntity("task", taskId)
        // Every binding kind is a distinct row.
        val expected = setOf(
            "workops.task.due", "workops.task.span", "workops.sla.deadline",
            "workops.sprint", "workops.milestone", "workops.release",
            "workops.version_release", "workops.ceremony",
            "workops.transition_meeting",
        )
        assertEquals(expected, bindings.map { it.kind }.toSet())
    }
}
