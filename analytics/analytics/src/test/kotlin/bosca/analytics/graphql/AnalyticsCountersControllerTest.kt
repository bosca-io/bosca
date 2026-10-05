package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsCounter
import bosca.analytics.model.AnalyticsCounterType
import bosca.analytics.service.CounterMetricsService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalyticsCountersControllerTest {

    private val service = mockk<CounterMetricsService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val controller = AnalyticsCountersController(service, groupEvaluator)

    private val adminAuth = mockk<AuthenticationContext>()
    private val managerAuth = mockk<AuthenticationContext>()
    private val unauthorizedAuth = mockk<AuthenticationContext>()

    private fun allowAdmin() {
        every { groupEvaluator.hasGroup(adminAuth, "analytics.manager") } returns false
        every { groupEvaluator.hasAdminGroup(adminAuth) } returns true
    }

    private fun allowManager() {
        every { groupEvaluator.hasGroup(managerAuth, "analytics.manager") } returns true
        every { groupEvaluator.hasAdminGroup(managerAuth) } returns false
    }

    private fun denyUnauthorized() {
        every { groupEvaluator.hasGroup(unauthorizedAuth, "analytics.manager") } returns false
        every { groupEvaluator.hasAdminGroup(unauthorizedAuth) } returns false
        every { groupEvaluator.throwUnauthorized() } throws SecurityException("Unauthorized access")
    }

    @Test
    fun `byId returns the resolved counter for an admin`() = runTest {
        allowAdmin()
        val counter = AnalyticsCounter("http.bosca.5xx", AnalyticsCounterType.COUNTER)
        coEvery { service.counter("http.bosca.5xx") } returns counter

        assertEquals(counter, controller.byId(adminAuth, "http.bosca.5xx"))
    }

    @Test
    fun `byId returns the resolved counter for a manager`() = runTest {
        allowManager()
        val counter = AnalyticsCounter("sessions.mobile", AnalyticsCounterType.GAUGE)
        coEvery { service.counter("sessions.mobile") } returns counter

        assertEquals(counter, controller.byId(managerAuth, "sessions.mobile"))
    }

    @Test
    fun `byId throws for an unknown counter`() = runTest {
        allowAdmin()
        coEvery { service.counter("widgets.foo") } returns null

        assertFailsWith<IllegalStateException> { controller.byId(adminAuth, "widgets.foo") }
    }

    @Test
    fun `byId throws SecurityException for unauthorized callers`() = runTest {
        denyUnauthorized()
        assertFailsWith<SecurityException> { controller.byId(unauthorizedAuth, "http.bosca.5xx") }
    }
}
