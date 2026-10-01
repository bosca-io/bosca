package bosca.analytics.graphql

import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupStatus
import bosca.analytics.service.ErrorGroupAnalysisService
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AnalyticsErrorsMutationControllerTest {

    private val errorGroupService = mockk<ErrorGroupService>()
    private val analysisService = mockk<ErrorGroupAnalysisService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val controller = AnalyticsErrorsMutationController(errorGroupService, analysisService, groupEvaluator)

    private val managerAuth = mockk<AuthenticationContext>(relaxed = true)
    private val adminAuth = mockk<AuthenticationContext>(relaxed = true)
    private val unauthorizedAuth = mockk<AuthenticationContext>(relaxed = true)

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

    private fun allowManager() {
        every { managerAuth.principal() } returns mockk(relaxed = true)
        every { groupEvaluator.hasGroup(managerAuth, "analytics.manager") } returns true
        every { groupEvaluator.hasAdminGroup(managerAuth) } returns false
    }

    private fun allowAdmin() {
        every { adminAuth.principal() } returns null
        every { groupEvaluator.hasGroup(adminAuth, "analytics.manager") } returns false
        every { groupEvaluator.hasAdminGroup(adminAuth) } returns true
    }

    private fun denyUnauthorized() {
        every { groupEvaluator.hasGroup(unauthorizedAuth, "analytics.manager") } returns false
        every { groupEvaluator.hasAdminGroup(unauthorizedAuth) } returns false
        every { groupEvaluator.throwUnauthorized() } throws SecurityException("Unauthorized access")
    }

    // --- setStatus ---

    @Test
    fun `setStatus delegates to the service for managers`() = runTest {
        allowManager()
        val resolved = sampleGroup.copy(status = ErrorGroupStatus.RESOLVED)
        coEvery { errorGroupService.setStatus("fp-1", ErrorGroupStatus.RESOLVED) } returns resolved
        assertEquals(resolved, controller.setStatus(managerAuth, "fp-1", ErrorGroupStatus.RESOLVED))
    }

    @Test
    fun `setStatus succeeds for admins`() = runTest {
        allowAdmin()
        coEvery { errorGroupService.setStatus("fp-1", ErrorGroupStatus.IGNORED) } returns sampleGroup.copy(status = ErrorGroupStatus.IGNORED)
        controller.setStatus(adminAuth, "fp-1", ErrorGroupStatus.IGNORED)
        coVerify { errorGroupService.setStatus("fp-1", ErrorGroupStatus.IGNORED) }
    }

    @Test
    fun `setStatus throws for unauthorized users`() = runTest {
        denyUnauthorized()
        assertFailsWith<SecurityException> {
            controller.setStatus(unauthorizedAuth, "fp-1", ErrorGroupStatus.RESOLVED)
        }
    }

    // --- assign ---

    @Test
    fun `assign delegates to the service for managers`() = runTest {
        allowManager()
        val assigneeId = Uuid.random()
        val assigned = sampleGroup.copy(assigneeId = assigneeId)
        coEvery { errorGroupService.assign("fp-1", assigneeId) } returns assigned
        assertEquals(assigned, controller.assign(managerAuth, "fp-1", assigneeId))
    }

    @Test
    fun `assign with null clears the assignee`() = runTest {
        allowManager()
        every { managerAuth.principal() } returns null
        coEvery { errorGroupService.assign("fp-1", null) } returns sampleGroup
        controller.assign(managerAuth, "fp-1", null)
        coVerify { errorGroupService.assign("fp-1", null) }

        controller.assign(managerAuth, "fp-1")
        coVerify(exactly = 2) { errorGroupService.assign("fp-1", null) }
    }

    @Test
    fun `assign throws for unauthorized users`() = runTest {
        denyUnauthorized()
        assertFailsWith<SecurityException> {
            controller.assign(unauthorizedAuth, "fp-1", null)
        }
    }

    // --- analyze ---

    @Test
    fun `analyze delegates to the analysis service`() = runTest {
        allowManager()
        val analyzed = sampleGroup.copy(aiSummary = "root cause: x")
        coEvery { analysisService.analyze("fp-1") } returns analyzed
        assertEquals(analyzed, controller.analyze(managerAuth, "fp-1"))
        every { managerAuth.principal() } returns null
        assertEquals(analyzed, controller.analyze(managerAuth, "fp-1"))
    }

    @Test
    fun `analyze throws for unauthorized users`() = runTest {
        denyUnauthorized()
        assertFailsWith<SecurityException> {
            controller.analyze(unauthorizedAuth, "fp-1")
        }
    }
}
