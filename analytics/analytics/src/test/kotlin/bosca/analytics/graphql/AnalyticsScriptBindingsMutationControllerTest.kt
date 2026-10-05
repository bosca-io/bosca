@file:OptIn(ExperimentalUuidApi::class)

package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsScriptBinding
import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.analytics.service.AnalyticsScriptBindingService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AnalyticsScriptBindingsMutationControllerTest {

    private val service = mockk<AnalyticsScriptBindingService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val auth = mockk<AuthenticationContext>()
    private val controller = AnalyticsScriptBindingsMutationController(service, groupEvaluator)

    private fun allow(allowed: Boolean) {
        every { groupEvaluator.hasGroup(auth, ANALYTICS_MANAGER_GROUP) } returns allowed
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        if (!allowed) every { groupEvaluator.throwUnauthorized() } throws SecurityException("Unauthorized access")
    }

    private fun binding(scriptId: Uuid) = AnalyticsScriptBinding(
        id = Uuid.random(),
        scriptId = scriptId,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    @Test
    fun `add defaults transform, enabled and ordinal when omitted`() = runTest {
        allow(true)
        val scriptId = Uuid.random()
        coEvery { service.add(scriptId, true, true, 0) } returns binding(scriptId)
        controller.add(auth, scriptId)
        coVerify(exactly = 1) { service.add(scriptId, true, true, 0) }
    }

    @Test
    fun `add passes explicit transform, enabled and ordinal`() = runTest {
        allow(true)
        val scriptId = Uuid.random()
        coEvery { service.add(scriptId, false, false, 5) } returns binding(scriptId)
        controller.add(auth, scriptId, transform = false, enabled = false, ordinal = 5)
        coVerify(exactly = 1) { service.add(scriptId, false, false, 5) }
    }

    @Test
    fun `add is denied for a non-manager`() = runTest {
        allow(false)
        assertFailsWith<SecurityException> { controller.add(auth, Uuid.random()) }
    }

    @Test
    fun `update passes the fields through`() = runTest {
        allow(true)
        val id = Uuid.random()
        val scriptId = Uuid.random()
        coEvery { service.update(id, scriptId, false, false, 2) } returns binding(scriptId)
        controller.update(auth, id, scriptId, transform = false, enabled = false, ordinal = 2)
        coVerify(exactly = 1) { service.update(id, scriptId, false, false, 2) }
    }

    @Test
    fun `update defaults optional fields and exposes its mutation marker`() = runTest {
        allow(true)
        val id = Uuid.random()
        val scriptId = Uuid.random()
        coEvery { service.update(id, scriptId, true, true, 0) } returns binding(scriptId)
        controller.update(auth, id, scriptId)
        coVerify { service.update(id, scriptId, true, true, 0) }
        assertSame(AnalyticsScriptBindingsMutation, AnalyticsScriptBindingsMutation)
    }

    @Test
    fun `update is denied for a non-manager`() = runTest {
        allow(false)
        assertFailsWith<SecurityException> { controller.update(auth, Uuid.random(), Uuid.random()) }
    }

    @Test
    fun `delete removes the binding and returns true`() = runTest {
        allow(true)
        val id = Uuid.random()
        coEvery { service.delete(id) } just Runs
        assertTrue(controller.delete(auth, id))
        coVerify(exactly = 1) { service.delete(id) }
    }

    @Test
    fun `delete is denied for a non-manager`() = runTest {
        allow(false)
        assertFailsWith<SecurityException> { controller.delete(auth, Uuid.random()) }
    }
}
