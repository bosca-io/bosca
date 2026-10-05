package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.service.AnalyticsQueryGitSyncService
import bosca.analytics.service.AnalyticsQueryService
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.SourceRefInput
import bosca.git.service.SourceRefService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(bosca.di.annotation.InternalDI::class)
class AnalyticsQueriesMutationControllerTest {

    private val queriesService = mockk<AnalyticsQueryService>()
    private val sourceRefServiceImpl = mockk<SourceRefService>()
    private val gitSyncServiceImpl = mockk<AnalyticsQueryGitSyncService>()
    private val sourceRefProvider = mockk<ObjectProvider<SourceRefService>>()
    private val gitSyncProvider = mockk<ObjectProvider<AnalyticsQueryGitSyncService>>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val permissionEvaluator = mockk<AnalyticsQueryPermissionEvaluator>()
    private val controller = AnalyticsQueriesMutationController(
        queriesService,
        sourceRefProvider,
        gitSyncProvider,
        groupEvaluator,
        permissionEvaluator,
    )

    private val auth = mockk<AuthenticationContext>()
    private val queryId = UUID.random()
    private val input = AnalyticsQueryInput(
        id = queryId,
        key = "k",
        name = "n",
        description = "d",
        query = "select 1",
    )
    private val updated = AnalyticsQuery(
        id = queryId,
        key = "k",
        name = "n",
        description = "d",
        query = "select 1",
        configuration = JsonNull,
    )

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun allowManager() {
        every { groupEvaluator.hasGroup(auth, "analytics.manager") } returns true
        every { groupEvaluator.hasAdminGroup(auth) } returns false
    }

