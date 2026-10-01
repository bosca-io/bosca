package bosca.experimentation.configuration

import bosca.di.ObjectProvider
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ConfigurationTest {

    private val configuration = Configuration()

    @Test
    fun `configuration uses safe defaults when experimentation block is absent`() {
        val application = mockk<BoscaApplication>()
        every { application.environment } returns BoscaApplication.Environment(
            ApplicationConfig.load("".byteInputStream()),
        )

        assertEquals(ExperimentationConfig(), configuration.experimentationConfig(application))
    }

    @Test
    fun `configuration decodes an explicit experimentation block`() {
        val source = ApplicationConfig.load(
            """
            experimentation:
              eventsTable: custom.analytics_events
              assignmentsTable: custom.flag_assignments
            """.trimIndent().byteInputStream(),
        )
        val application = mockk<BoscaApplication>()
        every { application.environment } returns BoscaApplication.Environment(source)

        val result = configuration.experimentationConfig(application)

        assertEquals("custom.analytics_events", result.safeEventsTable)
        assertEquals("custom.flag_assignments", result.safeAssignmentsTable)
        assertEquals(ExperimentationConfig.DEFAULT_POSTGRES_CATALOG, result.safePostgresCatalog)
    }

    @Test
    fun `configuration scopes the operational catalog and ignores an unsafe one`() {
        assertEquals("sitea_bosca", ExperimentationConfig(postgresCatalog = " sitea_bosca ").safePostgresCatalog)
        assertEquals(
            ExperimentationConfig.DEFAULT_POSTGRES_CATALOG,
            ExperimentationConfig(postgresCatalog = "bosca; drop table").safePostgresCatalog,
        )
        assertEquals(
            ExperimentationConfig.DEFAULT_POSTGRES_CATALOG,
            ExperimentationConfig(postgresCatalog = "sitea.bosca").safePostgresCatalog,
        )
    }

    @Test
    fun `configuration replaces unsafe warehouse tables with the defaults instead of failing startup`() {
        val unsafe = ExperimentationConfig(
            eventsTable = "warehouse.bosca.events; drop table x",
            assignmentsTable = "a.b.c.d",
        )
        assertEquals(ExperimentationConfig.DEFAULT_EVENTS_TABLE, unsafe.safeEventsTable)
        assertEquals(ExperimentationConfig.DEFAULT_ASSIGNMENTS_TABLE, unsafe.safeAssignmentsTable)

        val trimmed = ExperimentationConfig(eventsTable = " sitea_warehouse.bosca.events ", assignmentsTable = " sitea_bosca.experimentation.assignments ")
        assertEquals("sitea_warehouse.bosca.events", trimmed.safeEventsTable)
        assertEquals("sitea_bosca.experimentation.assignments", trimmed.safeAssignmentsTable)
    }

    @Test
    fun `configuration exposes the registered migration`() {
        assertIs<ExperimentationMigration>(configuration.migration())
    }

    @Test
    fun `configuration creates the named queue and runner`() {
        val factory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>()
        every { factory.create(JobQueueNames.experimentationQueue) } returns queue

        assertEquals(queue, configuration.experimentationJobQueue(factory))
        assertNotNull(
            configuration.experimentationJobQueueRunner(
                queue = queue,
                distributedLockFactory = mockk<DistributedLockFactory>(),
                errorCapture = mockk<ObjectProvider<ErrorCapture>>(),
            ),
        )
    }
}
