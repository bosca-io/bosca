package bosca.git.graphql

import bosca.di.ObjectProvider
import bosca.git.model.BranchProtectionRule
import bosca.git.model.BranchProtectionRuleInput
import bosca.git.model.CreateRepositoryInput
import bosca.git.model.PullRequest
import bosca.git.model.Repository
import bosca.git.model.UpdateRepositoryInput
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.BranchProtectionService
import bosca.git.service.PullRequestService
import bosca.git.service.QuerySourceBackfill
import bosca.git.service.RepositoryLifecycleService
import bosca.git.service.RepositoryService
import bosca.git.service.RepositoryWriteBusyException
import bosca.git.service.RepositoryWriteService
import bosca.git.service.ScriptSourceBackfill
import bosca.git.service.SourceRefService
import bosca.git.service.WebhookService
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for [GitRepositoryMutation]: every mutation resolves its target,
 * enforces the correct permission, and delegates to the owning service; each
 * also rejects a missing target with [NoSuchElementException].
 */
class GitRepositoryMutationTest {

    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)
    private val branchProtectionService = mockk<BranchProtectionService>(relaxed = true)
    private val pullRequestService = mockk<PullRequestService>(relaxed = true)
    private val webhookService = mockk<WebhookService>(relaxed = true)
    private val sourceRefService = mockk<SourceRefService>(relaxed = true)
    private val writeService = mockk<RepositoryWriteService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>(relaxed = true)
    private val lifecycleService = mockk<RepositoryLifecycleService>(relaxed = true)
    private val auth = mockk<AuthenticationContext>(relaxed = true)

    private val querySourceBackfill = mockk<ObjectProvider<QuerySourceBackfill>>(relaxed = true)
    private val scriptSourceBackfill = mockk<ObjectProvider<ScriptSourceBackfill>>(relaxed = true)

    private val mutation = GitRepositoryMutation(
        repositoryService, permissionEvaluator, branchProtectionService, pullRequestService,
        webhookService, sourceRefService, writeService,
        querySourceBackfill, scriptSourceBackfill,
        groupEvaluator, profileService, profilePermissionEvaluator, lifecycleService,
    )

    private val id = UUID.random()
    private val repo = Repository(id = id, slug = "r", name = "R", ownerId = UUID.random(), visibility = bosca.git.model.Visibility.PRIVATE)

    private fun repoFound() {
        coEvery { repositoryService.findById(id) } returns repo
        coEvery { repositoryService.findByIdIncludingDeleted(id) } returns repo
    }

    @Test
    fun `updateRepository verifies MANAGE and delegates`() = runTest {
        repoFound()
        val input = mockk<UpdateRepositoryInput>(relaxed = true)
        mutation.updateRepository(auth, id, input)
        coVerify { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.MANAGE) }
        coVerify { repositoryService.update(id, input) }
    }

    @Test
    fun `updateRepository throws when the repository is missing`() = runTest {
        coEvery { repositoryService.findById(id) } returns null
        assertFailsWith<NoSuchElementException> { mutation.updateRepository(auth, id, mockk(relaxed = true)) }
    }

    @Test
    fun `archive, delete, transfer delegate to their service`() = runTest {
        repoFound()
        mutation.archiveRepository(auth, id)
        mutation.deleteRepository(auth, id)
        val newOwner = UUID.random()
        mutation.transferRepository(auth, id, newOwner)
        coVerify { repositoryService.archive(id) }
        coVerify { repositoryService.delete(id) }
        coVerify { repositoryService.transfer(id, newOwner) }
    }

    @Test
    fun `renameRepository verifies MANAGE and delegates`() = runTest {
        repoFound()
        mutation.renameRepository(auth, id, "new-slug")
        coVerify { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.MANAGE) }
        coVerify { repositoryService.rename(id, "new-slug") }
    }

    @Test
    fun `restoreRepository uses the including-deleted lookup`() = runTest {
        repoFound()
        mutation.restoreRepository(auth, id)
        coVerify { repositoryService.findByIdIncludingDeleted(id) }
        coVerify { repositoryService.restore(id) }
    }

    @Test
    fun `runRepositoryGc and repair invoke the lifecycle service`() = runTest {
        repoFound()
        coEvery { lifecycleService.runGc(id) } returns true
        coEvery { lifecycleService.repair(id) } returns true
        mutation.runRepositoryGc(auth, id)
        mutation.runRepositoryRepair(auth, id)
        coVerify { lifecycleService.runGc(id) }
        coVerify { lifecycleService.repair(id) }
    }

    @Test
    fun `runRepositoryGc and repair fail loudly when the cycle is skipped`() = runTest {
        repoFound()
        coEvery { lifecycleService.runGc(id) } returns false
        coEvery { lifecycleService.repair(id) } returns false
        // A skipped cycle must surface as an error, not report success while
        // nothing actually ran.
        assertFailsWith<RepositoryWriteBusyException> { mutation.runRepositoryGc(auth, id) }
        assertFailsWith<RepositoryWriteBusyException> { mutation.runRepositoryRepair(auth, id) }
    }

    @Test
    fun `createRepository requires the git creator group`() = runTest {
        coEvery { groupEvaluator.hasGroup(auth, "git.creator") } returns false
        coEvery { groupEvaluator.hasAdminGroup(auth) } returns false
        val input = mockk<CreateRepositoryInput>(relaxed = true)
        coEvery { groupEvaluator.throwUnauthorized() } throws SecurityException("no")
        assertFailsWith<SecurityException> { mutation.createRepository(auth, input) }
    }

    @Test
    fun `createRepository succeeds for an admin and verifies owner MANAGE`() = runTest {
        coEvery { groupEvaluator.hasGroup(auth, "git.creator") } returns false
        coEvery { groupEvaluator.hasAdminGroup(auth) } returns true
        val ownerId = UUID.random()
        val input = mockk<CreateRepositoryInput>(relaxed = true)
        coEvery { input.ownerId } returns ownerId
        val owner = mockk<Profile>(relaxed = true)
        coEvery { profileService.getById(ownerId) } returns owner
        mutation.createRepository(auth, input)
        coVerify { profilePermissionEvaluator.verifyAllowed(auth, owner, PermissionAction.MANAGE) }
        coVerify { repositoryService.create(input) }
    }

    @Test
    fun `addPermission and removePermission return true after MANAGE`() = runTest {
        val perm = mockk<PermissionInput>(relaxed = true)
        coEvery { perm.entityId } returns id
        repoFound()
        assertTrue(mutation.addPermission(auth, perm))
        assertTrue(mutation.removePermission(auth, perm))
        coVerify { repositoryService.addPermission(id, perm.groupId, perm.action) }
        coVerify { repositoryService.removePermission(id, perm.groupId, perm.action) }
    }

    @Test
    fun `createBranchProtectionRule stamps the repository id`() = runTest {
        repoFound()
        val input = BranchProtectionRuleInput(
            pattern = "main",
            requirePullRequest = true,
            requireStatusChecks = listOf("ci"),
        )
        mutation.createBranchProtectionRule(auth, id, input)
        coVerify {
            branchProtectionService.create(
                match {
                    it.repositoryId == id &&
                        it.pattern == "main" &&
                        it.requirePullRequest &&
                        it.requireStatusChecks == listOf("ci")
                }
            )
        }
    }

    @Test
    fun `deleteBranchProtectionRule throws when the rule is missing`() = runTest {
        coEvery { branchProtectionService.findById(id) } returns null
        assertFailsWith<NoSuchElementException> { mutation.deleteBranchProtectionRule(auth, id) }
    }

    @Test
    fun `pull request mutations resolve the PR then the repository`() = runTest {
        val prId = UUID.random()
        val pr = mockk<PullRequest>(relaxed = true)
        coEvery { pr.repositoryId } returns id
        coEvery { pullRequestService.findById(prId) } returns pr
        repoFound()

        mutation.closePullRequest(auth, prId)
        mutation.reopenPullRequest(auth, prId)
        mutation.markPullRequestReady(auth, prId)

        coVerify { pullRequestService.close(prId) }
        coVerify { pullRequestService.reopen(prId) }
        coVerify { pullRequestService.markReady(prId) }
    }

    @Test
    fun `closePullRequest throws when the pull request is missing`() = runTest {
        val prId = UUID.random()
        coEvery { pullRequestService.findById(prId) } returns null
        assertFailsWith<NoSuchElementException> { mutation.closePullRequest(auth, prId) }
    }

    /** Stubs an authenticated principal on [auth] and returns its concrete id for verification. */
    private fun principal(): UUID {
        val pid = UUID.random()
        val p = mockk<bosca.security.model.AuthenticatedPrincipal>(relaxed = true)
        io.mockk.every { p.id } returns pid
        io.mockk.every { auth.principal() } returns p
        return pid
    }

    /** Stubs the authenticated principal's primary profile and returns the profile id. */
    private fun actorProfile(): UUID {
        principal()
        val profileId = UUID.random()
        val profile = mockk<Profile>(relaxed = true)
        io.mockk.every { profile.id } returns profileId
        coEvery { profileService.getPrimaryProfile(any()) } returns profile
        return profileId
    }

    private fun prFound(prId: UUID): PullRequest {
        val pr = mockk<PullRequest>(relaxed = true)
        coEvery { pr.repositoryId } returns id
        coEvery { pullRequestService.findById(prId) } returns pr
        repoFound()
        return pr
    }

    @Test
    fun `createPullRequest requires an authenticated principal`() = runTest {
        val input = mockk<bosca.git.model.CreatePullRequestInput>(relaxed = true)
        coEvery { input.repositoryId } returns id
        repoFound()
        io.mockk.every { auth.principal() } returns null
        assertFailsWith<SecurityException> { mutation.createPullRequest(auth, input) }
    }

    @Test
    fun `createPullRequest requires the authenticated principal to have a profile`() = runTest {
        val input = mockk<bosca.git.model.CreatePullRequestInput>(relaxed = true)
        coEvery { input.repositoryId } returns id
        repoFound()
        principal()
        coEvery { profileService.getPrimaryProfile(any()) } returns null

        assertFailsWith<SecurityException> { mutation.createPullRequest(auth, input) }
        coVerify(exactly = 0) { pullRequestService.create(any(), any()) }
    }

    @Test
    fun `createPullRequest delegates with the author id`() = runTest {
        val input = mockk<bosca.git.model.CreatePullRequestInput>(relaxed = true)
        coEvery { input.repositoryId } returns id
        repoFound()
        val pid = actorProfile()
        mutation.createPullRequest(auth, input)
        coVerify { pullRequestService.create(input, pid) }
    }

    @Test
    fun `updatePullRequest verifies EDIT and delegates`() = runTest {
        val prId = UUID.random()
        prFound(prId)
        val input = mockk<bosca.git.model.UpdatePullRequestInput>(relaxed = true)
        mutation.updatePullRequest(auth, prId, input)
        coVerify { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.EDIT) }
        coVerify { pullRequestService.update(prId, input) }
    }

    @Test
    fun `mergePullRequest requires a principal and delegates`() = runTest {
        val prId = UUID.random()
        prFound(prId)
        io.mockk.every { auth.principal() } returns null
        assertFailsWith<SecurityException> { mutation.mergePullRequest(auth, prId, bosca.git.model.MergeStrategy.MERGE_COMMIT) }

        val pid = actorProfile()
        mutation.mergePullRequest(auth, prId, bosca.git.model.MergeStrategy.MERGE_COMMIT)
        coVerify { pullRequestService.merge(prId, bosca.git.model.MergeStrategy.MERGE_COMMIT, pid, any(), any()) }
    }

    @Test
    fun `mergePullRequest requires the authenticated principal to have a profile`() = runTest {
        val prId = UUID.random()
        prFound(prId)
        principal()
        coEvery { profileService.getPrimaryProfile(any()) } returns null

        assertFailsWith<SecurityException> {
            mutation.mergePullRequest(auth, prId, bosca.git.model.MergeStrategy.MERGE_COMMIT)
        }
        coVerify(exactly = 0) { pullRequestService.merge(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `mergePullRequestWithDependencies verifies edit permission for the complete plan before delegating`() = runTest {
        val prId = UUID.random()
        val dependencyRepository = repo.copy(id = UUID.random(), slug = "dependency")
        val dependency = PullRequest(
            id = UUID.random(), repositoryId = dependencyRepository.id, number = 1,
            title = "Dependency", authorId = UUID.random(), sourceBranch = "feature", targetBranch = "main",
        )
        val parent = dependency.copy(id = prId, repositoryId = id, number = 2, title = "Parent")
        coEvery { pullRequestService.getMergePlan(prId) } returns listOf(dependency, parent)
        coEvery { repositoryService.findById(dependencyRepository.id) } returns dependencyRepository
        repoFound()
        coEvery {
            permissionEvaluator.filterAllowed(
                auth, listOf(dependencyRepository, repo), PermissionAction.VIEW,
            )
        } returns listOf(dependencyRepository, repo)
        val actorId = actorProfile()

        mutation.mergePullRequestWithDependencies(auth, prId, bosca.git.model.MergeStrategy.SQUASH)

        coVerify { permissionEvaluator.verifyAllowed(auth, dependencyRepository, PermissionAction.EDIT) }
        coVerify { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.EDIT) }
        coVerify {
            pullRequestService.mergeWithDependencies(
                prId, bosca.git.model.MergeStrategy.SQUASH, actorId, any(), any(),
                listOf(dependency.id, parent.id),
            )
        }
    }

    @Test
    fun `mergePullRequestWithDependencies rejects a hidden dependency before merging anything`() = runTest {
        val prId = UUID.random()
        val hiddenRepository = repo.copy(id = UUID.random(), slug = "hidden")
        val hidden = PullRequest(
            id = UUID.random(), repositoryId = hiddenRepository.id, number = 1,
            title = "Hidden", authorId = UUID.random(), sourceBranch = "feature", targetBranch = "main",
        )
        val parent = hidden.copy(id = prId, repositoryId = id, number = 2)
        coEvery { pullRequestService.getMergePlan(prId) } returns listOf(hidden, parent)
        coEvery { repositoryService.findById(hiddenRepository.id) } returns hiddenRepository
        repoFound()
        coEvery {
            permissionEvaluator.filterAllowed(
                auth, listOf(hiddenRepository, repo), PermissionAction.VIEW,
            )
        } returns listOf(repo)

        val failure = assertFailsWith<SecurityException> {
            mutation.mergePullRequestWithDependencies(auth, prId, bosca.git.model.MergeStrategy.MERGE_COMMIT)
        }

        kotlin.test.assertEquals(
            "You cannot view every pull request required for this merge",
            failure.message,
        )
        coVerify(exactly = 0) {
            pullRequestService.mergeWithDependencies(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { permissionEvaluator.verifyAllowed(auth, any(), PermissionAction.EDIT) }
    }

    @Test
    fun `mergePullRequestWithDependencies rejects empty and incomplete plans`() = runTest {
        val prId = UUID.random()
        coEvery { pullRequestService.getMergePlan(prId) } returns emptyList()
        assertFailsWith<IllegalArgumentException> {
            mutation.mergePullRequestWithDependencies(auth, prId, bosca.git.model.MergeStrategy.MERGE_COMMIT)
        }

        val parent = PullRequest(
            id = prId, repositoryId = id, number = 1, title = "Parent",
            authorId = UUID.random(), sourceBranch = "feature", targetBranch = "main",
        )
        coEvery { pullRequestService.getMergePlan(prId) } returns listOf(parent)
        coEvery { repositoryService.findById(id) } returns null
        val failure = assertFailsWith<SecurityException> {
            mutation.mergePullRequestWithDependencies(auth, prId, bosca.git.model.MergeStrategy.MERGE_COMMIT)
        }
        kotlin.test.assertEquals(
            "You cannot view every pull request required for this merge",
            failure.message,
        )
        coVerify(exactly = 0) {
            pullRequestService.mergeWithDependencies(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `mergePullRequestWithDependencies requires an actor profile after authorizing the full plan`() = runTest {
        val prId = UUID.random()
        val parent = PullRequest(
            id = prId, repositoryId = id, number = 1, title = "Parent",
            authorId = UUID.random(), sourceBranch = "feature", targetBranch = "main",
        )
        coEvery { pullRequestService.getMergePlan(prId) } returns listOf(parent)
        repoFound()
        coEvery {
            permissionEvaluator.filterAllowed(auth, listOf(repo), PermissionAction.VIEW)
        } returns listOf(repo)
        io.mockk.every { auth.principal() } returns null

        assertFailsWith<SecurityException> {
            mutation.mergePullRequestWithDependencies(auth, prId, bosca.git.model.MergeStrategy.MERGE_COMMIT)
        }

        principal()
        coEvery { profileService.getPrimaryProfile(any()) } returns null
        assertFailsWith<SecurityException> {
            mutation.mergePullRequestWithDependencies(auth, prId, bosca.git.model.MergeStrategy.MERGE_COMMIT)
        }
        coVerify(exactly = 0) {
            pullRequestService.mergeWithDependencies(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `dependency mutations enforce edit on the parent and view on the prerequisite`() = runTest {
        val prId = UUID.random()
        val dependencyId = UUID.random()
        val dependencyRepository = repo.copy(id = UUID.random(), slug = "dependency")
        val parent = prFound(prId)
        val dependency = PullRequest(
            id = dependencyId, repositoryId = dependencyRepository.id, number = 1,
            title = "Dependency", authorId = UUID.random(), sourceBranch = "feature", targetBranch = "main",
        )
        coEvery { pullRequestService.findById(dependencyId) } returns dependency
        coEvery { repositoryService.findById(dependencyRepository.id) } returns dependencyRepository
        coEvery { pullRequestService.addDependency(prId, dependencyId) } returns parent
        coEvery { pullRequestService.removeDependency(prId, dependencyId) } returns parent

        mutation.addPullRequestDependency(auth, prId, dependencyId)
        mutation.removePullRequestDependency(auth, prId, dependencyId)

        coVerify(exactly = 2) { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.EDIT) }
        coVerify { permissionEvaluator.verifyAllowed(auth, dependencyRepository, PermissionAction.VIEW) }
        coVerify { pullRequestService.addDependency(prId, dependencyId) }
        coVerify { pullRequestService.removeDependency(prId, dependencyId) }
    }

    @Test
    fun `dependency mutations reject missing pull requests and repositories`() = runTest {
        val prId = UUID.random()
        val dependencyId = UUID.random()
        val dependencyRepositoryId = UUID.random()
        val parent = PullRequest(
            id = prId, repositoryId = id, number = 1, title = "Parent",
            authorId = UUID.random(), sourceBranch = "feature", targetBranch = "main",
        )
        val dependency = parent.copy(
            id = dependencyId, repositoryId = dependencyRepositoryId, number = 2,
        )

        coEvery { pullRequestService.findById(prId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.addPullRequestDependency(auth, prId, dependencyId)
        }
        assertFailsWith<NoSuchElementException> {
            mutation.removePullRequestDependency(auth, prId, dependencyId)
        }

        coEvery { pullRequestService.findById(prId) } returns parent
        coEvery { repositoryService.findById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.addPullRequestDependency(auth, prId, dependencyId)
        }
        assertFailsWith<NoSuchElementException> {
            mutation.removePullRequestDependency(auth, prId, dependencyId)
        }

        coEvery { repositoryService.findById(id) } returns repo
        coEvery { pullRequestService.findById(dependencyId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.addPullRequestDependency(auth, prId, dependencyId)
        }

        coEvery { pullRequestService.findById(dependencyId) } returns dependency
        coEvery { repositoryService.findById(dependencyRepositoryId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.addPullRequestDependency(auth, prId, dependencyId)
        }
    }

    @Test
    fun `assign and unassign delegate to the pull request service`() = runTest {
        val prId = UUID.random()
        prFound(prId)
        val profileId = UUID.random()
        mutation.assignPullRequest(auth, prId, profileId)
        mutation.unassignPullRequest(auth, prId, profileId)
        coVerify { pullRequestService.addAssignee(prId, profileId) }
        coVerify { pullRequestService.removeAssignee(prId, profileId) }
    }

    @Test
    fun `submitReview requires a principal and delegates with the reviewer id`() = runTest {
        val prId = UUID.random()
        prFound(prId)
        val input = mockk<bosca.git.model.SubmitReviewInput>(relaxed = true)
        coEvery { input.pullRequestId } returns prId

        io.mockk.every { auth.principal() } returns null
        assertFailsWith<SecurityException> { mutation.submitReview(auth, input) }

        val pid = actorProfile()
        mutation.submitReview(auth, input)
        coVerify { pullRequestService.submitReview(input, pid) }
    }

    @Test
    fun `addReviewComment requires a principal and delegates with the author id`() = runTest {
        val prId = UUID.random()
        prFound(prId)
        val input = bosca.git.model.AddReviewCommentInput(
            reviewId = UUID.random(), pullRequestId = prId,
            filePath = "src/a.kt", newLineNumber = 3, commitSha = "abc", content = "looks off",
        )

        io.mockk.every { auth.principal() } returns null
        assertFailsWith<SecurityException> { mutation.addReviewComment(auth, input) }

        val pid = actorProfile()
        mutation.addReviewComment(auth, input)
        coVerify { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.VIEW) }
        coVerify {
            pullRequestService.addReviewComment(
                input.reviewId, prId, pid, "src/a.kt", null, 3, "abc", "looks off",
            )
        }
    }

    @Test
    fun `resolveReviewThread verifies VIEW and delegates`() = runTest {
        val prId = UUID.random()
        prFound(prId)
        assertTrue(mutation.resolveReviewThread(auth, prId, "src/a.kt", 3))
        coVerify { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.VIEW) }
        coVerify { pullRequestService.resolveThread(prId, "src/a.kt", 3) }
    }

    @Test
    fun `webhook mutations stamp ids and verify MANAGE`() = runTest {
        repoFound()
        val hook = bosca.git.model.Webhook(id = UUID.random(), repositoryId = UUID.random(), url = "https://x", secret = "s")
        mutation.createWebhook(auth, id, hook)
        coVerify { webhookService.create(match { it.repositoryId == id }) }

        val hookId = UUID.random()
        coEvery { webhookService.findById(hookId) } returns hook.copy(id = hookId, repositoryId = id)
        mutation.updateWebhook(auth, hookId, hook)
        coVerify { webhookService.update(match { it.id == hookId && it.repositoryId == id }) }

        assertTrue(mutation.deleteWebhook(auth, hookId))
        coVerify { webhookService.delete(hookId) }
    }

    @Test
    fun `deleteWebhook throws when the webhook is missing`() = runTest {
        val hookId = UUID.random()
        coEvery { webhookService.findById(hookId) } returns null
        assertFailsWith<NoSuchElementException> { mutation.deleteWebhook(auth, hookId) }
    }

    @Test
    fun `setScriptSourceRef backfills when the provider exists`() = runTest {
        repoFound()
        val scriptId = UUID.random()
        val backfill = mockk<ScriptSourceBackfill>(relaxed = true)
        coEvery { scriptSourceBackfill.exists } returns true
        coEvery { scriptSourceBackfill.get() } returns backfill
        coEvery { sourceRefService.setScriptSourceRef(scriptId, any()) } returns
            bosca.git.model.ScriptSourceRef(scriptId, id, "scripts/x/source.kts")

        mutation.setScriptSourceRef(auth, scriptId, id, "scripts/x/source.kts", "main")

        coVerify { sourceRefService.setScriptSourceRef(scriptId, match { it.repositoryId == id }) }
        coVerify { backfill.backfillScript(scriptId) }
    }

    @Test
    fun `setQuerySourceRef skips backfill when the provider is absent`() = runTest {
        repoFound()
        val queryId = UUID.random()
        coEvery { querySourceBackfill.exists } returns false
        coEvery { sourceRefService.setQuerySourceRef(queryId, any()) } returns
            bosca.git.model.QuerySourceRef(queryId, id, "queries/x.sql")

        mutation.setQuerySourceRef(auth, queryId, id, "queries/x.sql", "main")

        coVerify(exactly = 0) { querySourceBackfill.get() }
    }

    @Test
    fun `remove source refs resolve the existing ref first`() = runTest {
        repoFound()
        val scriptId = UUID.random()
        val queryId = UUID.random()
        coEvery { sourceRefService.findScriptSourceRef(scriptId) } returns
            bosca.git.model.ScriptSourceRef(scriptId, id, "p")
        coEvery { sourceRefService.findQuerySourceRef(queryId) } returns
            bosca.git.model.QuerySourceRef(queryId, id, "p")

        assertTrue(mutation.removeScriptSourceRef(auth, scriptId))
        assertTrue(mutation.removeQuerySourceRef(auth, queryId))
        coVerify { sourceRefService.removeScriptSourceRef(scriptId) }
        coVerify { sourceRefService.removeQuerySourceRef(queryId) }
    }

    @Test
    fun `removeScriptSourceRef throws when no ref exists`() = runTest {
        val scriptId = UUID.random()
        coEvery { sourceRefService.findScriptSourceRef(scriptId) } returns null
        assertFailsWith<NoSuchElementException> { mutation.removeScriptSourceRef(auth, scriptId) }
    }

    @Test
    fun `syncSourceRefs sums changes from both available backfills`() = runTest {
        repoFound()
        val qb = mockk<QuerySourceBackfill>(relaxed = true)
        val sb = mockk<ScriptSourceBackfill>(relaxed = true)
        coEvery { querySourceBackfill.exists } returns true
        coEvery { querySourceBackfill.get() } returns qb
        coEvery { scriptSourceBackfill.exists } returns true
        coEvery { scriptSourceBackfill.get() } returns sb
        coEvery { qb.backfillRepository(id) } returns 2
        coEvery { sb.backfillRepository(id) } returns 3

        kotlin.test.assertEquals(5, mutation.syncSourceRefs(auth, id))
    }

    @Test
    fun `syncSourceRefs returns zero when no backfills are available`() = runTest {
        repoFound()
        coEvery { querySourceBackfill.exists } returns false
        coEvery { scriptSourceBackfill.exists } returns false
        kotlin.test.assertEquals(0, mutation.syncSourceRefs(auth, id))
    }

    @Test
    fun `commitFile and deleteFile retain the authenticated initiating principal`() = runTest {
        repoFound()
        val principalId = principal()
        val input = GraphQLCommitFileInput(
            repositoryId = id, branch = "main", path = "a.txt", content = "x",
            message = "m", authorName = "n", authorEmail = "e",
        )
        mutation.commitFile(auth, input)
        coVerify {
            writeService.commitFile(
                match { it.repositoryId == id && it.path == "a.txt" && it.message == "m" },
                principalId,
            )
        }

        mutation.deleteFile(auth, id, "main", "a.txt", "n", "e")
        coVerify {
            writeService.deleteFile(
                match { it.repositoryId == id && it.path == "a.txt" && it.message == "Delete a.txt" },
                principalId,
            )
        }
    }

    @Test
    fun `commitFile and deleteFile require an authenticated principal`() = runTest {
        repoFound()
        io.mockk.every { auth.principal() } returns null
        val input = GraphQLCommitFileInput(
            repositoryId = id, branch = "main", path = "a.txt", content = "x",
            message = "m", authorName = "n", authorEmail = "e",
        )

        assertFailsWith<SecurityException> { mutation.commitFile(auth, input) }
        assertFailsWith<SecurityException> { mutation.deleteFile(auth, id, "main", "a.txt", "n", "e") }
    }

    @Test
    fun `createBranch verifies EDIT and delegates`() = runTest {
        repoFound()
        val principalId = principal()
        mutation.createBranch(auth, id, "feature", "main")
        coVerify { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.EDIT) }
        coVerify { repositoryService.createBranch(id, "feature", "main", principalId) }
    }

    @Test
    fun `createBranch requires an authenticated principal`() = runTest {
        repoFound()
        io.mockk.every { auth.principal() } returns null
        assertFailsWith<SecurityException> {
            mutation.createBranch(auth, id, "feature", "main")
        }
        coVerify(exactly = 0) { repositoryService.createBranch(any(), any(), any(), any()) }
    }

    @Test
    fun `deleteTag and deleteBranch enforce branch safety then delegate`() = runTest {
        repoFound()
        assertTrue(mutation.deleteTag(auth, id, "v1"))
        coVerify { writeService.deleteTag(id, "v1") }

        assertFailsWith<IllegalArgumentException> {
            mutation.deleteBranch(auth, id, repo.defaultBranch)
        }

        coEvery { branchProtectionService.findMatchingRule(id, "protected") } returns
            BranchProtectionRule(repositoryId = id, pattern = "protected")
        assertFailsWith<IllegalStateException> {
            mutation.deleteBranch(auth, id, "protected")
        }

        coEvery { branchProtectionService.findMatchingRule(id, "feature") } returns null
        assertTrue(mutation.deleteBranch(auth, id, "feature"))
        coVerify { writeService.deleteBranch(id, "feature") }
    }

    @Test
    fun `deleteTag and deleteBranch reject a missing repository`() = runTest {
        coEvery { repositoryService.findById(id) } returns null
        assertFailsWith<NoSuchElementException> { mutation.deleteTag(auth, id, "v1") }
        assertFailsWith<NoSuchElementException> { mutation.deleteBranch(auth, id, "feature") }
    }

    @Test
    fun `updateBranchProtectionRule keeps the existing repository binding`() = runTest {
        val ruleId = UUID.random()
        coEvery { branchProtectionService.findById(ruleId) } returns
            BranchProtectionRule(id = ruleId, repositoryId = id, pattern = "main")
        repoFound()
        val input = BranchProtectionRuleInput(pattern = "release/*")
        mutation.updateBranchProtectionRule(auth, ruleId, input)
        coVerify { branchProtectionService.update(match { it.id == ruleId && it.repositoryId == id && it.pattern == "release/*" }) }
    }

    @Test
    fun `every repository-scoped mutation throws when the repository is missing`() = runTest {
        coEvery { repositoryService.findById(any()) } returns null
        coEvery { repositoryService.findByIdIncludingDeleted(any()) } returns null

        val prId = UUID.random()
        val pr = mockk<PullRequest>(relaxed = true)
        coEvery { pr.repositoryId } returns id
        coEvery { pullRequestService.findById(prId) } returns pr

        val hookId = UUID.random()
        coEvery { webhookService.findById(hookId) } returns
            bosca.git.model.Webhook(id = hookId, repositoryId = id, url = "u", secret = "s")

        val ruleId = UUID.random()
        coEvery { branchProtectionService.findById(ruleId) } returns
            BranchProtectionRule(id = ruleId, repositoryId = id, pattern = "main")

        val scriptId = UUID.random()
        val queryId = UUID.random()
        coEvery { sourceRefService.findScriptSourceRef(scriptId) } returns bosca.git.model.ScriptSourceRef(scriptId, id, "p")
        coEvery { sourceRefService.findQuerySourceRef(queryId) } returns bosca.git.model.QuerySourceRef(queryId, id, "p")

        val perm = mockk<PermissionInput>(relaxed = true)
        coEvery { perm.entityId } returns id
        val prInput = mockk<bosca.git.model.CreatePullRequestInput>(relaxed = true)
        coEvery { prInput.repositoryId } returns id
        val forkInput = mockk<bosca.git.model.ForkRepositoryInput>(relaxed = true)
        coEvery { forkInput.sourceRepositoryId } returns id
        val reviewInput = mockk<bosca.git.model.SubmitReviewInput>(relaxed = true)
        coEvery { reviewInput.pullRequestId } returns prId
        val reviewCommentInput = bosca.git.model.AddReviewCommentInput(
            reviewId = UUID.random(), pullRequestId = prId, filePath = "p", commitSha = "c", content = "x",
        )
        val commitInput = GraphQLCommitFileInput(
            repositoryId = id, branch = "main", path = "a", content = "x",
            message = "m", authorName = "n", authorEmail = "e",
        )

        val calls: List<suspend () -> Any?> = listOf(
            { mutation.archiveRepository(auth, id) },
            { mutation.deleteRepository(auth, id) },
            { mutation.restoreRepository(auth, id) },
            { mutation.transferRepository(auth, id, UUID.random()) },
            { mutation.renameRepository(auth, id, "new-slug") },
            { mutation.runRepositoryGc(auth, id) },
            { mutation.runRepositoryRepair(auth, id) },
            { mutation.forkRepository(auth, forkInput) },
            { mutation.addPermission(auth, perm) },
            { mutation.removePermission(auth, perm) },
            { mutation.createBranch(auth, id, "b", "main") },
            { mutation.createBranchProtectionRule(auth, id, BranchProtectionRuleInput(pattern = "x")) },
            { mutation.updateBranchProtectionRule(auth, ruleId, BranchProtectionRuleInput(pattern = "x")) },
            { mutation.deleteBranchProtectionRule(auth, ruleId) },
            { mutation.createPullRequest(auth, prInput) },
            { mutation.updatePullRequest(auth, prId, mockk(relaxed = true)) },
            { mutation.mergePullRequest(auth, prId, bosca.git.model.MergeStrategy.MERGE_COMMIT) },
            { mutation.closePullRequest(auth, prId) },
            { mutation.reopenPullRequest(auth, prId) },
            { mutation.markPullRequestReady(auth, prId) },
            { mutation.assignPullRequest(auth, prId, UUID.random()) },
            { mutation.unassignPullRequest(auth, prId, UUID.random()) },
            { mutation.submitReview(auth, reviewInput) },
            { mutation.addReviewComment(auth, reviewCommentInput) },
            { mutation.resolveReviewThread(auth, prId, "p", 1) },
            { mutation.createWebhook(auth, id, bosca.git.model.Webhook(repositoryId = id, url = "u", secret = "s")) },
            { mutation.updateWebhook(auth, hookId, bosca.git.model.Webhook(repositoryId = id, url = "u", secret = "s")) },
            { mutation.deleteWebhook(auth, hookId) },
            { mutation.setScriptSourceRef(auth, scriptId, id, "p", "main") },
            { mutation.removeScriptSourceRef(auth, scriptId) },
            { mutation.setQuerySourceRef(auth, queryId, id, "p", "main") },
            { mutation.removeQuerySourceRef(auth, queryId) },
            { mutation.syncSourceRefs(auth, id) },
            { mutation.commitFile(auth, commitInput) },
            { mutation.deleteFile(auth, id, "main", "a", "n", "e") },
        )
        for ((index, block) in calls.withIndex()) {
            try {
                block()
                kotlin.test.fail("call #$index should have thrown NoSuchElementException")
            } catch (_: NoSuchElementException) {
                // expected
            }
        }
    }

    @Test
    fun `updatePullRequest and updateWebhook throw when the target itself is missing`() = runTest {
        val prId = UUID.random()
        coEvery { pullRequestService.findById(prId) } returns null
        assertFailsWith<NoSuchElementException> { mutation.updatePullRequest(auth, prId, mockk(relaxed = true)) }
        assertFailsWith<NoSuchElementException> { mutation.mergePullRequest(auth, prId, bosca.git.model.MergeStrategy.MERGE_COMMIT) }
        assertFailsWith<NoSuchElementException> { mutation.reopenPullRequest(auth, prId) }
        assertFailsWith<NoSuchElementException> { mutation.markPullRequestReady(auth, prId) }
        assertFailsWith<NoSuchElementException> { mutation.assignPullRequest(auth, prId, UUID.random()) }
        assertFailsWith<NoSuchElementException> { mutation.unassignPullRequest(auth, prId, UUID.random()) }
        assertFailsWith<NoSuchElementException> { mutation.submitReview(auth, mockk(relaxed = true) { coEvery { pullRequestId } returns prId }) }
        assertFailsWith<NoSuchElementException> {
            mutation.addReviewComment(auth, bosca.git.model.AddReviewCommentInput(
                reviewId = UUID.random(), pullRequestId = prId, filePath = "p", commitSha = "c", content = "x",
            ))
        }
        assertFailsWith<NoSuchElementException> { mutation.resolveReviewThread(auth, prId, "p", 1) }

        val hookId = UUID.random()
        coEvery { webhookService.findById(hookId) } returns null
        assertFailsWith<NoSuchElementException> { mutation.updateWebhook(auth, hookId, bosca.git.model.Webhook(repositoryId = id, url = "u", secret = "s")) }

        val ruleId = UUID.random()
        coEvery { branchProtectionService.findById(ruleId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.updateBranchProtectionRule(auth, ruleId, BranchProtectionRuleInput(pattern = "x"))
        }

        val scriptId = UUID.random()
        coEvery { sourceRefService.findScriptSourceRef(scriptId) } returns null
        assertFailsWith<NoSuchElementException> { mutation.removeScriptSourceRef(auth, scriptId) }
        val queryId = UUID.random()
        coEvery { sourceRefService.findQuerySourceRef(queryId) } returns null
        assertFailsWith<NoSuchElementException> { mutation.removeQuerySourceRef(auth, queryId) }
    }

    @Test
    fun `forkRepository only requires VIEW on the source`() = runTest {
        val input = mockk<bosca.git.model.ForkRepositoryInput>(relaxed = true)
        coEvery { input.sourceRepositoryId } returns id
        repoFound()
        mutation.forkRepository(auth, input)
        coVerify { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.VIEW) }
        coVerify { repositoryService.fork(input) }
    }

    @Test
    fun `gc and repair throw when the repository vanishes mid-operation`() = runTest {
        coEvery { lifecycleService.runGc(id) } returns true
        coEvery { lifecycleService.repair(id) } returns true
        coEvery { repositoryService.findById(id) } returnsMany listOf(repo, null, repo, null)
        assertFailsWith<NoSuchElementException> { mutation.runRepositoryGc(auth, id) }
        assertFailsWith<NoSuchElementException> { mutation.runRepositoryRepair(auth, id) }
    }

    @Test
    fun `deleteBranchProtectionRule deletes after resolving rule and repository`() = runTest {
        val ruleId = UUID.random()
        coEvery { branchProtectionService.findById(ruleId) } returns
            BranchProtectionRule(id = ruleId, repositoryId = id, pattern = "main")
        repoFound()
        assertTrue(mutation.deleteBranchProtectionRule(auth, ruleId))
        coVerify { branchProtectionService.delete(ruleId) }
    }

    @Test
    fun `setQuerySourceRef backfills when the provider exists`() = runTest {
        repoFound()
        val queryId = UUID.random()
        val backfill = mockk<QuerySourceBackfill>(relaxed = true)
        coEvery { querySourceBackfill.exists } returns true
        coEvery { querySourceBackfill.get() } returns backfill
        coEvery { sourceRefService.setQuerySourceRef(queryId, any()) } returns
            bosca.git.model.QuerySourceRef(queryId, id, "queries/x.sql")

        mutation.setQuerySourceRef(auth, queryId, id, "queries/x.sql", "main")

        coVerify { backfill.backfillQuery(queryId) }
    }

    @Test
    fun `commit file input serializes and the mutation marker exists`() {
        val input = GraphQLCommitFileInput(
            repositoryId = id, branch = "main", path = "a", content = "x",
            message = "m", authorName = "n", authorEmail = "e",
        )
        val json = kotlinx.serialization.json.Json.encodeToString(GraphQLCommitFileInput.serializer(), input)
        assertTrue(json.contains("\"authorName\":\"n\""), json)
        assertTrue(GitMutation.toString().isNotEmpty())
    }
}
