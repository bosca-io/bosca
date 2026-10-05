package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.portal.Portal
import bosca.workops.model.portal.PortalAuthMode
import bosca.workops.model.portal.PortalToken
import bosca.workops.model.portal.PortalUser
import bosca.workops.model.portal.RequestType
import bosca.workops.model.task.Task
import bosca.workops.repository.PortalInsertParams
import bosca.workops.repository.PortalRepository
import bosca.workops.repository.PortalTokenRepository
import bosca.workops.repository.PortalUserRepository
import bosca.workops.repository.RequestTypeRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class PortalServiceTest {

    private val portals = mockk<PortalRepository>()
    private val requestTypes = mockk<RequestTypeRepository>()
    private val users = mockk<PortalUserRepository>()
    private val tokens = mockk<PortalTokenRepository>()
    private val tasks = mockk<TaskService>()
    private val portalId = UUID.random()
    private val projectId = UUID.random()
    private val requestType = RequestType(
        id = UUID.random(),
        portalId = portalId,
        name = "Support",
        taskTypeId = UUID.random(),
        defaultPriorityId = UUID.random(),
    )

    @Test
    fun `portal catalog delegates reads creation and request type mapping`() = runTest {
        val service = PortalServiceImpl(portals, requestTypes)
        val portal = portal(PortalAuthMode.MIXED)
        val create = CreatePortalInput(
            slug = portal.slug,
            name = portal.name,
            description = "Description",
            projectId = projectId,
            themeColorHex = "#123456",
            welcomeMarkdown = "Welcome",
            supportEmail = portal.supportEmail,
            authMode = PortalAuthMode.MIXED,
            anonymousAllowlistDomains = listOf("example.com"),
            slaPolicyId = UUID.random(),
            enabled = true,
        )
        val captured = slot<PortalInsertParams>()
        coEvery { portals.listAll() } returns listOf(portal)
        coEvery { portals.listForProject(projectId) } returns listOf(portal)
        coEvery { portals.getById(portalId) } returns portal
        coEvery { portals.getBySlug(portal.slug) } returns portal
        coEvery { portals.add(capture(captured)) } returns portal
        coEvery { requestTypes.listForPortal(portalId) } returns listOf(requestType)
        coEvery { requestTypes.add(any(), any(), any(), any(), any(), any(), any()) } returns requestType

        assertEquals(listOf(portal), service.list())
        assertEquals(listOf(portal), service.listForProject(projectId))
        assertSame(portal, service.getById(portalId))
        assertSame(portal, service.getBySlug(portal.slug))
        assertSame(portal, service.create(create))
        assertEquals("MIXED", captured.captured.authMode)
        assertEquals(listOf("example.com"), captured.captured.anonymousAllowlistDomains)
        assertEquals(listOf(requestType), service.listRequestTypes(portalId))
        assertSame(
            requestType,
            service.addRequestType(portalId, "Support", "Help", requestType.taskTypeId, requestType.defaultPriorityId, 2, "support"),
        )
    }

    @Test
    fun `submission validates portal request ownership auth and anonymous email domains`() = runTest {
        val service = submissionService()
        val input = submissionInput()
        coEvery { portals.getById(portalId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.submit(portalId, input) }

        coEvery { portals.getById(portalId) } returns portal(PortalAuthMode.MIXED, enabled = false)
        assertFailsWith<WorkOpsValidationException> { service.submit(portalId, input) }

        coEvery { portals.getById(portalId) } returns portal(PortalAuthMode.MIXED)
        coEvery { requestTypes.getById(requestType.id) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.submit(portalId, input) }

        coEvery { requestTypes.getById(requestType.id) } returns requestType.copy(portalId = UUID.random())
        assertFailsWith<WorkOpsValidationException> { service.submit(portalId, input) }

        coEvery { requestTypes.getById(requestType.id) } returns requestType
        coEvery { portals.getById(portalId) } returns portal(PortalAuthMode.AUTHENTICATED_ONLY)
        assertFailsWith<WorkOpsValidationException> { service.submit(portalId, input) }

        coEvery { portals.getById(portalId) } returns portal(
            PortalAuthMode.ALLOW_ANONYMOUS_VIA_EMAIL,
            domains = listOf("Example.COM"),
        )
        assertFailsWith<WorkOpsValidationException> {
            service.submit(portalId, input.copy(reporterEmail = "person@outside.test"))
        }
        assertFailsWith<WorkOpsValidationException> {
            service.submit(portalId, input.copy(reporterEmail = "missing-domain"))
        }
    }

    @Test
    fun `anonymous submission accepts exact and subdomains and stores only a token hash`() = runTest {
        val service = submissionService()
        val task = task()
        val portalUser = PortalUser(id = UUID.random(), portalId = portalId, email = "person@team.example.com")
        coEvery { portals.getById(portalId) } returns portal(
            PortalAuthMode.ALLOW_ANONYMOUS_VIA_EMAIL,
            domains = listOf("example.com"),
        )
        coEvery { requestTypes.getById(requestType.id) } returns requestType
        coEvery { users.upsert(any(), any(), any(), any()) } returns portalUser
        coEvery { tasks.create(any(), UUID.NIL, portalUser.id, portalUser.id) } returns task
        coEvery { tokens.add(any(), any(), any(), any()) } returns PortalToken(
            portalUserId = portalUser.id,
            taskId = task.id,
            tokenHash = "stored",
            expiresAt = OffsetDateTime.parse("2026-09-30T12:00:00Z"),
        )

        val result = service.submit(portalId, submissionInput("person@team.example.com"))
        val token = assertNotNull(result.magicLinkToken)
        assertEquals(64, token.length)
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        coVerify(exactly = 1) {
            tokens.add(portalUser.id, task.id, expectedHash, any())
        }

        val profiledUser = portalUser.copy(profileId = UUID.random(), email = "person@example.com")
        coEvery { users.upsert(any(), any(), any(), any()) } returns profiledUser
        coEvery { tasks.create(any(), UUID.NIL, profiledUser.profileId!!, profiledUser.profileId!!) } returns task
        assertNotNull(service.submit(portalId, submissionInput("person@example.com")).magicLinkToken)
    }

    @Test
    fun `authenticated mixed submission uses the reporter and does not mint a token`() = runTest {
        val service = submissionService()
        val profileId = UUID.random()
        val portalUser = PortalUser(id = UUID.random(), portalId = portalId, email = "person@outside.test", profileId = UUID.random())
        val task = task()
        coEvery { portals.getById(portalId) } returns portal(PortalAuthMode.MIXED, domains = listOf("example.com"))
        coEvery { requestTypes.getById(requestType.id) } returns requestType
        coEvery { users.upsert(portalId, "person@outside.test", profileId, "Person") } returns portalUser
        coEvery { tasks.create(any(), UUID.NIL, profileId, profileId) } returns task

        val result = service.submit(
            portalId,
            submissionInput("person@outside.test").copy(reporterProfileId = profileId),
        )

        assertNull(result.magicLinkToken)
        coVerify(exactly = 0) { tokens.add(any(), any(), any(), any()) }
    }

    @Test
    fun `token verification hashes the supplied secret`() = runTest {
        val token = PortalToken(
            portalUserId = UUID.random(),
            tokenHash = "hash",
            expiresAt = OffsetDateTime.parse("2026-09-30T12:00:00Z"),
        )
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("plain-secret".toByteArray())
            .joinToString("") { "%02x".format(it) }
        coEvery { tokens.getByHash(expected) } returns token

        assertSame(token, PortalTokenServiceImpl(tokens).verify("plain-secret"))
    }

    private fun submissionService() = PortalSubmissionServiceImpl(portals, requestTypes, users, tokens, tasks)

    private fun portal(
        authMode: PortalAuthMode,
        enabled: Boolean = true,
        domains: List<String> = emptyList(),
    ) = Portal(
        id = portalId,
        slug = "support",
        name = "Support",
        projectId = projectId,
        supportEmail = "support@example.com",
        authMode = authMode,
        anonymousAllowlistDomains = domains,
        enabled = enabled,
    )

    private fun submissionInput(email: String = "person@example.com") = PortalSubmissionInput(
        requestTypeId = requestType.id,
        summary = "Need help",
        description = "Details",
        reporterEmail = email,
        reporterDisplayName = "Person",
    )

    private fun task() = Task(
        id = UUID.random(),
        key = "SUP-1",
        projectId = projectId,
        taskTypeId = requestType.taskTypeId,
        statusId = UUID.random(),
        priorityId = requestType.defaultPriorityId!!,
        summary = "Need help",
        reporterProfileId = UUID.random(),
        createdByPrincipalId = UUID.NIL,
        modifiedByPrincipalId = UUID.NIL,
    )
}
