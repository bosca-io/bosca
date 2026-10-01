package bosca.analytics.graphql

import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupFilter
import bosca.analytics.model.ErrorGroupStatus
import bosca.analytics.service.ErrorGroupService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AnalyticsErrorsControllerTest {

    private val service = mockk<ErrorGroupService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val controller = AnalyticsErrorsController(service, groupEvaluator)

    private val adminAuth = mockk<AuthenticationContext>()
    private val managerAuth = mockk<AuthenticationContext>()
    private val unauthorizedAuth = mockk<AuthenticationContext>()

    private val now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)
    private val sampleGroup = ErrorGroup(
        fingerprint = "fp-1",
        appId = "app-1",
        type = "java.lang.IllegalStateException",
        message = "boom",
        fatal = false,
        status = ErrorGroupStatus.OPEN,
        firstSeen = now,
        lastSeen = now,
        eventCount = 5L,
        sampleEventId = "c-1",
        sampleStack = "at com.example.Foo.bar(Foo.kt:42)",
        created = now,
        modified = now,
    )

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
    fun `groups returns connection for admin`() = runTest {
        allowAdmin()
        coEvery { service.list(any(), any(), any(), any(), 0L, 50) } returns listOf(sampleGroup)
        coEvery { service.count(any(), any(), any(), any()) } returns 1L

        val result = controller.groups(adminAuth)

        assertEquals(1L, result.total)
        assertEquals(listOf(sampleGroup), result.edges)
    }

    @Test
    fun `groups returns connection for manager`() = runTest {
        allowManager()
        coEvery { service.list(any(), any(), any(), any(), any(), any()) } returns listOf(sampleGroup)
        coEvery { service.count(any(), any(), any(), any()) } returns 1L

        val result = controller.groups(managerAuth)

        assertEquals(1L, result.total)
    }

    @Test
    fun `groups passes filters through to the service`() = runTest {
        allowAdmin()
        val filter = ErrorGroupFilter(appId = "app-1", status = ErrorGroupStatus.OPEN, fatal = true, search = "boom")
        coEvery { service.list("app-1", ErrorGroupStatus.OPEN, true, "boom", 5L, 25) } returns emptyList()
        coEvery { service.count("app-1", ErrorGroupStatus.OPEN, true, "boom") } returns 0L

        controller.groups(adminAuth, filter = filter, offset = 5L, limit = 25)

        coVerify { service.list("app-1", ErrorGroupStatus.OPEN, true, "boom", 5L, 25) }
        coVerify { service.count("app-1", ErrorGroupStatus.OPEN, true, "boom") }
    }

    @Test
    fun `groups uses default offset and limit when omitted`() = runTest {
        allowAdmin()
        coEvery { service.list(null, null, null, null, 0L, 50) } returns emptyList()
        coEvery { service.count(null, null, null, null) } returns 0L

        controller.groups(adminAuth)

        coVerify { service.list(null, null, null, null, 0L, 50) }
    }

    @Test
    fun `groups clamps the limit to the maximum page size`() = runTest {
        allowAdmin()
        coEvery { service.list(any(), any(), any(), any(), any(), 500) } returns emptyList()
        coEvery { service.count(any(), any(), any(), any()) } returns 0L

        controller.groups(adminAuth, limit = 99_999)

        coVerify { service.list(any(), any(), any(), any(), any(), 500) }
    }

    @Test
    fun `groups clamps the limit to at least 1`() = runTest {
        allowAdmin()
        coEvery { service.list(any(), any(), any(), any(), any(), 1) } returns emptyList()
        coEvery { service.count(any(), any(), any(), any()) } returns 0L

        controller.groups(adminAuth, limit = 0)

        coVerify { service.list(any(), any(), any(), any(), any(), 1) }
    }

    @Test
    fun `groups throws SecurityException for unauthorized callers`() = runTest {
        denyUnauthorized()
        assertFailsWith<SecurityException> {
            controller.groups(unauthorizedAuth)
        }
    }

    @Test
    fun `group by fingerprint returns the group when found`() = runTest {
        allowAdmin()
        coEvery { service.getByFingerprint("fp-1") } returns sampleGroup
        assertEquals(sampleGroup, controller.group(adminAuth, "fp-1"))
    }

    @Test
    fun `group by fingerprint returns null when missing`() = runTest {
        allowAdmin()
        coEvery { service.getByFingerprint("missing") } returns null
        assertNull(controller.group(adminAuth, "missing"))
    }

    @Test
    fun `group throws SecurityException for unauthorized callers`() = runTest {
        denyUnauthorized()
        assertFailsWith<SecurityException> {
            controller.group(unauthorizedAuth, "fp-1")
        }
    }
}
