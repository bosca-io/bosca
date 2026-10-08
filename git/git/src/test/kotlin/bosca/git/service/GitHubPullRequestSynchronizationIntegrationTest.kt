@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.service

import bosca.db.*
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.github.GitHubClient
import bosca.git.github.GitHubRequestRejectedException
import bosca.git.model.*
import bosca.git.repository.*
import bosca.lock.DistributedLockFactory
import bosca.pipelines.service.PipelineSecretService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

/** Production PR service, generated JDBC repositories, version checks and pair transactions against PostgreSQL. */
class GitHubPullRequestSynchronizationIntegrationTest {
    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("github_pull_requests_test"); withReuse(true); start()
        }
        private val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
            url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 6,
        ), key = "github-pull-requests-test"))
    }
    private val repositoryId = UUID.random()
    private val authorId = UUID.random()
    private val principalId = UUID.random()
    private val hosted = Repository(id = repositoryId, name = "Source", slug = "source", ownerId = authorId)
    private val repository = GitHubSyncRepositoryImpl()
    private val nativeRepository = PullRequestRepositoryImpl()
    private val hostedService = mockk<RepositoryService>()
    private val hostedRepository = mockk<GitRepositoryRepository>()
    private val profiles = mockk<ProfileService>()
    private val security = mockk<SecurityService>()
    private val writers = bosca.security.model.Group(UUID.random(), "writers", "Writers", bosca.security.model.GroupType.SYSTEM)
    private val permissions = bosca.git.security.RepositoryPermissionEvaluator(hostedService, security, bosca.security.service.GroupEvaluator(security))
    private val protections = mockk<BranchProtectionService>()
    private val dfs = mockk<bosca.git.dfs.BoscaDfsRepositoryManager>()
    private val reviews = ReviewRepositoryImpl()
    private val dependencies = mockk<PullRequestDependencyRepository>(relaxed = true)
    private val dfsRepository = mockk<org.eclipse.jgit.internal.storage.dfs.DfsRepository>(relaxed = true)
    private val statuses = mockk<CommitStatusService>()
    private val secrets = mockk<PipelineSecretService>()
    private val github = mockk<GitHubClient>()
    private val locks = mockk<DistributedLockFactory>()
    private val writes = mockk<RepositoryWriteService>()
    private val notifications = mockk<PubSubService>(relaxed = true)
    private val native = PullRequestServiceImpl(nativeRepository, dependencies, reviews,
        mockk(relaxed = true), hostedRepository, mockk(relaxed = true), protections, dfs,
        mockk(relaxed = true), statuses, locks, profiles, security)
    private val service = GitHubSyncServiceImpl(repository, hostedService, secrets, security, writes, github, locks, native, profiles, permissions, protections)
    private val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer()) } }
    private var remote = remote()
    private var nextNumber = 0
    private var created = 0
    private var patched = 0
    private var failAfterCreate = false
    private val timestamp = OffsetDateTime.parse("2026-10-02T10:00:00Z")

    private fun remote() = GitHubPullRequest(456, 7, "PR_456", "Change", "Description", "open", user = GitHubPullRequestUser(8, "author", "User"),
        head = GitHubPullRequestBranch("feature", "1".repeat(40), GitHubWebhookRepository(123)),
        base = GitHubPullRequestBranch("main", "2".repeat(40), GitHubWebhookRepository(123)), modified = OffsetDateTime.parse("2026-10-02T10:00:00Z"))

    @BeforeTest fun setup(): Unit = runBlocking {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        provides<PubSubService>(singleton = true) { notifications }
        coEvery { hostedService.findById(repositoryId) } returns hosted
        coEvery { hostedService.isParentAllowed(any(), hosted, any()) } returns false
        coEvery { hostedRepository.findById(repositoryId) } returns hosted
        coEvery { hostedRepository.incrementPrNumber(repositoryId) } answers { ++nextNumber }
        coEvery { security.getPrincipalById(principalId) } returns Principal(id = principalId)
        coEvery { security.getPrincipalGroups(principalId) } returns listOf(writers)
        coEvery { hostedService.getPermissions(hosted) } returns listOf(RepositoryPermission(repositoryId, writers.id, bosca.security.model.PermissionAction.EDIT))
        coEvery { protections.findMatchingRule(repositoryId, any()) } returns null
        every { dfsRepository.resolve("refs/heads/feature") } returns org.eclipse.jgit.lib.ObjectId.fromString("1".repeat(40))
        every { dfsRepository.refDatabase.findRef("refs/heads/feature") } returns org.eclipse.jgit.lib.ObjectIdRef.Unpeeled(
            org.eclipse.jgit.lib.Ref.Storage.NEW, "refs/heads/feature", org.eclipse.jgit.lib.ObjectId.fromString("1".repeat(40)))
        every { dfs.open(repositoryId) } returns dfsRepository
        val profile = Profile(id = authorId, name = "Original Author", type = ProfileType.GENERIC, visibility = ProfileVisibility.USER)
        coEvery { profiles.getPrimaryProfile(any()) } returns profile
        coEvery { profiles.getAllByIds(any()) } answers { firstArg<List<UUID>>().map { profile.copy(id = it) } }
        coEvery { secrets.resolve(any()) } returns "token"
        val lock = mockk<bosca.lock.DistributedLock>()
        coEvery { locks.create(any()) } returns lock
        coEvery { lock.acquire(any(), any(), any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
        coEvery { github.repositoryUrl(any(), any()) } returns "https://github.com/owner/source.git"
        coEvery { github.getPullRequest(any(), any(), any()) } answers { remote }
        coEvery { github.listPullRequests(any(), any(), any(), any()) } answers {
            if (thirdArg<Int>() == 1 && created > 0) listOf(remote) else emptyList()
        }
        coEvery { github.createPullRequest(any(), any(), any()) } answers {
            val input = thirdArg<GitHubCreatePullRequestInput>()
            created++
            remote = remote.copy(title = input.title, body = input.body, draft = input.draft,
                head = remote.head.copy(ref = input.head), base = remote.base.copy(ref = input.base))
            if (failAfterCreate) throw IllegalStateException("Response lost")
            remote
        }
        coEvery { github.updatePullRequest(any(), any(), any(), any()) } answers {
            val input = arg<GitHubUpdatePullRequestInput>(3)
            patched++
            patch(input)
        }
        coEvery { github.setPullRequestDraft(any(), any(), any(), any()) } answers { remote = remote.copy(draft = arg(3)) }
        coEvery { writes.compareRefs(any(), any(), any()) } returns listOf(
            RefComparison("refs/heads/feature", "1".repeat(40), "1".repeat(40)),
            RefComparison("refs/heads/main", "2".repeat(40), "2".repeat(40)),
        )
        coEvery { writes.synchronizeRef(any()) } answers {
            val input = firstArg<RefSynchronizationInput>()
            RefSynchronizationResult(GitHubSyncResult.UNCHANGED, input.afterSha, input.afterSha)
        }
        db {
            connection().useStatement("drop schema if exists git cascade; create schema git; create table git.repositories(id uuid primary key)") { it.execute() }
            for (name in listOf("V3__pull_requests.sql", "V6__review_comments.sql", "V44__github_intake.sql", "V45__github_ref_synchronization.sql", "V46__github_pull_request_synchronization.sql", "V47__github_delivery_problems.sql", "V48__github_branch_filters.sql")) {
                connection().useStatement(javaClass.getResource("/db/migrations/$name")?.readText() ?: error(name)) { it.execute() }
            }
            connection().useStatement("create table git.dfs_refs(repository_id uuid, name text, object_id text, updated timestamptz)") { it.execute() }
            connection().useStatement("insert into git.repositories(id) values ('$repositoryId')") { it.execute() }
            service.savePair(GitHubRepositoryPairInput(repositoryId, 123, "owner", "source", "webhook", "token", true))
            service.mapUser(8, principalId)
        }
    }
    @AfterTest fun cleanup() = ProviderRegistry.clear()
    private suspend fun <T> db(block: suspend () -> T): T = withConnectionManager { block() }
    private suspend fun delivery(number: Int = 7, event: String = "pull_request", ignored: Boolean = false) = db {
        repository.createDelivery(GitHubDelivery(UUID.random().toString(), repositoryId, event,
            buildJsonObject { put("number", number); put("pull_request", json.encodeToJsonElement(GitHubPullRequest.serializer(), remote)) },
            "digest", principalId = principalId, ignored = ignored)) ?: error("delivery")
    }
    private suspend fun states() = db { service.findPullRequestStates(repositoryId, 0, 100) }
    private suspend fun imported(): PullRequest {
        service.synchronizePullRequest(delivery(remote.number))
        return db { native.findById(requireNotNull(states().single { it.githubNumber == remote.number }.pullRequestId)) } ?: error("import")
    }
    private suspend fun create(draft: Boolean = false, description: String? = "Description") = db {
        native.create(CreatePullRequestInput(repositoryId, "Change", description, "feature", "main", isDraft = draft), authorId)
    }
    private fun event(pr: PullRequest) = PullRequestEvent(repositoryId, pr.id, pr.number, PullRequestEventAction.UPDATED,
        "Old title", pr.sourceBranch, pr.targetBranch, pr.authorId)
    private fun patch(input: GitHubUpdatePullRequestInput): GitHubPullRequest {
        val body = input.body
        remote = remote.copy(title = input.title ?: remote.title,
            body = if (body != null) body.contentOrNull else remote.body,
            state = input.state ?: remote.state, base = remote.base.copy(ref = input.base ?: remote.base.ref))
        return remote
    }

    @Test fun `PR branch transfers cannot bypass either automatic direction filter`(): Unit = runBlocking {
        db {
            val pair = checkNotNull(service.findPair(repositoryId))
            service.savePair(GitHubRepositoryPairInput(repositoryId, 123, "owner", "source", "webhook", "token", true,
                pair.version, pushBranchExcludes = listOf("feature"), pullBranchExcludes = listOf("feature")))
        }
        coEvery { writes.compareRefs(any(), any(), any()) } returns listOf(
            RefComparison("refs/heads/feature", "1".repeat(40), "3".repeat(40)),
            RefComparison("refs/heads/main", "2".repeat(40), "2".repeat(40)),
        )
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
        assertTrue(states().single().problem?.contains("filtered") == true)
        val local = create()
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(local)))
        assertEquals(0, created)
        coVerify(exactly = 0) { writes.synchronizeRef(any()) }
    }

    @Test fun `imports original author once and redelivery and echoes do not create or update another PR`(): Unit = runBlocking {
        val receipt = delivery()
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(receipt.copy(principalId = UUID.random())))
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(receipt))
        val state = states().single()
        val pr = db { native.findById(requireNotNull(state.pullRequestId)) } ?: error("missing")
        assertEquals(authorId, pr.authorId); assertEquals(remote.title, pr.title); assertTrue(state.imported)
        assertEquals(456L, state.githubId); assertEquals(7, state.githubNumber); assertNull(state.problem)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(event(pr)))
        assertEquals(0, created); assertEquals(0, patched)
        coVerify { writes.synchronizeRef(match { !it.triggerBuild }) }
    }

    @Test fun `a mapped GitHub user without current Bosca write permission cannot create or edit a PR`(): Unit = runBlocking {
        coEvery { hostedService.getPermissions(hosted) } returns emptyList()
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
        assertNull(states().single().pullRequestId)
        coEvery { hostedService.getPermissions(hosted) } returns listOf(RepositoryPermission(repositoryId, writers.id, bosca.security.model.PermissionAction.EDIT))
        val pr = imported()
        remote = remote.copy(title = "Unauthorized change")
        val receipt = delivery()
        coEvery { security.getPrincipalGroups(principalId) } returns emptyList()
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(receipt))
        assertEquals(pr.title, db { native.findById(pr.id) }?.title)
        coEvery { security.getPrincipalGroups(principalId) } returns listOf(writers)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(receipt))
    }

    @Test fun `a signed PR occurrence cannot authorize provider edits or commits made after its snapshot`(): Unit = runBlocking {
        val pr = imported()
        remote = remote.copy(title = "Authorized edit")
        val receipt = delivery()
        remote = remote.copy(title = "Later edit", head = remote.head.copy(sha = "4".repeat(40)))
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(receipt))
        assertEquals(pr.title, db { native.findById(pr.id) }?.title)
        assertEquals(pr.version, db { native.findById(pr.id) }?.version)
        assertNotNull(states().single().problem)
    }

    @Test fun `GitHub completed merges wait for Bosca approvals and checks before importing protected history`(): Unit = runBlocking {
        val pr = imported()
        val rule = BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requirePullRequest = true,
            requiredApprovals = 1, requireStatusChecks = listOf("ci/build"))
        coEvery { protections.findMatchingRule(repositoryId, "main") } returns rule
        remote = remote.copy(state = "closed", merged = true, mergedBy = remote.user, mergeSha = "3".repeat(40), mergedAt = timestamp,
            base = remote.base.copy(sha = "3".repeat(40)))
        val receipt = delivery()
        coEvery { writes.compareRefs(any(), any(), any()) } returns listOf(
            RefComparison("refs/heads/feature", "1".repeat(40), "1".repeat(40)),
            RefComparison("refs/heads/main", "2".repeat(40), "3".repeat(40)))
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(receipt))
        assertEquals(PullRequestStatus.OPEN, db { native.findById(pr.id) }?.status)
        db { reviews.create(Review(pullRequestId = pr.id, reviewerId = UUID.random(), status = ReviewStatus.APPROVED)) }
        coEvery { statuses.areRequiredChecksPassing(repositoryId, "1".repeat(40), listOf("ci/build")) } returns false
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(receipt))
        coVerify(exactly = 0) { writes.synchronizeRef(match { it.afterSha == "3".repeat(40) }) }
        coEvery { statuses.areRequiredChecksPassing(repositoryId, "1".repeat(40), listOf("ci/build")) } returns true
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(receipt))
        coVerify { writes.synchronizeRef(match { it.pullRequestMerge && it.protection == rule && it.principalId == principalId && !it.triggerBuild }) }
        assertEquals(PullRequestStatus.MERGED, db { native.findById(pr.id) }?.status)
    }

    @Test fun `dismissed Bosca approvals cannot authorize an imported GitHub merge`(): Unit = runBlocking {
        val pr = imported()
        coEvery { protections.findMatchingRule(repositoryId, "main") } returns BranchProtectionRule(repositoryId = repositoryId,
            pattern = "main", requirePullRequest = true, requiredApprovals = 1)
        db {
            reviews.create(Review(pullRequestId = pr.id, reviewerId = UUID.random(), status = ReviewStatus.APPROVED))
            reviews.dismissByPullRequest(pr.id, "Source branch changed")
        }
        remote = remote.copy(state = "closed", merged = true, mergedBy = remote.user, mergeSha = "3".repeat(40), mergedAt = timestamp,
            base = remote.base.copy(sha = "3".repeat(40)))
        coEvery { writes.compareRefs(any(), any(), any()) } returns listOf(
            RefComparison("refs/heads/feature", "1".repeat(40), "1".repeat(40)),
            RefComparison("refs/heads/main", "2".repeat(40), "3".repeat(40)))
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
        assertEquals(PullRequestStatus.OPEN, db { native.findById(pr.id) }?.status)
        coVerify(exactly = 0) { writes.synchronizeRef(match { it.pullRequestMerge }) }
    }

    @Test fun `import merge preflight rejects stale versions drafts dependencies missing sources and unverifiable protection`(): Unit = runBlocking {
        val pr = create()
        suspend fun verify(id: UUID = pr.id, version: Long = pr.version, sha: String = "1".repeat(40)) = db {
            withRefSynchronizationTransaction(repositoryId, locks) { native.verifyMergeAllowed(id, version, sha) }
        }
        assertFailsWith<NoSuchElementException> { verify(id = UUID.random()) }
        assertFailsWith<IllegalStateException> { verify(version = pr.version + 1) }
        val draft = create(draft = true)
        assertFailsWith<IllegalArgumentException> { verify(id = draft.id, version = draft.version) }
        coEvery { dependencies.findDependencies(pr.id) } returns listOf(pr.copy(id = UUID.random()))
        assertFailsWith<IllegalArgumentException> { verify() }
        coEvery { dependencies.findDependencies(pr.id) } returns listOf(pr.copy(id = UUID.random(), status = PullRequestStatus.MERGED))
        assertFailsWith<IllegalArgumentException> { verify(sha = "4".repeat(40)) }
        every { dfsRepository.resolve("refs/heads/feature") } returns null
        assertFailsWith<IllegalArgumentException> { verify() }
        every { dfsRepository.resolve("refs/heads/feature") } returns org.eclipse.jgit.lib.ObjectId.fromString("1".repeat(40))
        coEvery { protections.findMatchingRule(repositoryId, "main") } returns BranchProtectionRule(repositoryId = repositoryId,
            pattern = "main", requireCodeOwnerReview = true)
        assertFailsWith<IllegalArgumentException> { verify() }
        coEvery { protections.findMatchingRule(repositoryId, "main") } returns null
        verify()
    }

    @Test fun `a completed GitHub merge cannot change the reviewed Bosca branches or source SHA`(): Unit = runBlocking {
        val pr = imported()
        val original = remote
        for (changed in listOf(original.copy(head = original.head.copy(ref = "other")),
            original.copy(base = original.base.copy(ref = "other")), original.copy(head = original.head.copy(sha = "4".repeat(40))))) {
            remote = changed.copy(state = "closed", merged = true, mergedBy = remote.user, mergeSha = "3".repeat(40), mergedAt = timestamp)
            assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
            assertEquals(PullRequestStatus.OPEN, db { native.findById(pr.id) }?.status)
        }
        coVerify(exactly = 0) { writes.synchronizeRef(match { it.pullRequestMerge }) }
    }

    @Test fun `PR metadata waits for separately authorized push evidence before transferring newer commits`(): Unit = runBlocking {
        val pr = imported()
        remote = remote.copy(title = "New title", head = remote.head.copy(sha = "4".repeat(40)))
        val receipt = delivery()
        coEvery { writes.compareRefs(any(), any(), any()) } returns listOf(
            RefComparison("refs/heads/feature", "1".repeat(40), "4".repeat(40)),
            RefComparison("refs/heads/main", "2".repeat(40), "2".repeat(40)))
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(receipt))
        assertEquals(pr.title, db { native.findById(pr.id) }?.title)
        val push = db { repository.createDelivery(GitHubDelivery(UUID.random().toString(), repositoryId, "push",
            buildJsonObject { put("ref", "refs/heads/feature"); put("before", "1".repeat(40)); put("after", "4".repeat(40)) },
            "digest", principalId = principalId)) ?: error("push") }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(receipt))
        coVerify { writes.synchronizeRef(match { it.ref == "refs/heads/feature" && it.principalId == principalId && it.triggerBuild }) }
        assertNotNull(db { repository.findPushResult(push.deliveryId) })
    }

    @Test fun `unmapped bot deleted and profileless authors remain visible until a valid mapping exists`(): Unit = runBlocking {
        db { service.unmapUser(8) }
        val receipt = delivery()
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(receipt))
        assertNull(states().single().pullRequestId); assertNotNull(states().single().problem)
        db { service.mapUser(8, principalId) }
        remote = remote.copy(user = remote.user.copy(type = "Bot"))
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(receipt))
        remote = remote.copy(user = remote.user.copy(type = "User"))
        coEvery { security.getPrincipalById(principalId) } returns Principal(id = principalId, deletedAt = timestamp)
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(receipt))
        coEvery { security.getPrincipalById(principalId) } returns Principal(id = principalId)
        coEvery { profiles.getPrimaryProfile(any()) } returns null
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(receipt))
        coEvery { profiles.getPrimaryProfile(any()) } returns Profile(id = authorId, name = "Author", type = ProfileType.GENERIC, visibility = ProfileVisibility.USER)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(receipt))
        assertNull(states().single().problem)
    }

    @Test fun `imports current title body clearing retargeting draft close reopen and completed merge`(): Unit = runBlocking {
        val pr = imported()
        for ((status, draft) in listOf("open" to true, "closed" to true, "open" to false)) {
            remote = remote.copy(title = "Current $status $draft", body = null, state = status, draft = draft)
            assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
            val current = db { native.findById(pr.id) } ?: error("missing")
            assertEquals(remote.title, current.title); assertNull(current.description)
            assertEquals(if (status == "closed") PullRequestStatus.CLOSED else if (draft) PullRequestStatus.DRAFT else PullRequestStatus.OPEN, current.status)
        }
        remote = remote.copy(state = "closed", merged = true, mergeSha = "3".repeat(40), mergedAt = timestamp,
            mergedBy = GitHubPullRequestUser(8, "merger", "User"))
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        val merged = db { native.findById(pr.id) } ?: error("missing")
        assertEquals(PullRequestStatus.MERGED, merged.status); assertEquals(remote.mergeSha, merged.mergeSha)
        assertEquals(timestamp, merged.mergedAt); assertEquals(authorId, merged.mergedBy)
        coVerify(exactly = 0) { writes.commitFile(any()) }
    }

    @Test fun `imports initial drafts but never untracked closed or merged GitHub history`(): Unit = runBlocking {
        for (status in listOf(PullRequestStatus.CLOSED, PullRequestStatus.DRAFT, PullRequestStatus.MERGED)) {
            remote = remote.copy(id = 456L + status.ordinal, number = 7 + status.ordinal,
                state = if (status == PullRequestStatus.DRAFT) "open" else "closed", draft = status == PullRequestStatus.DRAFT,
                merged = status == PullRequestStatus.MERGED, mergeSha = if (status == PullRequestStatus.MERGED) "3".repeat(40) else null,
                mergedAt = if (status == PullRequestStatus.MERGED) timestamp else null)
            val expected = if (status == PullRequestStatus.DRAFT) GitHubSyncResult.APPLIED else GitHubSyncResult.IGNORED
            assertEquals(expected, service.synchronizePullRequest(delivery(remote.number)))
        }
        assertEquals(PullRequestStatus.DRAFT, states().single().snapshot?.status)
        assertEquals(1, db { native.findByRepository(repositoryId) }.size)
        // An earlier unresolved import is not completed after its GitHub PR closes.
        db { service.unmapUser(8) }
        remote = remote().copy(id = 500, number = 20)
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery(20)))
        db { service.mapUser(8, principalId) }
        remote = remote.copy(state = "closed")
        assertEquals(GitHubSyncResult.IGNORED, service.synchronizePullRequest(delivery(20)))
        assertNull(states().single { it.githubNumber == 20 }.pullRequestId)
    }

    @Test fun `exports current native fields creates one attributed counterpart and changes its draft stage`(): Unit = runBlocking {
        val pr = create(draft = true, description = null)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals(1, created); assertTrue(remote.draft)
        assertTrue(remote.body.orEmpty().contains("Original Author")); assertFalse(remote.body.orEmpty().contains("Old title"))
        db { native.markReady(pr.id); native.update(pr.id, UpdatePullRequestInput(title = "Updated")) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertFalse(remote.draft); assertEquals("Updated", remote.title)
        val current = db { native.findById(pr.id) } ?: error("missing")
        db { native.synchronize(pr.id, current.version, GitHubPullRequestSynchronization.snapshot(current).copy(status = PullRequestStatus.DRAFT)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertTrue(remote.draft)
        db { native.close(pr.id) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr))); assertEquals("closed", remote.state)
        db { native.reopen(pr.id) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr))); assertEquals("open", remote.state)
        assertEquals(1, created)
    }

    @Test fun `lost create response keeps reservation and retry recovers counterpart after a later native edit`(): Unit = runBlocking {
        val pr = create(description = "Quoted reference: <!-- bosca-pull-request:${UUID.random()} -->")
        failAfterCreate = true
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        assertNull(states().single().githubId)
        db { native.update(pr.id, UpdatePullRequestInput(title = "Later edit")) }
        failAfterCreate = false
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals(1, created); assertEquals("Later edit", remote.title)
        assertEquals(456L, states().single().githubId)
    }

    @Test fun `lost creation recovered after a duplicate rejection preserves a later native title`(): Unit = runBlocking {
        val pr = create()
        failAfterCreate = true
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        val originalBody = remote.body
        remote = remote.copy(body = "Description")
        db { native.update(pr.id, UpdatePullRequestInput(title = "Later native title")) }
        failAfterCreate = false
        coEvery { github.createPullRequest(any(), any(), any()) } throws GitHubRequestRejectedException("HTTP 422 duplicate open PR")
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        assertEquals("Later native title", db { native.findById(pr.id) }?.title)
        remote = remote.copy(body = originalBody)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals("Later native title", db { native.findById(pr.id) }?.title)
        assertEquals("Later native title", remote.title); assertEquals(1, created)
        assertNull(states().single().problem)
    }

    @Test fun `inbound echo can recover a lost creation response without importing a duplicate native PR`(): Unit = runBlocking {
        val pr = create()
        failAfterCreate = true
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery()))
        assertEquals(pr.id, states().single().pullRequestId)
        assertEquals(1, db { native.findByRepository(repositoryId) }.size)
    }

    @Test fun `a later quoted marker cannot hide the creation reservation on an inbound echo`(): Unit = runBlocking {
        val pr = create()
        failAfterCreate = true
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        val reservation = states().single()
        assertNull(reservation.githubId)
        val note = "\n\nQuoted marker example: <!-- bosca-pull-request:${UUID.random()} -->"
        remote = remote.copy(body = remote.body + note)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals(1, db { native.findByRepository(repositoryId) }.size,
            "Recovering the original counterpart must not create another native PR")
        val recovered = states().single()
        assertEquals(reservation.id, recovered.id); assertEquals(pr.id, recovered.pullRequestId)
        assertEquals(456L, recovered.githubId)
        assertEquals("Description" + note, db { native.findById(pr.id) }?.description)
    }

    @Test fun `outbound recovery ignores a later quoted marker and retains the provider note`(): Unit = runBlocking {
        val pr = create()
        failAfterCreate = true
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        val note = "\n\nQuoted marker example: <!-- bosca-pull-request:${UUID.random()} -->"
        remote = remote.copy(body = remote.body + note)
        delivery()
        failAfterCreate = false
        coEvery { github.createPullRequest(any(), any(), any()) } throws GitHubRequestRejectedException("HTTP 422 duplicate open PR")
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals(1, created); assertEquals(456L, states().single().githubId)
        assertEquals("Description" + note, db { native.findById(pr.id) }?.description)
        assertNull(states().single().problem)
    }

    @Test fun `an unrelated complete footer after a lost creation does not hide its reservation`(): Unit = runBlocking {
        val pr = create()
        failAfterCreate = true
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        val unrelated = UUID.random()
        val note = "\n\nQuoted footer example:\n\n<!-- bosca-pull-request:$unrelated -->\n" +
            "An unrelated example.\n<!-- /bosca-pull-request:$unrelated -->"
        remote = remote.copy(body = remote.body + note)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals(1, db { native.findByRepository(repositoryId) }.size)
        assertEquals(456L, states().single().githubId)
        assertEquals("Description" + note, db { native.findById(pr.id) }?.description)
    }

    @Test fun `multiple unpaired references for the same source branch cannot choose a native counterpart`(): Unit = runBlocking {
        val first = create()
        failAfterCreate = true
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(first)) }
        val firstBody = remote.body
        val second = create()
        var secondBody: String? = null
        coEvery { github.createPullRequest(any(), any(), any()) } answers {
            secondBody = thirdArg<GitHubCreatePullRequestInput>().body
            throw IllegalStateException("Before provider write")
        }
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(second)) }
        remote = remote.copy(body = firstBody + "\n\nQuoted second body:\n" + requireNotNull(secondBody))
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
        assertEquals(2, db { native.findByRepository(repositoryId) }.size)
        val states = states()
        assertEquals(2, states.size)
        assertTrue(states.all { it.githubId == null && it.githubNumber == null })
        assertTrue(states.all { it.problem == "GitHub pull request contains multiple Bosca counterpart references" })
        assertEquals("Change", db { native.findById(first.id) }?.title)
        assertEquals("Change", db { native.findById(second.id) }?.title)
    }

    @Test fun `a copied unpaired marker cannot retarget the native PR to another source branch`(): Unit = runBlocking {
        val pr = create()
        failAfterCreate = true
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        assertNull(states().single().githubId)
        remote = remote.copy(id = 999, number = 9, title = "Independent copied PR", head = remote.head.copy(ref = "copy"))
        coEvery { writes.compareRefs(any(), any(), any()) } returns listOf(
            RefComparison("refs/heads/feature", "1".repeat(40), "1".repeat(40)),
            RefComparison("refs/heads/copy", "1".repeat(40), "1".repeat(40)),
            RefComparison("refs/heads/main", "2".repeat(40), "2".repeat(40)),
        )
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery(9)))
        val original = requireNotNull(db { native.findById(pr.id) })
        assertEquals("feature", original.sourceBranch,
            "An independent PR copying the body must not change the original native source branch")
        assertEquals("Change", original.title)
        assertNull(states().single { it.pullRequestId == pr.id }.githubId)
        val copied = states().single { it.githubNumber == 9 }
        assertNotEquals(pr.id, copied.pullRequestId)
        assertEquals(2, db { native.findByRepository(repositoryId) }.size)
    }

    @Test fun `inbound recovery cannot choose between same-source counterparts with copied references`(): Unit = runBlocking {
        val pr = create()
        failAfterCreate = true
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        val original = remote
        remote = remote.copy(id = 999, number = 9, title = "Independent copied PR", base = remote.base.copy(ref = "release"))
        coEvery { github.listPullRequests(any(), any(), any(), any()) } answers {
            if (thirdArg<Int>() == 1) listOf(original, remote) else emptyList()
        }
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery(9)))
        val state = states().single()
        assertNull(state.githubId); assertNull(state.githubNumber)
        assertEquals("Multiple GitHub counterparts contain the same Bosca reference", state.problem)
        val current = requireNotNull(db { native.findById(pr.id) })
        assertEquals("Change", current.title); assertEquals("feature", current.sourceBranch); assertEquals("main", current.targetBranch)
        assertEquals(1, db { native.findByRepository(repositoryId) }.size)
    }

    @Test fun `inbound recovery after a duplicate rejection retains incompatible native and GitHub titles`(): Unit = runBlocking {
        val pr = create()
        failAfterCreate = true
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        val originalBody = remote.body
        remote = remote.copy(body = "Description")
        db { native.update(pr.id, UpdatePullRequestInput(title = "Later native title")) }
        failAfterCreate = false
        coEvery { github.createPullRequest(any(), any(), any()) } throws GitHubRequestRejectedException("HTTP 422 duplicate open PR")
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        remote = remote.copy(body = originalBody, title = "Independent GitHub title")
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
        val state = states().single()
        assertEquals("Change", state.snapshot?.title)
        assertEquals("Later native title", state.boscaSnapshot?.title)
        assertEquals("Independent GitHub title", state.githubSnapshot?.title)
        assertEquals("Later native title", db { native.findById(pr.id) }?.title)
        assertEquals("Independent GitHub title", remote.title)
        assertEquals(pr.id, state.pullRequestId); assertEquals(1, created)
        assertEquals(1, db { native.findByRepository(repositoryId) }.size)
    }

    @Test fun `independent edits retain both snapshots and resolve only after deliberate agreement`(): Unit = runBlocking {
        val pr = imported()
        db { native.update(pr.id, UpdatePullRequestInput(title = "Bosca edit")) }
        remote = remote.copy(title = "GitHub edit")
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
        val conflict = states().single()
        assertEquals("Bosca edit", conflict.boscaSnapshot?.title); assertEquals("GitHub edit", conflict.githubSnapshot?.title)
        assertEquals("Change", conflict.snapshot?.title); assertNotNull(conflict.problem)
        remote = remote.copy(title = "Bosca edit")
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery()))
        assertNull(states().single().problem)
    }

    @Test fun `native edit racing an import fails the actual database version comparison`(): Unit = runBlocking {
        val pr = imported()
        remote = remote.copy(title = "GitHub edit")
        coEvery { writes.synchronizeRef(any()) } coAnswers {
            db { native.update(pr.id, UpdatePullRequestInput(title = "Racing Bosca edit")) }
            val input = firstArg<RefSynchronizationInput>()
            RefSynchronizationResult(GitHubSyncResult.UNCHANGED, input.afterSha, input.afterSha)
        }
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
        assertEquals("Racing Bosca edit", db { native.findById(pr.id) }?.title)
        assertNotNull(states().single().problem)
    }

    @Test fun `provider edit observed after ref transfer is preserved instead of patched`(): Unit = runBlocking {
        val pr = imported()
        db { native.update(pr.id, UpdatePullRequestInput(title = "Bosca edit")) }
        coEvery { writes.synchronizeRef(any()) } answers {
            remote = remote.copy(title = "Racing GitHub edit")
            val input = firstArg<RefSynchronizationInput>()
            RefSynchronizationResult(GitHubSyncResult.UNCHANGED, input.afterSha, input.afterSha)
        }
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        assertEquals(0, patched); assertEquals("Racing GitHub edit", remote.title)
    }

    @Test fun `native change during provider patch preserves conflict snapshots for recovery`(): Unit = runBlocking {
        val pr = imported()
        db { native.update(pr.id, UpdatePullRequestInput(title = "First edit")) }
        coEvery { github.updatePullRequest(any(), any(), any(), any()) } coAnswers {
            val input = arg<GitHubUpdatePullRequestInput>(3)
            patch(input)
            db { native.update(pr.id, UpdatePullRequestInput(title = "Second edit")) }
            remote
        }
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        assertEquals("Second edit", states().single().boscaSnapshot?.title)
        assertEquals("First edit", states().single().githubSnapshot?.title)
    }

    @Test fun `partial REST and draft stage update resumes from the committed intention`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        db { native.synchronize(pr.id, pr.version, GitHubPullRequestSynchronization.snapshot(pr).copy(title = "Draft edit", status = PullRequestStatus.DRAFT)) }
        coEvery { github.setPullRequestDraft(any(), any(), any(), any()) } throws IllegalStateException("Stage response lost")
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        assertEquals("Draft edit", remote.title); assertFalse(remote.draft)
        assertEquals(PullRequestStatus.DRAFT, states().single().pending?.status)
        coEvery { github.setPullRequestDraft(any(), any(), any(), any()) } answers { remote = remote.copy(draft = arg(3)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertTrue(remote.draft); assertNull(states().single().pending)
    }

    @Test fun `text appended after the generated attribution footer remains part of the description`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        remote = remote.copy(body = remote.body + "\n\nA new GitHub note.")
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals("Description\n\nA new GitHub note.", db { native.findById(pr.id) }?.description)
    }

    @Test fun `user content containing an incomplete footer survives a later description export`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        remote = remote.copy(body = remote.body.orEmpty().substringBefore("\n<!-- /bosca-pull-request:"))
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        val importedDescription = requireNotNull(db { native.findById(pr.id) }?.description)
        val edited = importedDescription + "\n\nAdditional user text."
        db { native.update(pr.id, UpdatePullRequestInput(description = edited)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals(edited, states().single().snapshot?.description)
        assertNull(states().single().problem)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery()))
    }

    @Test fun `a quoted complete footer survives a later description export`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        val footer = remote.body.orEmpty().substringAfter("Description")
        val edited = "Quoted footer example:" + footer + "\n\nAdditional user text."
        db { native.update(pr.id, UpdatePullRequestInput(description = edited)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals(edited, states().single().snapshot?.description)
        assertNull(states().single().problem)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery()))
    }

    @Test fun `a quoted footer appended after the managed footer remains user content`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        val footer = remote.body.orEmpty().substringAfter("Description")
        val appended = "\n\nQuoted footer example:" + footer + "\n\nAdditional user text."
        remote = remote.copy(body = remote.body + appended)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals("Description" + appended, db { native.findById(pr.id) }?.description)
        val edited = "Description" + appended + "\n\nLater native edit."
        db { native.update(pr.id, UpdatePullRequestInput(description = edited)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals(edited, states().single().snapshot?.description)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery()))
    }

    @Test fun `ambiguous complete footers remain user content and allow a later description export`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        val footer = remote.body.orEmpty().substringAfter("Description")
        val ambiguous = "Changed prefix" + footer + "\n\nQuoted copy:" + footer + "\n\nTrailing notes."
        remote = remote.copy(body = ambiguous)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals(ambiguous, db { native.findById(pr.id) }?.description)
        val edited = ambiguous + "\n\nLater native edit."
        db { native.update(pr.id, UpdatePullRequestInput(description = edited)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals(edited, states().single().snapshot?.description)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery()))
    }

    @Test fun `removing the managed footer preserves a quoted footer in the known description`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        val footer = remote.body.orEmpty().substringAfter("Description")
        val description = "Quoted footer example:" + footer + "\n\nAdditional user text."
        db { native.update(pr.id, UpdatePullRequestInput(description = description)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        remote = remote.copy(body = description)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery()))
        assertEquals(description, db { native.findById(pr.id) }?.description)
        assertEquals(description, states().single().snapshot?.description)
        assertNull(states().single().problem)
    }

    @Test fun `removing the managed footer and adding a note preserves the quoted user footer`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        val footer = remote.body.orEmpty().substringAfter("Description")
        val description = "Quoted footer example:" + footer + "\n\nOriginal user text."
        db { native.update(pr.id, UpdatePullRequestInput(description = description)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        val edited = description + "\n\nNew GitHub note."
        remote = remote.copy(body = edited)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals(edited, db { native.findById(pr.id) }?.description,
            "Quoted user content must survive when the managed footer has been removed")
        assertEquals(edited, states().single().snapshot?.description)
        val revised = edited.replace("Original Author", "Edited quotation")
        remote = remote.copy(body = revised)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals(revised, db { native.findById(pr.id) }?.description)
        assertEquals(revised, states().single().snapshot?.description)
    }

    @Test fun `branches moved only by the other host are left to their own transfer during metadata sync`(): Unit = runBlocking {
        val pr = imported()
        val main = db { repository.findRefState(repositoryId, "refs/heads/main") }
        assertEquals("2".repeat(40), main?.sha); assertEquals(true, main?.synchronized)
        // Transferring the target branch against the side that moved would not fast-forward.
        coEvery { writes.synchronizeRef(match { it.ref == "refs/heads/main" }) } answers {
            val input = firstArg<RefSynchronizationInput>()
            RefSynchronizationResult(GitHubSyncResult.CONFLICT, input.beforeSha, input.afterSha)
        }
        // Bosca's target branch has an unexported commit while GitHub edits only the title.
        coEvery { writes.compareRefs(any(), any(), any()) } returns listOf(
            RefComparison("refs/heads/feature", "1".repeat(40), "1".repeat(40)),
            RefComparison("refs/heads/main", "5".repeat(40), "2".repeat(40)),
        )
        remote = remote.copy(title = "GitHub edit")
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals("GitHub edit", db { native.findById(pr.id) }?.title)
        // GitHub's target branch moved while Bosca edits only the title.
        coEvery { writes.compareRefs(any(), any(), any()) } returns listOf(
            RefComparison("refs/heads/feature", "1".repeat(40), "1".repeat(40)),
            RefComparison("refs/heads/main", "2".repeat(40), "6".repeat(40)),
        )
        db { native.update(pr.id, UpdatePullRequestInput(title = "Bosca edit")) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals("Bosca edit", remote.title)
        val after = db { repository.findRefState(repositoryId, "refs/heads/main") }
        assertEquals("2".repeat(40), after?.sha); assertEquals(false, after?.conflict)
        assertNull(states().single().problem)
    }

    @Test fun `a branch deletion not yet exported is left to its own transfer during metadata sync`(): Unit = runBlocking {
        val pr = imported()
        assertEquals("1".repeat(40), db { repository.findRefState(repositoryId, "refs/heads/feature") }?.sha)
        // Bringing GitHub's branch back against Bosca's deletion would not apply.
        coEvery { writes.synchronizeRef(match { it.ref == "refs/heads/feature" }) } answers {
            val input = firstArg<RefSynchronizationInput>()
            RefSynchronizationResult(GitHubSyncResult.CONFLICT, null, input.afterSha)
        }
        coEvery { writes.compareRefs(any(), any(), any()) } returns listOf(
            RefComparison("refs/heads/feature", null, "1".repeat(40)),
            RefComparison("refs/heads/main", "2".repeat(40), "2".repeat(40)),
        )
        remote = remote.copy(title = "GitHub edit")
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals("GitHub edit", db { native.findById(pr.id) }?.title)
        val feature = db { repository.findRefState(repositoryId, "refs/heads/feature") }
        assertEquals("1".repeat(40), feature?.sha); assertEquals(false, feature?.conflict)
        assertNull(states().single().problem)
    }

    @Test fun `GitHub refusing the counterpart is recorded while other provider failures still fail`(): Unit = runBlocking {
        val pr = create()
        coEvery { github.createPullRequest(any(), any(), any()) } throws GitHubRequestRejectedException("GitHub pull request creation failed: HTTP 422")
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        val state = states().single()
        assertEquals("GitHub rejected the counterpart pull request", state.problem); assertNull(state.githubId)
        assertEquals(1, db { service.reconcilePullRequests(repositoryId) }.size)
        coEvery { github.createPullRequest(any(), any(), any()) } throws IllegalStateException("GitHub pull request creation failed: HTTP 502")
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        assertEquals("GitHub rejected the counterpart pull request", states().single().problem)
    }

    @Test fun `correcting a rejected creation refreshes its input without replacing its reservation`(): Unit = runBlocking {
        val pr = create()
        db { native.update(pr.id, UpdatePullRequestInput(title = "")) }
        coEvery { github.createPullRequest(any(), any(), any()) } answers {
            val input = thirdArg<GitHubCreatePullRequestInput>()
            if (input.title.isBlank()) throw GitHubRequestRejectedException("HTTP 422 invalid title")
            created++
            remote = remote.copy(title = input.title, body = input.body, draft = input.draft)
            remote
        }
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        val reservation = states().single()
        assertNull(reservation.pending); assertNull(reservation.githubId)
        db { native.update(pr.id, UpdatePullRequestInput(title = "Corrected title")) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        val recovered = states().single()
        assertEquals(reservation.id, recovered.id); assertEquals(pr.id, recovered.pullRequestId)
        assertNull(recovered.problem); assertNull(recovered.pending)
        assertEquals("Corrected title", remote.title); assertEquals(1, created)
    }

    @Test fun `correcting a rejected update refreshes its input without replacing its counterpart`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        val counterpart = states().single()
        db { native.update(pr.id, UpdatePullRequestInput(title = "")) }
        coEvery { github.updatePullRequest(any(), any(), any(), any()) } answers {
            val input = arg<GitHubUpdatePullRequestInput>(3)
            if (input.title?.isBlank() == true) throw GitHubRequestRejectedException("HTTP 422 invalid title")
            patch(input)
        }
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        val rejected = states().single()
        assertNull(rejected.pending); assertEquals(counterpart.snapshot, rejected.snapshot)
        assertEquals("GitHub rejected the counterpart pull request update", rejected.problem)
        assertEquals("", rejected.boscaSnapshot?.title); assertEquals("Change", rejected.githubSnapshot?.title)
        db { native.update(pr.id, UpdatePullRequestInput(title = "Corrected title")) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        val recovered = states().single()
        assertEquals(counterpart.id, recovered.id); assertEquals(counterpart.githubId, recovered.githubId)
        assertEquals(counterpart.githubNumber, recovered.githubNumber); assertEquals(pr.id, recovered.pullRequestId)
        assertNull(recovered.problem); assertNull(recovered.pending)
        assertEquals("Corrected title", remote.title); assertEquals(1, created)
    }

    @Test fun `correcting a rejected update still preserves a later independent GitHub edit`(): Unit = runBlocking {
        val pr = imported()
        db { native.update(pr.id, UpdatePullRequestInput(title = "")) }
        coEvery { github.updatePullRequest(any(), any(), any(), any()) } throws GitHubRequestRejectedException("HTTP 422")
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        db { native.update(pr.id, UpdatePullRequestInput(title = "Corrected title")) }
        remote = remote.copy(body = "Independent GitHub description")
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        assertEquals("Independent GitHub description", remote.body); assertEquals("Change", remote.title)
        val conflict = states().single()
        assertEquals("Corrected title", conflict.boscaSnapshot?.title)
        assertEquals("Independent GitHub description", conflict.githubSnapshot?.description)
        coVerify(exactly = 1) { github.updatePullRequest(any(), any(), any(), any()) }
    }

    @Test fun `transient and cancelled updates retain their committed intention and fail loudly`(): Unit = runBlocking {
        val pr = imported()
        db { native.update(pr.id, UpdatePullRequestInput(title = "First edit")) }
        coEvery { github.updatePullRequest(any(), any(), any(), any()) } throws IllegalStateException("HTTP 502")
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        assertEquals("First edit", states().single().pending?.title)
        coEvery { github.updatePullRequest(any(), any(), any(), any()) } throws CancellationException("Cancelled update")
        assertFailsWith<CancellationException> { service.synchronizePullRequest(event(pr)) }
        assertEquals("First edit", states().single().pending?.title)
        db { native.update(pr.id, UpdatePullRequestInput(title = "Later edit")) }
        coEvery { github.updatePullRequest(any(), any(), any(), any()) } answers { patch(arg(3)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals("Later edit", remote.title); assertNull(states().single().pending)
    }

    @Test fun `native CRLF descriptions agree without changing their stored content`(): Unit = runBlocking {
        val description = "First line\r\nSecond line"
        val pr = create(description = description)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals("First line\nSecond line", states().single().snapshot?.description)
        assertEquals(description, db { native.findById(pr.id) }?.description)
        assertEquals(pr.version, db { native.findById(pr.id) }?.version)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery()))
        assertNull(states().single().problem); assertEquals(0, patched)
    }

    @Test fun `CRLF description edits converge for native and imported counterparts without echoes`(): Unit = runBlocking {
        for (origin in listOf("Bosca", "GitHub")) {
            remote = remote().copy(id = if (origin == "Bosca") 456 else 457, number = if (origin == "Bosca") 7 else 8)
            val pr = if (origin == "Bosca") create().also { service.synchronizePullRequest(event(it)) } else imported()
            val description = "First line\r\nSecond line"
            db { native.update(pr.id, UpdatePullRequestInput(description = description)) }
            assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
            assertEquals(description, db { native.findById(pr.id) }?.description)
            val state = states().single { it.pullRequestId == pr.id }
            assertEquals("First line\nSecond line", state.snapshot?.description)
            assertNull(state.problem); assertNull(state.pending)
            val before = patched
            remote = remote.copy(body = remote.body?.replace("\n", "\r\n"))
            assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery(remote.number)))
            assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(event(pr)))
            assertEquals(before, patched)
        }
    }

    @Test fun `a lost CRLF description update resumes before exporting a later native edit`(): Unit = runBlocking {
        val pr = imported()
        db { native.update(pr.id, UpdatePullRequestInput(description = "First line\r\nSecond line")) }
        coEvery { github.updatePullRequest(any(), any(), any(), any()) } answers {
            patch(arg(3))
            throw IllegalStateException("Response lost")
        }
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        assertEquals("First line\nSecond line", states().single().pending?.description)
        db { native.update(pr.id, UpdatePullRequestInput(title = "Later edit")) }
        coEvery { github.updatePullRequest(any(), any(), any(), any()) } answers { patch(arg(3)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        val state = states().single()
        assertEquals("Later edit", state.snapshot?.title); assertEquals("First line\nSecond line", state.snapshot?.description)
        assertNull(state.pending); assertNull(state.problem)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery()))
    }

    @Test fun `title updates omit untouched provider fields and preserve a description edited just before PATCH`(): Unit = runBlocking {
        for (origin in listOf("Bosca", "GitHub")) {
            remote = remote().copy(id = if (origin == "Bosca") 456 else 457, number = if (origin == "Bosca") 7 else 8)
            val pr = if (origin == "Bosca") create().also { service.synchronizePullRequest(event(it)) } else imported()
            db { native.update(pr.id, UpdatePullRequestInput(title = "Bosca title edit")) }
            coEvery { github.updatePullRequest(any(), any(), any(), any()) } answers {
                val changes = arg<GitHubUpdatePullRequestInput>(3)
                assertEquals("Bosca title edit", changes.title)
                assertNull(changes.body); assertNull(changes.base); assertNull(changes.state)
                remote = remote.copy(body = "Concurrent GitHub description")
                patch(changes)
            }
            assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
            assertEquals("Concurrent GitHub description", remote.body)
            val conflict = states().single { it.pullRequestId == pr.id }
            assertEquals("Concurrent GitHub description", conflict.githubSnapshot?.description)
            assertEquals("Description", conflict.boscaSnapshot?.description)
            assertNotNull(conflict.problem)
        }
    }

    @Test fun `a concurrent close is preserved by a title-only patch`(): Unit = runBlocking {
        val pr = imported()
        db { native.update(pr.id, UpdatePullRequestInput(title = "Bosca title edit")) }
        coEvery { github.updatePullRequest(any(), any(), any(), any()) } answers {
            remote = remote.copy(state = "closed")
            patch(arg(3))
        }
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        assertEquals("closed", remote.state)
        assertEquals(PullRequestStatus.CLOSED, states().single().githubSnapshot?.status)
    }

    @Test fun `description-only and draft-only exports preserve unrelated fields`(): Unit = runBlocking {
        val pr = imported()
        db { native.update(pr.id, UpdatePullRequestInput(description = "Changed description")) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        coVerify { github.updatePullRequest(any(), any(), remote.number, match {
            it.body != null && it.title == null && it.base == null && it.state == null
        }) }
        val current = db { native.findById(pr.id) } ?: error("missing")
        db { native.synchronize(pr.id, current.version, GitHubPullRequestSynchronization.snapshot(current).copy(status = PullRequestStatus.DRAFT)) }
        val before = patched
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertTrue(remote.draft); assertEquals(before, patched)
        assertEquals("Change", remote.title)
    }

    @Test fun `a committed merge advances an older intention and recovers indirect merging with pending metadata`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        db { native.update(pr.id, UpdatePullRequestInput(title = "First edit")) }
        coEvery { github.getPullRequest(any(), any(), any()) } throws IllegalStateException("Provider unavailable")
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        coEvery { github.getPullRequest(any(), any(), any()) } answers { remote }
        val current = db { native.findById(pr.id) } ?: error("missing")
        db { nativeRepository.updateMergeState(current.copy(status = PullRequestStatus.MERGED, mergeSha = "3".repeat(40), mergedAt = timestamp)) }
        db { native.update(pr.id, UpdatePullRequestInput(title = "Later edit")) }
        coEvery { writes.synchronizeRef(any()) } coAnswers {
            // The merge intention was committed before any external transfer.
            val committed = db { states().single().pending }
            assertEquals(PullRequestStatus.MERGED, committed?.status)
            assertEquals("3".repeat(40), committed?.mergeSha)
            remote = remote.copy(merged = true, state = "closed", mergeSha = "3".repeat(40), mergedAt = timestamp)
            val input = firstArg<RefSynchronizationInput>()
            RefSynchronizationResult(GitHubSyncResult.UNCHANGED, input.afterSha, input.afterSha)
        }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        val state = states().single()
        assertEquals("Later edit", remote.title); assertEquals(PullRequestStatus.MERGED, state.snapshot?.status)
        assertEquals("3".repeat(40), state.snapshot?.mergeSha); assertNull(state.problem); assertNull(state.pending)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(event(pr)))
    }

    @Test fun `an indirect merge interrupted after transfer recovers without losing its earlier intention`(): Unit = runBlocking {
        val pr = imported()
        db { native.update(pr.id, UpdatePullRequestInput(title = "Pending title")) }
        coEvery { github.getPullRequest(any(), any(), any()) } throws IllegalStateException("Provider unavailable")
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        coEvery { github.getPullRequest(any(), any(), any()) } answers { remote }
        val current = db { native.findById(pr.id) } ?: error("missing")
        db { nativeRepository.updateMergeState(current.copy(status = PullRequestStatus.MERGED, mergeSha = "3".repeat(40), mergedAt = timestamp)) }
        coEvery { writes.synchronizeRef(any()) } answers {
            remote = remote.copy(merged = true, state = "closed", mergeSha = "3".repeat(40), mergedAt = timestamp)
            coEvery { github.getPullRequest(any(), any(), any()) } throws IllegalStateException("Transfer response lost")
            val input = firstArg<RefSynchronizationInput>()
            RefSynchronizationResult(GitHubSyncResult.UNCHANGED, input.afterSha, input.afterSha)
        }
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        coEvery { github.getPullRequest(any(), any(), any()) } answers { remote }
        coEvery { writes.synchronizeRef(any()) } answers {
            val input = firstArg<RefSynchronizationInput>()
            RefSynchronizationResult(GitHubSyncResult.UNCHANGED, input.afterSha, input.afterSha)
        }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals("Pending title", remote.title); assertNull(states().single().problem)
    }

    @Test fun `indirect merging cannot hide an unrelated description edit or another merge SHA`(): Unit = runBlocking {
        val pr = imported()
        db { nativeRepository.updateMergeState(pr.copy(status = PullRequestStatus.MERGED, mergeSha = "3".repeat(40), mergedAt = timestamp)) }
        val original = remote
        for (changed in listOf(original.copy(body = "Independent edit"), original.copy(mergeSha = "4".repeat(40)))) {
            remote = original
            coEvery { writes.synchronizeRef(any()) } answers {
                remote = changed.copy(merged = true, state = "closed", mergeSha = changed.mergeSha ?: "3".repeat(40), mergedAt = timestamp)
                val input = firstArg<RefSynchronizationInput>()
                RefSynchronizationResult(GitHubSyncResult.UNCHANGED, input.afterSha, input.afterSha)
            }
            assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
            assertNotNull(states().single().problem)
        }
        assertEquals(0, patched)
    }

    @Test fun `a tracked PR completed before its counterpart exists searches GitHub once until it is reopened`(): Unit = runBlocking {
        val pr = create()
        coEvery { github.createPullRequest(any(), any(), any()) } throws IllegalStateException("GitHub pull request creation failed: HTTP 502")
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        assertNull(states().single().githubId)
        db { native.close(pr.id) }
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        coVerify(exactly = 2) { github.listPullRequests(any(), any(), 1, any()) }
        repeat(2) { assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr))) }
        coVerify(exactly = 2) { github.listPullRequests(any(), any(), 1, any()) }
        coEvery { github.createPullRequest(any(), any(), any()) } answers {
            val input = thirdArg<GitHubCreatePullRequestInput>()
            created++
            remote = remote.copy(title = input.title, body = input.body, draft = input.draft,
                head = remote.head.copy(ref = input.head), base = remote.base.copy(ref = input.base))
            remote
        }
        assertEquals("A completed pull request has no GitHub counterpart", states().single().problem)
        db { native.reopen(pr.id) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals(1, created); assertNull(states().single().problem)
    }

    @Test fun `GitHub CRLF line endings still separate the generated footer and its recorded merge`(): Unit = runBlocking {
        val pr = create(description = "First line\nSecond line")
        service.synchronizePullRequest(event(pr))
        remote = remote.copy(body = remote.body.orEmpty().replace("First line", "Edited line").replace("\n", "\r\n"))
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals("Edited line\nSecond line", db { native.findById(pr.id) }?.description)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(event(pr)))
        assertEquals(1, Regex("<!-- bosca-pull-request:").findAll(remote.body.orEmpty()).count())
        val current = db { native.findById(pr.id) } ?: error("missing")
        db { nativeRepository.updateMergeState(current.copy(status = PullRequestStatus.MERGED, mergeSha = "3".repeat(40),
            mergedAt = timestamp, mergedBy = authorId)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        remote = remote.copy(body = remote.body.orEmpty().replace("\n", "\r\n"))
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery()))
        val state = states().single()
        assertNull(state.problem); assertEquals(PullRequestStatus.MERGED, state.snapshot?.status)
    }

    @Test fun `an intention left by a failed attempt is completed before a newer native edit is exported`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        db { native.update(pr.id, UpdatePullRequestInput(title = "First edit")) }
        coEvery { github.getPullRequest(any(), any(), any()) } throws IllegalStateException("Provider unavailable")
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        assertEquals("First edit", states().single().pending?.title)
        coEvery { github.getPullRequest(any(), any(), any()) } answers { remote }
        val current = db { native.findById(pr.id) } ?: error("missing")
        db { native.synchronize(pr.id, current.version, GitHubPullRequestSynchronization.snapshot(current)
            .copy(title = "Second edit", status = PullRequestStatus.DRAFT)) }
        coEvery { github.setPullRequestDraft(any(), any(), any(), any()) } throws IllegalStateException("Stage response lost")
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        assertEquals("Second edit", remote.title); assertFalse(remote.draft)
        assertEquals("Second edit", states().single().pending?.title)
        coEvery { github.setPullRequestDraft(any(), any(), any(), any()) } answers { remote = remote.copy(draft = arg(3)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals("Second edit", remote.title); assertTrue(remote.draft)
        val state = states().single()
        assertNull(state.problem); assertNull(state.pending); assertEquals("Second edit", state.snapshot?.title)
    }

    @Test fun `a recovered lost create reads the full counterpart so a GitHub merge is imported as a merge`(): Unit = runBlocking {
        val pr = create()
        failAfterCreate = true
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        failAfterCreate = false
        remote = remote.copy(state = "closed", merged = true, mergeSha = "3".repeat(40), mergedAt = timestamp, mergedBy = remote.user)
        delivery()
        // GitHub's listing omits the merge state and merging user.
        coEvery { github.listPullRequests(any(), any(), any(), any()) } answers {
            if (thirdArg<Int>() == 1) listOf(remote.copy(merged = false, mergedBy = null)) else emptyList()
        }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        val merged = db { native.findById(pr.id) } ?: error("missing")
        assertEquals(PullRequestStatus.MERGED, merged.status); assertEquals("3".repeat(40), merged.mergeSha)
        assertEquals(authorId, merged.mergedBy)
    }

    @Test fun `duplicate references and identity mismatches cannot adopt an unrelated counterpart`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        val state = states().single()
        val counterpart = remote
        // A copied description carries the paired reference but is an independent GitHub PR.
        remote = remote.copy(id = 999, number = 9, head = remote.head.copy(ref = "copy"))
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery(9)))
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery(9)))
        assertEquals(state, states().single { it.id == state.id })
        val copy = states().single { it.githubNumber == 9 }
        assertNotEquals(pr.id, copy.pullRequestId); assertNull(copy.problem)
        remote = counterpart
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(event(pr)))
        remote = remote.copy(id = 999)
        assertFailsWith<IllegalStateException> { service.synchronizePullRequest(event(pr)) }
        db { repository.savePullRequestState(state.copy(githubId = null, githubNumber = null)) }
        coEvery { github.listPullRequests(any(), any(), any(), any()) } answers {
            if (thirdArg<Int>() == 1) listOf(remote, remote.copy(id = 1000, number = 10)) else emptyList()
        }
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
    }

    @Test fun `deleted source branches are skipped but missing target branches block import`(): Unit = runBlocking {
        coEvery { writes.compareRefs(any(), any(), any()) } returns listOf(RefComparison("refs/heads/main", "2".repeat(40), "2".repeat(40)))
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        remote = remote.copy(title = "Changed")
        coEvery { writes.compareRefs(any(), any(), any()) } returns emptyList()
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
        coEvery { writes.compareRefs(any(), any(), any()) } returns listOf(
            RefComparison("refs/heads/feature", null, null), RefComparison("refs/heads/main", "2".repeat(40), null))
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
    }

    @Test fun `foreign Bosca forks and incorrectly routed PR events cannot create counterpart reservations`(): Unit = runBlocking {
        val other = UUID.random()
        db { connection().useStatement("insert into git.repositories(id) values ('$other')") { it.execute() } }
        val fork = db { native.create(CreatePullRequestInput(repositoryId, "Fork", sourceBranch = "feature", targetBranch = "main", sourceRepositoryId = other), authorId) }
        assertEquals(GitHubSyncResult.IGNORED, service.synchronizePullRequest(event(fork)))
        val foreign = db { nativeRepository.create(PullRequest(repositoryId = other, number = 1, title = "Foreign", authorId = authorId,
            sourceBranch = "feature", targetBranch = "main")) }
        assertFailsWith<IllegalArgumentException> { service.synchronizePullRequest(event(foreign)) }
        assertTrue(states().isEmpty()); assertEquals(0, created)
    }

    @Test fun `GitHub ready-for-review transitions use the native owning service`(): Unit = runBlocking {
        remote = remote.copy(draft = true)
        val pr = imported()
        assertEquals(PullRequestStatus.DRAFT, pr.status)
        remote = remote.copy(draft = false)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals(PullRequestStatus.OPEN, db { native.findById(pr.id) }?.status)
    }

    @Test fun `native forks and incompatible source branches remain outside automatic synchronization`(): Unit = runBlocking {
        val fork = db { native.create(CreatePullRequestInput(repositoryId, "Fork", sourceBranch = "feature", targetBranch = "main", sourceRepositoryId = repositoryId), authorId) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(fork)))
        val current = db { native.findById(fork.id) } ?: error("missing")
        db { native.synchronize(fork.id, current.version, GitHubPullRequestSynchronization.snapshot(current).copy(sourceBranch = "other")) }
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(fork)))
        remote = remote.copy(base = remote.base.copy(repo = null))
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(fork)))
        assertEquals(GitHubSyncResult.IGNORED, service.synchronizePullRequest(delivery()))
    }

    @Test fun `squash and rebase merges close counterpart with exact originating SHA instead of generating another merge`(): Unit = runBlocking {
        for ((index, strategy) in listOf(MergeStrategy.SQUASH, MergeStrategy.REBASE).withIndex()) {
            remote = remote().copy(id = 456L + index, number = 7 + index)
            val pr = create()
            service.synchronizePullRequest(event(pr))
            val current = db { native.findById(pr.id) } ?: error("missing")
            db { nativeRepository.updateMergeState(current.copy(status = PullRequestStatus.MERGED, mergeStrategy = strategy,
                mergeSha = "3".repeat(40), mergedAt = timestamp, mergedBy = authorId)) }
            assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
            assertEquals("closed", remote.state); assertFalse(remote.merged)
            assertTrue(remote.body.orEmpty().contains("Merged in Bosca at `${"3".repeat(40)}`"))
            assertEquals(PullRequestStatus.MERGED, states().single { it.pullRequestId == pr.id }.snapshot?.status)
            assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePullRequest(delivery(remote.number)))
        }
    }

    @Test fun `indirect merge recognized by GitHub preserves the identical commit`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        db { nativeRepository.updateMergeState(pr.copy(status = PullRequestStatus.MERGED, mergeSha = "3".repeat(40), mergedAt = timestamp)) }
        coEvery { writes.synchronizeRef(any()) } answers {
            remote = remote.copy(merged = true, state = "closed", mergeSha = "3".repeat(40), mergedAt = timestamp)
            val input = firstArg<RefSynchronizationInput>()
            RefSynchronizationResult(GitHubSyncResult.UNCHANGED, input.afterSha, input.afterSha)
        }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals("3".repeat(40), states().single().snapshot?.mergeSha)
    }

    @Test fun `completed merge cannot be reopened rewritten or reverted from either host`(): Unit = runBlocking {
        val pr = imported()
        db { nativeRepository.updateMergeState(pr.copy(status = PullRequestStatus.MERGED, mergeSha = "3".repeat(40), mergedAt = timestamp)) }
        service.synchronizePullRequest(event(pr))
        remote = remote.copy(state = "open")
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
        assertEquals(PullRequestStatus.MERGED, db { native.findById(pr.id) }?.status)
    }

    @Test fun `the provider merge SHA takes precedence over a Bosca merge recorded in its footer`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        db { nativeRepository.updateMergeState(pr.copy(status = PullRequestStatus.MERGED, mergeSha = "3".repeat(40), mergedAt = timestamp)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertFalse(remote.merged); assertTrue(remote.body.orEmpty().contains("Merged in Bosca at `${"3".repeat(40)}`"))
        remote = remote.copy(merged = true, mergeSha = "4".repeat(40), mergedAt = timestamp)
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
        val state = states().single()
        assertEquals("3".repeat(40), state.snapshot?.mergeSha)
        assertEquals("4".repeat(40), state.githubSnapshot?.mergeSha)
        assertEquals("3".repeat(40), db { native.findById(pr.id) }?.mergeSha)
    }

    @Test fun `initially conflicting counterpart surfaces a problem and untracked completed native history is not exported`(): Unit = runBlocking {
        val pr = create()
        val state = GitHubPullRequestState(repositoryId = repositoryId, pullRequestId = pr.id, githubId = 456, githubNumber = 7)
        db { repository.savePullRequestState(state) }
        remote = remote.copy(title = "Different")
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        for (status in listOf(PullRequestStatus.CLOSED, PullRequestStatus.MERGED)) {
            val completed = create()
            if (status == PullRequestStatus.CLOSED) db { native.close(completed.id) }
            else db { nativeRepository.updateMergeState(completed.copy(status = status, mergeSha = "3".repeat(40), mergedAt = timestamp)) }
            assertEquals(GitHubSyncResult.IGNORED, service.synchronizePullRequest(event(completed)))
            assertTrue(states().none { it.pullRequestId == completed.id })
        }
        db { service.reconcilePullRequests(repositoryId) }
        assertEquals(1, states().size)
        coVerify(exactly = 0) { github.listPullRequests(any(), any(), any(), isNull(inverse = true)) }
        coVerify(exactly = 0) { github.createPullRequest(any(), any(), any()) }
    }

    @Test fun `branch conflicts block creation and metadata updates without overwriting history`(): Unit = runBlocking {
        coEvery { writes.synchronizeRef(any()) } returns RefSynchronizationResult(GitHubSyncResult.CONFLICT, "a", "b")
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
        assertNull(states().single().pullRequestId)
        val pr = create()
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        assertEquals(0, created)
    }

    @Test fun `fork disabled ignored nonexistent and mismatched occurrences cannot mutate counterpart state`(): Unit = runBlocking {
        remote = remote.copy(head = remote.head.copy(repo = GitHubWebhookRepository(999)))
        assertEquals(GitHubSyncResult.IGNORED, service.synchronizePullRequest(delivery()))
        assertEquals(GitHubSyncResult.IGNORED, service.synchronizePullRequest(delivery(event = "push")))
        assertEquals(GitHubSyncResult.IGNORED, service.synchronizePullRequest(delivery(ignored = true)))
        val missing = GitHubDelivery(UUID.random().toString(), repositoryId, "pull_request", JsonNull, "digest")
        assertFailsWith<NoSuchElementException> { service.synchronizePullRequest(missing) }
        assertEquals(GitHubSyncResult.IGNORED, service.synchronizePullRequest(event(create()).copy(pullRequestId = UUID.random())))
        assertTrue(states().isEmpty())
    }

    @Test fun `reconciliation requires verified PR authority and pages through stored mappings`(): Unit = runBlocking {
        created = 1
        val blocked = db { service.reconcilePullRequests(repositoryId) }
        assertNull(blocked.single().pullRequestId); assertNotNull(blocked.single().problem)
        delivery()
        val states = db { service.reconcilePullRequests(repositoryId) }
        assertEquals(1, states.size); assertNotNull(states.single().pullRequestId)
        coVerify { writes.synchronizeRef(match { !it.triggerBuild }) }
        assertTrue(db { service.findPullRequestStates(repositoryId, 1, 25) }.isEmpty())
        assertFailsWith<IllegalArgumentException> { db { service.findPullRequestStates(repositoryId, -1, 25) } }
        assertFailsWith<IllegalArgumentException> { db { service.findPullRequestStates(repositoryId, 0, 101) } }
        assertEquals(1, db { service.reconcilePullRequests() }.size)
        assertTrue(db { service.reconcilePullRequests(UUID.random()) }.isEmpty())
    }

    @Test fun `reconciliation imports only open GitHub PRs and keeps synchronizing paired completed ones`(): Unit = runBlocking {
        val paired = imported()
        val closed = remote().copy(id = 457, number = 8, state = "closed")
        val merged = remote().copy(id = 458, number = 9, state = "closed", merged = true, mergeSha = "3".repeat(40), mergedAt = timestamp)
        val open = remote().copy(id = 459, number = 10)
        db { repository.savePullRequestState(GitHubPullRequestState(repositoryId = repositoryId, githubId = 457, githubNumber = 8,
            problem = "Author mapping needed")) }
        remote = remote.copy(state = "closed")
        delivery()
        db { repository.createDelivery(GitHubDelivery(UUID.random().toString(), repositoryId, "pull_request",
            buildJsonObject { put("number", open.number); put("pull_request", json.encodeToJsonElement(GitHubPullRequest.serializer(), open)) },
            "digest", principalId = principalId)) }
        coEvery { github.listPullRequests(any(), any(), any(), any()) } answers {
            if (thirdArg<Int>() == 1) listOf(remote, closed, merged, open) else emptyList()
        }
        coEvery { github.getPullRequest(any(), any(), any()) } answers {
            listOf(remote, closed, merged, open).first { it.number == thirdArg<Int>() }
        }
        db { service.reconcilePullRequests(repositoryId) }
        assertEquals(PullRequestStatus.CLOSED, db { native.findById(paired.id) }?.status)
        assertEquals(2, db { native.findByRepository(repositoryId) }.size)
        val states = states()
        assertNull(states.single { it.githubNumber == 8 }.pullRequestId)
        assertNotNull(states.single { it.githubNumber == 10 }.pullRequestId)
        assertTrue(states.none { it.githubNumber == 9 })
    }

    @Test fun `one failing PR does not starve the other PRs in its paired repository`(): Unit = runBlocking {
        val second = remote.copy(id = 457, number = 8)
        coEvery { github.listPullRequests(any(), any(), any(), any()) } answers {
            if (thirdArg<Int>() == 1) listOf(remote, second) else emptyList()
        }
        coEvery { github.getPullRequest(any(), any(), 7) } throws IllegalStateException("First PR unavailable")
        coEvery { github.getPullRequest(any(), any(), 8) } returns second
        assertFailsWith<IllegalStateException> { db { service.reconcilePullRequests(repositoryId) } }
        assertEquals(8, states().single().githubNumber)
        coEvery { github.getPullRequest(any(), any(), 7) } throws CancellationException("cancel")
        assertFailsWith<CancellationException> { db { service.reconcilePullRequests(repositoryId) } }
    }

    @Test fun `all-pair reconciliation accumulates failures and returns every page of recorded states`(): Unit = runBlocking {
        db {
            for (number in 1..101) repository.savePullRequestState(GitHubPullRequestState(repositoryId = repositoryId,
                githubId = 1000L + number, githubNumber = number, problem = "Author mapping needed"))
        }
        assertEquals(101, db { service.reconcilePullRequests(repositoryId) }.size)
        val other = UUID.random()
        coEvery { hostedService.findById(other) } returns hosted.copy(id = other)
        db {
            connection().useStatement("insert into git.repositories(id) values ('$other')") { it.execute() }
            service.savePair(GitHubRepositoryPairInput(other, 124, "owner", "other", "webhook", "token", true))
        }
        coEvery { github.listPullRequests(any(), any(), any(), any()) } throws IllegalStateException("Unavailable")
        assertEquals(2, db { repository.findEnabledPairs() }.size)
        db {
            val failure = assertFailsWith<IllegalStateException> { service.reconcilePullRequests() }
            assertEquals(1, failure.suppressed.size)
        }
    }

    @Test fun `reconciliation preserves cancellation and reports provider failures`(): Unit = runBlocking {
        coEvery { github.listPullRequests(any(), any(), any(), any()) } throws IllegalStateException("Provider unavailable")
        assertFailsWith<IllegalStateException> { db { service.reconcilePullRequests() } }
        coEvery { github.listPullRequests(any(), any(), any(), any()) } throws CancellationException("cancel")
        assertFailsWith<CancellationException> { db { service.reconcilePullRequests(repositoryId) } }
    }

    @Test fun `a pending outbound write never accepts independently changed provider fields`(): Unit = runBlocking {
        val changes: List<(GitHubPullRequest) -> GitHubPullRequest> = listOf(
            { it.copy(body = "Independent description") },
            { it.copy(head = it.head.copy(ref = "Independent source")) },
            { it.copy(base = it.base.copy(ref = "Independent target")) },
            { it.copy(draft = true) },
        )
        for ((index, change) in changes.withIndex()) {
            remote = remote().copy(id = 456L + index, number = 7 + index)
            val pr = imported()
            db { native.update(pr.id, UpdatePullRequestInput(title = "Bosca edit")) }
            remote = change(remote)
            assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery(remote.number)))
        }
        remote = remote().copy(id = 999, number = 99)
        val pr = imported()
        db { nativeRepository.updateMergeState(pr.copy(status = PullRequestStatus.MERGED, mergeSha = "3".repeat(40), mergedAt = timestamp)) }
        remote = remote.copy(merged = true, state = "closed", mergeSha = "4".repeat(40), mergedAt = timestamp)
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery(99)))
    }

    @Test fun `missing author display information never changes the native author identity`(): Unit = runBlocking {
        val pr = create()
        coEvery { profiles.getAllByIds(any()) } returns emptyList()
        coEvery { security.getPrincipalById(authorId) } returns Principal(id = authorId)
        service.synchronizePullRequest(event(pr))
        assertTrue(remote.body.orEmpty().contains("Original Author"))
        val second = create()
        coEvery { security.getPrincipalById(authorId) } returns null
        remote = remote.copy(id = 457, number = 8)
        created = 0
        service.synchronizePullRequest(event(second))
        assertTrue(remote.body.orEmpty().contains("the original author"))
        assertEquals(authorId, db { native.findById(second.id) }?.authorId)
    }

    @Test fun `missing merge-user mapping is not replaced by the integration identity`(): Unit = runBlocking {
        val merge: (GitHubPullRequestUser) -> GitHubPullRequest = {
            remote.copy(merged = true, state = "closed", mergeSha = "3".repeat(40), mergedAt = timestamp, mergedBy = it)
        }
        val pr = imported()
        remote = merge(GitHubPullRequestUser(999, "unknown", "User"))
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
        assertNull(db { native.findById(pr.id) }?.mergedBy)
        remote = remote().copy(id = 457, number = 8)
        val bot = imported()
        remote = merge(GitHubPullRequestUser(8, "bot", "Bot"))
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery(8)))
        assertNull(db { native.findById(bot.id) }?.mergedBy)
        assertTrue(states().all { it.snapshot?.status == PullRequestStatus.OPEN && it.problem != null })
    }

    @Test fun `provider mismatch after PATCH is visible and a missing generated footer is treated as user content`(): Unit = runBlocking {
        val pr = create()
        service.synchronizePullRequest(event(pr))
        remote = remote.copy(body = remote.body.orEmpty().substringBefore("\n<!-- /bosca-pull-request:"))
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals(remote.body, db { native.findById(pr.id) }?.description)
        db { native.update(pr.id, UpdatePullRequestInput(title = "Bosca edit")) }
        coEvery { github.updatePullRequest(any(), any(), any(), any()) } answers { remote }
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(event(pr)))
        remote = remote.copy(body = null)
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePullRequest(delivery()))
    }

    @Test fun `stale native repository writes cannot replace synchronized metadata`(): Unit = runBlocking {
        val pr = create()
        db {
            val updated = nativeRepository.update(pr.copy(title = "First")) ?: error("update")
            assertEquals(1L, updated.version)
            assertNull(nativeRepository.update(pr.copy(title = "Stale")))
            assertNull(nativeRepository.updateStatus(pr.id, PullRequestStatus.CLOSED, pr.version))
            assertNull(nativeRepository.updateMergeState(pr.copy(status = PullRequestStatus.MERGED)))
            assertEquals("First", native.findById(pr.id)?.title)
        }
    }

    @Test fun `filtered matching refs allow PR metadata updates in both directions`(): Unit = runBlocking {
        val pr = create()
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        db {
            val pair = checkNotNull(service.findPair(repositoryId))
            service.savePair(GitHubRepositoryPairInput(repositoryId, 123, "owner", "source", "webhook", "token", true,
                pair.version, pushBranchExcludes = listOf("main"), pullBranchExcludes = listOf("main")))
            native.update(pr.id, UpdatePullRequestInput(title = "Updated title"))
        }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(event(pr)))
        assertEquals("Updated title", remote.title)
        remote = remote.copy(title = "Inbound title")
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePullRequest(delivery()))
        assertEquals("Inbound title", db { native.findById(pr.id) }?.title)
    }
}