    private fun denyAuthorization() {
        every { groupEvaluator.hasGroup(auth, "analytics.manager") } returns false
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.throwUnauthorized() } throws SecurityException("denied")
    }

    private fun allowAdmin() {
        every { groupEvaluator.hasGroup(auth, "analytics.manager") } returns false
        every { groupEvaluator.hasAdminGroup(auth) } returns true
    }

    @Test
    fun `add creates query and stores available source ref`() = runTest {
        allowManager()
        val ref = mockk<SourceRefInput>()
        coEvery { queriesService.addQuery(input) } returns updated
        every { sourceRefProvider.exists } returns true
        coEvery { sourceRefProvider.get() } returns sourceRefServiceImpl
        coEvery { sourceRefServiceImpl.setQuerySourceRef(queryId, ref) } returns mockk()

        assertSame(updated, controller.add(auth, input, ref))
        coVerify { sourceRefServiceImpl.setQuerySourceRef(queryId, ref) }
    }

    @Test
    fun `add skips absent source integration and allows administrators`() = runTest {
        allowAdmin()
        coEvery { queriesService.addQuery(input) } returns updated
        every { sourceRefProvider.exists } returns false

        assertSame(updated, controller.add(auth, input, mockk()))
        coVerify(exactly = 0) { sourceRefProvider.get() }

        assertSame(updated, controller.add(auth, input, null))
    }

    @Test
    fun `edit pushes back to git when author info is provided and a sourceRef exists`() = runTest {
        allowManager()
        coEvery { queriesService.editQuery(input) } returns updated
        every { sourceRefProvider.exists } returns false
        every { gitSyncProvider.exists } returns true
        coEvery { gitSyncProvider.get() } returns gitSyncServiceImpl
        coEvery {
            gitSyncServiceImpl.pushToGit(queryId, "Author", "author@example.com")
        } returns "sha-1"

        controller.edit(auth, input, sourceRef = null, authorName = "Author", authorEmail = "author@example.com")

        coVerify { gitSyncServiceImpl.pushToGit(queryId, "Author", "author@example.com") }
    }

    @Test
    fun `edit skips push when authorName is missing`() = runTest {
        allowManager()
        coEvery { queriesService.editQuery(input) } returns updated
        every { sourceRefProvider.exists } returns false
        every { gitSyncProvider.exists } returns true

        controller.edit(auth, input, sourceRef = null, authorName = null, authorEmail = "x@example.com")

        coVerify(exactly = 0) { gitSyncProvider.get() }
    }

    @Test
    fun `edit skips push when authorEmail is missing`() = runTest {
        allowManager()
        coEvery { queriesService.editQuery(input) } returns updated
        every { sourceRefProvider.exists } returns false
        every { gitSyncProvider.exists } returns true

        controller.edit(auth, input, sourceRef = null, authorName = "Author", authorEmail = null)

        coVerify(exactly = 0) { gitSyncProvider.get() }
    }

    @Test
    fun `edit skips push when gitSyncService is unavailable`() = runTest {
        allowManager()
        coEvery { queriesService.editQuery(input) } returns updated
        every { sourceRefProvider.exists } returns false
        every { gitSyncProvider.exists } returns false

        controller.edit(auth, input, sourceRef = null, authorName = "Author", authorEmail = "x@example.com")

        coVerify(exactly = 0) { gitSyncProvider.get() }
    }

    @Test
    fun `edit succeeds even if pushToGit throws`() = runTest {
        allowManager()
        coEvery { queriesService.editQuery(input) } returns updated
        every { sourceRefProvider.exists } returns false
        every { gitSyncProvider.exists } returns true
        coEvery { gitSyncProvider.get() } returns gitSyncServiceImpl
        coEvery {
            gitSyncServiceImpl.pushToGit(queryId, "Author", "x@example.com")
        } throws RuntimeException("git is unreachable")

        val result = controller.edit(
            auth, input, sourceRef = null,
            authorName = "Author", authorEmail = "x@example.com",
        )

        assertSame(updated, result)
    }

    @Test
    fun `edit re-throws CancellationException from pushToGit`() = runTest {
        allowManager()
        coEvery { queriesService.editQuery(input) } returns updated
        every { sourceRefProvider.exists } returns false
        every { gitSyncProvider.exists } returns true
        coEvery { gitSyncProvider.get() } returns gitSyncServiceImpl
        coEvery {
            gitSyncServiceImpl.pushToGit(queryId, "Author", "x@example.com")
        } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            controller.edit(
                auth, input, sourceRef = null,
                authorName = "Author", authorEmail = "x@example.com",
            )
        }
    }

    @Test
    fun `edit sets sourceRef when provided and propagates the result`() = runTest {
        allowManager()
        val ref = mockk<SourceRefInput>()
        coEvery { queriesService.editQuery(input) } returns updated
        every { sourceRefProvider.exists } returns true
        coEvery { sourceRefProvider.get() } returns sourceRefServiceImpl
        coEvery { sourceRefServiceImpl.setQuerySourceRef(queryId, ref) } returns mockk()
        every { gitSyncProvider.exists } returns false

        val result = controller.edit(auth, input, sourceRef = ref)

        assertSame(updated, result)
        coVerify { sourceRefServiceImpl.setQuerySourceRef(queryId, ref) }
    }

    @Test
    fun `edit skips a provided source ref when integration is absent`() = runTest {
        allowManager()
        coEvery { queriesService.editQuery(input) } returns updated
        every { sourceRefProvider.exists } returns false
        every { gitSyncProvider.exists } returns false

        assertSame(updated, controller.edit(auth, input, sourceRef = mockk()))
        coVerify(exactly = 0) { sourceRefProvider.get() }
    }

    @Test
    fun `edit refuses unauthorized callers`() = runTest {
        denyAuthorization()

        assertFailsWith<SecurityException> {
            controller.edit(auth, input, sourceRef = null)
        }
    }

    @Test
    fun `delete delegates after manager authorization`() = runTest {
        allowManager()
        coJustRun { queriesService.deleteQueryById(queryId) }

        assertTrue(controller.delete(auth, queryId))
        coVerify { queriesService.deleteQueryById(queryId) }
    }

    @Test
    fun `permission mutations verify manage permission and delegate`() = runTest {
        val permission = PermissionInput(PermissionAction.VIEW, queryId, UUID.random())
        coEvery { queriesService.getQueryById(queryId) } returns updated
        coEvery { permissionEvaluator.verifyAllowed(auth, updated, PermissionAction.MANAGE) } returns Unit
        coJustRun { queriesService.addPermission(permission) }
        coJustRun { queriesService.deletePermission(permission) }

        assertEquals(permission.groupId, controller.addPermission(auth, permission).groupId)
        assertEquals(permission.action, controller.deletePermission(auth, permission).action)
    }

    @Test
    fun `refresh rejects uncached queries`() = runTest {
        coEvery { queriesService.getQueryById(queryId) } returns updated
        coEvery { permissionEvaluator.verifyAllowed(auth, updated, PermissionAction.EXECUTE) } returns Unit

        assertFalse(controller.refresh(auth, queryId))
    }

    @Test
    fun `refresh enqueues cached queries`() = runTest {
        val cached = updated.copy(refreshIntervalSeconds = 60)
        val queue = mockk<JobQueue>()
        provides<JobQueue>(name = "analytics") { queue }
        coEvery { queue.enqueue(any()) } returns UUID.random()
        coEvery { queriesService.getQueryById(queryId) } returns cached
        coEvery { permissionEvaluator.verifyAllowed(auth, cached, PermissionAction.EXECUTE) } returns Unit

        assertTrue(controller.refresh(auth, queryId))
        coVerify(exactly = 1) { queue.enqueue(any()) }
    }

    @Test
    fun `explicit git push returns null when integration is unavailable`() = runTest {
        allowManager()
        every { gitSyncProvider.exists } returns false

        assertEquals(null, controller.pushToGit(auth, queryId, "Author", "author@example.com"))
        assertEquals(0, controller.pushAllToGit(auth, UUID.random(), "Author", "author@example.com"))
    }

    @Test
    fun `explicit git push delegates single and bulk operations`() = runTest {
        allowManager()
        val repositoryId = UUID.random()
        every { gitSyncProvider.exists } returns true
        coEvery { gitSyncProvider.get() } returns gitSyncServiceImpl
        coEvery { gitSyncServiceImpl.pushToGit(queryId, "Author", "author@example.com") } returns "sha"
        coEvery { gitSyncServiceImpl.pushAllToGit(repositoryId, "Author", "author@example.com") } returns 4

        assertEquals("sha", controller.pushToGit(auth, queryId, "Author", "author@example.com"))
        assertEquals(4, controller.pushAllToGit(auth, repositoryId, "Author", "author@example.com"))
    }
}
