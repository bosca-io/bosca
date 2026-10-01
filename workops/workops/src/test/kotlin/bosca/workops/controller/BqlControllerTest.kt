package bosca.workops.controller

import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.bql.BqlError
import bosca.workops.model.bql.SavedFilter
import bosca.workops.model.bql.SavedFilterInput
import bosca.workops.model.spec.Spec
import bosca.workops.model.task.Task
import bosca.workops.service.SavedFilterService
import bosca.workops.service.SpecPermissionEvaluator
import bosca.workops.service.SpecQueryService
import bosca.workops.service.SpecSearchResult
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskQueryService
import bosca.workops.service.TaskSearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Permission-focused tests for BqlController.
 *
 * The previous behavior required admin-group membership to call searchTasks
 * or searchSpecs at all. After the fix, any authenticated user can call them,
 * but results are filtered via the entity permission evaluator. SavedFilter
 * mutations now require ownership (or admin) rather than admin-only.
 */
class BqlControllerTest {

    private val savedFilterService = mockk<SavedFilterService>()
    private val taskQueryService = mockk<TaskQueryService>()
    private val specQueryService = mockk<SpecQueryService>()
    private val taskPermissionEvaluator = mockk<TaskPermissionEvaluator>()
    private val specPermissionEvaluator = mockk<SpecPermissionEvaluator>()
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)

    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val otherProfileId = UUID.random()

    private fun authenticated(profile: UUID? = profileId): AuthenticationContext {
        val principal = Principal(id = principalId, primaryProfileId = profile)
        val groups = listOf(Group(id = UUID.random(), name = "users", description = "", type = GroupType.SYSTEM))
        return ImpersonatedAuthenticationContext(principal, groups)
    }

    // --- searchSpecs ---

    @Test
    fun `searchSpecs filters results through specPermissionEvaluator`() = runTest {
        val controller = queryController()
        val allSpecs = listOf(sampleSpec(), sampleSpec(), sampleSpec())
        val visibleSpecs = listOf(allSpecs[0])
        val result = SpecSearchResult(rows = allSpecs, freeTextTerms = emptyList())
        coEvery { specQueryService.search("status = todo", profileId, 0, 50) } returns result
        coEvery {
            specPermissionEvaluator.filterAllowed(any(), allSpecs, PermissionAction.VIEW)
        } returns visibleSpecs

        val filtered = controller.searchSpecs(authenticated(), "status = todo", 0, 50)

        assertEquals(visibleSpecs, filtered.rows)
    }

    // --- searchTasks ---

    @Test
    fun `searchTasks filters results through taskPermissionEvaluator`() = runTest {
        val controller = queryController()
        val allTasks = listOf(sampleTask(), sampleTask())
        val visibleTasks = listOf(allTasks[0])
        val result = TaskSearchResult(rows = allTasks, freeTextTerms = emptyList())
        coEvery { taskQueryService.search("status = todo", profileId, 0, 50) } returns result
        coEvery {
            taskPermissionEvaluator.filterAllowed(any(), allTasks, PermissionAction.VIEW)
        } returns visibleTasks

        val filtered = controller.searchTasks(authenticated(), "status = todo", 0, 50)

        assertEquals(visibleTasks, filtered.rows)
    }

    // --- savedFilter ownership check ---

    @Test
    fun `mine returns empty for unauthenticated caller`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        coEvery { authentication.principal() } returns null

        assertEquals(emptyList(), queryController().mine(authentication, 0, 25))
        coVerify(exactly = 0) { savedFilterService.listForOwner(any(), any(), any()) }
    }

    @Test
    fun `mine uses principal primary profile`() = runTest {
        val filters = listOf(sampleFilter(UUID.random(), profileId))
        coEvery { savedFilterService.listForOwner(profileId, 10, 25) } returns filters

        assertEquals(filters, queryController().mine(authenticated(), 10, 25))
    }

    @Test
    fun `mine falls back to first principal profile`() = runTest {
        val profile = mockk<Profile>()
        val filters = listOf(sampleFilter(UUID.random(), profileId))
        coEvery { profile.id } returns profileId
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile)
        coEvery { savedFilterService.listForOwner(profileId, 0, 25) } returns filters

        assertEquals(filters, queryController().mine(authenticated(profile = null), 0, 25))
    }

    @Test
    fun `mine returns empty when principal has no profile`() = runTest {
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()

        assertEquals(emptyList(), queryController().mine(authenticated(profile = null), 0, 25))
        coVerify(exactly = 0) { savedFilterService.listForOwner(any(), any(), any()) }
    }

    @Test
    fun `savedFilter returns null when filter is missing`() = runTest {
        val filterId = UUID.random()
        coEvery { savedFilterService.getById(filterId) } returns null

        assertNull(queryController().savedFilter(authenticated(), filterId))
    }

    @Test
    fun `savedFilter returns filter when caller is the owner`() = runTest {
        val controller = queryController()
        val filterId = UUID.random()
        val filter = sampleFilter(id = filterId, ownerProfileId = profileId)
        coEvery { savedFilterService.getById(filterId) } returns filter

        assertEquals(filter, controller.savedFilter(authenticated(), filterId))
    }

    @Test
    fun `savedFilter returns null when caller is not the owner and not admin`() = runTest {
        val controller = queryController()
        val filterId = UUID.random()
        val filter = sampleFilter(id = filterId, ownerProfileId = otherProfileId)
        coEvery { savedFilterService.getById(filterId) } returns filter
        coEvery { groupEvaluator.hasAdminGroup(any()) } returns false

        assertNull(controller.savedFilter(authenticated(), filterId))
    }

    @Test
    fun `savedFilter returns filter when caller is admin even if not owner`() = runTest {
        val controller = queryController()
        val filterId = UUID.random()
        val filter = sampleFilter(id = filterId, ownerProfileId = otherProfileId)
        coEvery { savedFilterService.getById(filterId) } returns filter
        coEvery { groupEvaluator.hasAdminGroup(any()) } returns true

        assertEquals(filter, controller.savedFilter(authenticated(), filterId))
    }

    @Test
    fun `validateBql returns parser errors`() = runTest {
        val errors = listOf(BqlError("expected value", 7, 8, "add a value"))
        coEvery { taskQueryService.validate("status =") } returns errors

        val result = queryController().validateBql(authenticated(), "status =")

        assertEquals(errors, result.errors)
        assertEquals(errors, BqlValidationResultTypeController().errors(result))
    }

    // --- savedFilter mutations (update/delete) verify ownership ---

    @Test
    fun `create requires an authenticated principal`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        coEvery { authentication.principal() } returns null

        assertFailsWith<IllegalStateException> {
            mutationController().create(authentication, SavedFilterInput("F", null, "status = todo"))
        }
        coVerify(exactly = 0) { savedFilterService.create(any(), any()) }
    }

    @Test
    fun `create uses principal primary profile`() = runTest {
        val input = SavedFilterInput("F", null, "status = todo")
        val filter = sampleFilter(UUID.random(), profileId)
        coEvery { savedFilterService.create(profileId, input) } returns filter

        assertEquals(filter, mutationController().create(authenticated(), input))
    }

    @Test
    fun `create falls back to first principal profile`() = runTest {
        val profile = mockk<Profile>()
        val input = SavedFilterInput("F", null, "status = todo")
        val filter = sampleFilter(UUID.random(), profileId)
        coEvery { profile.id } returns profileId
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile)
        coEvery { savedFilterService.create(profileId, input) } returns filter

        assertEquals(filter, mutationController().create(authenticated(profile = null), input))
    }

    @Test
    fun `create errors when principal has no profile`() = runTest {
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()

        assertFailsWith<IllegalStateException> {
            mutationController().create(
                authenticated(profile = null),
                SavedFilterInput("F", null, "status = todo"),
            )
        }
        coVerify(exactly = 0) { savedFilterService.create(any(), any()) }
    }

    @Test
    fun `update errors when filter is missing`() = runTest {
        val filterId = UUID.random()
        coEvery { savedFilterService.getById(filterId) } returns null

        assertFailsWith<IllegalStateException> {
            mutationController().update(
                authenticated(),
                filterId,
                SavedFilterInput("F", null, "status = todo"),
                0,
            )
        }
        coVerify(exactly = 0) { savedFilterService.update(any(), any(), any()) }
    }

    @Test
    fun `update requires an authenticated principal`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val filter = sampleFilter(UUID.random(), profileId)
        coEvery { savedFilterService.getById(filter.id) } returns filter
        coEvery { authentication.principal() } returns null

        assertFailsWith<IllegalStateException> {
            mutationController().update(
                authentication,
                filter.id,
                SavedFilterInput("F", null, "status = todo"),
                0,
            )
        }
        coVerify(exactly = 0) { savedFilterService.update(any(), any(), any()) }
    }

    @Test
    fun `update throws when caller does not own the filter and is not admin`() = runTest {
        val controller = mutationController()
        val filterId = UUID.random()
        val filter = sampleFilter(id = filterId, ownerProfileId = otherProfileId)
        coEvery { savedFilterService.getById(filterId) } returns filter
        coEvery { groupEvaluator.hasAdminGroup(any()) } returns false
        coEvery { groupEvaluator.throwUnauthorized() } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.update(authenticated(), filterId, SavedFilterInput("F", null, "status = todo"), 0L)
        }
        coVerify(exactly = 0) { savedFilterService.update(any(), any(), any()) }
    }

    @Test
    fun `delete throws when caller does not own the filter and is not admin`() = runTest {
        val controller = mutationController()
        val filterId = UUID.random()
        val filter = sampleFilter(id = filterId, ownerProfileId = otherProfileId)
        coEvery { savedFilterService.getById(filterId) } returns filter
        coEvery { groupEvaluator.hasAdminGroup(any()) } returns false
        coEvery { groupEvaluator.throwUnauthorized() } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.delete(authenticated(), filterId)
        }
        coVerify(exactly = 0) { savedFilterService.delete(any()) }
    }

    @Test
    fun `update succeeds when caller owns the filter`() = runTest {
        val controller = mutationController()
        val filterId = UUID.random()
        val filter = sampleFilter(id = filterId, ownerProfileId = profileId)
        val updated = filter.copy(name = "Updated")
        coEvery { savedFilterService.getById(filterId) } returns filter
        coEvery { savedFilterService.update(filterId, any(), 0L) } returns updated

        val result = controller.update(authenticated(), filterId, SavedFilterInput("Updated", null, "status = todo"), 0L)
        assertEquals(updated, result)
    }

    @Test
    fun `update succeeds for administrator who does not own filter`() = runTest {
        val filter = sampleFilter(UUID.random(), otherProfileId)
        val input = SavedFilterInput("Updated", null, "status = done")
        val updated = filter.copy(name = input.name, bqlSource = input.bqlSource)
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        coEvery { savedFilterService.getById(filter.id) } returns filter
        coEvery { groupEvaluator.hasAdminGroup(any()) } returns true
        coEvery { savedFilterService.update(filter.id, input, 0) } returns updated

        assertEquals(
            updated,
            mutationController().update(authenticated(profile = null), filter.id, input, 0),
        )
    }

    @Test
    fun `update resolves ownership through fallback profile`() = runTest {
        val profile = mockk<Profile>()
        val filter = sampleFilter(UUID.random(), profileId)
        val input = SavedFilterInput("Updated", null, "status = done")
        val updated = filter.copy(name = input.name, bqlSource = input.bqlSource)
        coEvery { profile.id } returns profileId
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile)
        coEvery { savedFilterService.getById(filter.id) } returns filter
        coEvery { savedFilterService.update(filter.id, input, 0) } returns updated

        assertEquals(
            updated,
            mutationController().update(authenticated(profile = null), filter.id, input, 0),
        )
    }

    @Test
    fun `delete removes owned filter`() = runTest {
        val filter = sampleFilter(UUID.random(), profileId)
        coEvery { savedFilterService.getById(filter.id) } returns filter
        coEvery { savedFilterService.delete(filter.id) } returns Unit

        assertEquals(true, mutationController().delete(authenticated(), filter.id))
        coVerify(exactly = 1) { savedFilterService.delete(filter.id) }
    }

    // --- helpers ---

    private fun queryController() = SavedFilterQueryController(
        service = savedFilterService,
        taskQueryService = taskQueryService,
        specQueryService = specQueryService,
        taskPermissionEvaluator = taskPermissionEvaluator,
        specPermissionEvaluator = specPermissionEvaluator,
        groupEvaluator = groupEvaluator,
        profileService = profileService,
    )

    private fun mutationController() = SavedFilterMutationController(
        service = savedFilterService,
        groupEvaluator = groupEvaluator,
        profileService = profileService,
    )

    private fun sampleSpec(): Spec = Spec(
        id = UUID.random(), key = "P-SPEC-${UUID.random()}", metadataId = UUID.random(),
        projectId = UUID.random(), statusId = UUID.random(), workflowId = UUID.random(),
        ownerProfileId = profileId,
        createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
    )

    private fun sampleTask(): Task = Task(
        id = UUID.random(), key = "P-${UUID.random()}", projectId = UUID.random(),
        taskTypeId = UUID.random(), statusId = UUID.random(), priorityId = UUID.random(),
        summary = "t", reporterProfileId = profileId,
        createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
    )

    private fun sampleFilter(id: UUID, ownerProfileId: UUID): SavedFilter {
        val now = bosca.serialization.OffsetDateTime.now()
        return SavedFilter(
            id = id,
            ownerProfileId = ownerProfileId,
            name = "F",
            description = null,
            bqlSource = "status = todo",
            parsedAst = JsonObject(emptyMap()),
            createdAt = now,
            modifiedAt = now,
            version = 0,
        )
    }
}
