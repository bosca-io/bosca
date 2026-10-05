@file:OptIn(ExperimentalUuidApi::class)

package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsScriptBinding
import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.analytics.service.AnalyticsScriptBindingService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AnalyticsScriptBindingsControllerTest {

    private val service = mockk<AnalyticsScriptBindingService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val auth = mockk<AuthenticationContext>()
    private val controller = AnalyticsScriptBindingsController(service, groupEvaluator)

    private fun allow(allowed: Boolean) {
        every { groupEvaluator.hasGroup(auth, ANALYTICS_MANAGER_GROUP) } returns allowed
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        if (!allowed) every { groupEvaluator.throwUnauthorized() } throws SecurityException("Unauthorized access")
    }

    private fun binding() = AnalyticsScriptBinding(
        id = Uuid.random(),
        scriptId = Uuid.random(),
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    @Test
    fun `all returns the bindings for a manager`() = runTest {
        allow(true)
        val all = listOf(binding())
        coEvery { service.list() } returns all
        assertSame(all, controller.all(auth))
    }

    @Test
    fun `all is denied for a non-manager`() = runTest {
        allow(false)
        assertFailsWith<SecurityException> { controller.all(auth) }
    }

    @Test
    fun `binding returns a single binding for a manager`() = runTest {
        allow(true)
        val b = binding()
        coEvery { service.get(b.id) } returns b
        assertEquals(b, controller.binding(auth, b.id))
    }

    @Test
    fun `binding is denied for a non-manager`() = runTest {
        allow(false)
        assertFailsWith<SecurityException> { controller.binding(auth, Uuid.random()) }
    }
}
