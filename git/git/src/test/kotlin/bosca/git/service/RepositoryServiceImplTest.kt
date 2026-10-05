package bosca.git.service

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext
import bosca.cache.serializers.UUIDKeySerializer
import bosca.di.provides
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.CreateRepositoryInput
import bosca.git.model.ForkRepositoryInput
import bosca.git.model.MergeStrategy
import bosca.git.model.Repository
import bosca.git.model.RepositoryConfiguration
import bosca.git.model.RepositoryPermission
import bosca.git.model.DfsRef
import bosca.git.model.StorageQuotaExceededException
import bosca.git.model.UpdateRepositoryInput
import bosca.git.model.Visibility
import bosca.git.repository.DfsRefRepository
import bosca.git.repository.GitRepositoryRepository
import bosca.git.repository.RepositoryPermissionRepository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.eclipse.jgit.internal.storage.dfs.DfsRepository
import org.eclipse.jgit.lib.ObjectId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RepositoryServiceImplTest {

    private val repoRepository = mockk<GitRepositoryRepository>()
    private val permissionRepository = mockk<RepositoryPermissionRepository>(relaxed = true)
    private val dfsRefRepository = mockk<DfsRefRepository>()
    private val initializer = mockk<RepositoryInitializer>(relaxed = true)
    private val slugService = mockk<SlugService>()
    private val profileService = mockk<bosca.profile.profile.service.ProfileService>(relaxed = true)
    private val securityService = mockk<bosca.security.service.SecurityService>(relaxed = true)
    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val gitRepository = mockk<DfsRepository>(relaxed = true)
    private val refUpdateNotifier = mockk<RefUpdateNotifier>(relaxed = true)
    private val repositoryIndexEnqueuer = mockk<JobConfigurationEnqueuer>(relaxed = true)
    private lateinit var service: RepositoryServiceImpl
    private lateinit var cacheManager: CacheManager
    private lateinit var requestCache: RequestCache

    @BeforeTest
    fun setup() {
        cacheManager = mockk(relaxed = true)
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<JobConfigurationEnqueuer>(name = "repository-index", singleton = true) {
            repositoryIndexEnqueuer
        }
        requestCache = RequestCache(cacheManager, mockk<RequestCacheSerializer>(relaxed = true))
        coEvery { repoRepository.sumDiskSizeByOwner(any()) } returns 0L
        coEvery { initializer.initialize(any(), any()) } returns 0L
        every { dfsManager.open(any()) } returns gitRepository
        service = RepositoryServiceImpl(
            repoRepository,
            permissionRepository,
            dfsRefRepository,
            initializer,
            slugService,
            profileService,
            securityService,
            kotlinx.serialization.json.Json,
            dfsManager,
            refUpdateNotifier,
        )
    }

    private suspend fun <T> withCacheContext(block: suspend () -> T): T {
        return withContext(requestCache.asCoroutineContext()) { block() }
    }

    private val ownerId = UUID.random()
    private val repoId = UUID.random()

    private fun testRepo(
        id: UUID = repoId,
        slug: String = "test-repo",
        visibility: Visibility = Visibility.PRIVATE
    ) = Repository(
        id = id,
        slug = slug,
        name = "Test Repository",
        ownerId = ownerId,
        visibility = visibility
    )

    @Test
    fun `create validates slug format`() = runTest {
        val input = CreateRepositoryInput(
            slug = "INVALID SLUG!",
            name = "Bad Repo",
            ownerId = ownerId,
        )
        assertFailsWith<IllegalArgumentException> {
            service.create(input)
        }
    }

    @Test
    fun `create persists repository with correct fields`() = runTest {
        val input = CreateRepositoryInput(
            slug = "my-repo",
            name = "My Repository",
            description = "A test repo",
            ownerId = ownerId,

            visibility = Visibility.PUBLIC,
            defaultBranch = "develop"
        )
        val captured = slot<Repository>()
        coEvery { repoRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = repoId)
        }

        val result = service.create(input)
        assertEquals("my-repo", result.slug)
        assertEquals("My Repository", result.name)
        assertEquals(Visibility.PUBLIC, result.visibility)
        assertEquals("develop", result.defaultBranch)
    }

    @Test
    fun `update preserves existing fields when input fields are null`() = runTest {
        val existing = testRepo()
        coEvery { repoRepository.findById(repoId) } returns existing
        coEvery { repoRepository.update(any()) } answers { firstArg() }

        val result = service.update(repoId, UpdateRepositoryInput(name = "New Name"))
        assertEquals("New Name", result.name)
        assertEquals(existing.description, result.description)
        assertEquals(existing.visibility, result.visibility)
    }

    @Test
    fun `update throws when repository not found`() = runTest {
        coEvery { repoRepository.findById(any()) } returns null
        assertFailsWith<IllegalArgumentException> {
            service.update(UUID.random(), UpdateRepositoryInput(name = "x"))
        }
    }

    @Test
    fun `rename validates slug format`() = runTest {
        // The slug format is validated before any lookup, so a malformed slug fails fast.
        assertFailsWith<IllegalArgumentException> {
            service.rename(repoId, "INVALID SLUG!")
        }
    }

    @Test
    fun `rename throws when repository not found`() = runTest {
        coEvery { repoRepository.findById(repoId) } returns null
        assertFailsWith<IllegalArgumentException> {
            service.rename(repoId, "new-slug")
        }
    }

    @Test
    fun `rename is a no-op when the slug is unchanged`() = runTest {
        val existing = testRepo(slug = "same-slug")
        coEvery { repoRepository.findById(repoId) } returns existing
        val result = service.rename(repoId, "same-slug")
        assertEquals(existing, result)
        coVerify(exactly = 0) { repoRepository.findByOwnerAndSlug(any(), any()) }
        coVerify(exactly = 0) { repoRepository.updateSlug(any(), any()) }
    }

    @Test
    fun `rename rejects a slug already taken in the owner namespace`() = runTest {
        val existing = testRepo(slug = "old-slug")
        coEvery { repoRepository.findById(repoId) } returns existing
        coEvery { repoRepository.findByOwnerAndSlug(ownerId, "taken") } returns testRepo(id = UUID.random(), slug = "taken")
        assertFailsWith<IllegalArgumentException> {
            service.rename(repoId, "taken")
        }
        coVerify(exactly = 0) { repoRepository.updateSlug(any(), any()) }
    }

    @Test
    fun `rename updates the slug when the new slug is free`() = runTest {
        val existing = testRepo(slug = "old-slug")
        coEvery { repoRepository.findById(repoId) } returns existing
        coEvery { repoRepository.findByOwnerAndSlug(ownerId, "new-slug") } returns null
        coEvery { repoRepository.updateSlug(repoId, "new-slug") } returns existing.copy(slug = "new-slug")

        val result = service.rename(repoId, "new-slug")

        assertEquals("new-slug", result.slug)
        coVerify { repoRepository.updateSlug(repoId, "new-slug") }
    }

    @Test
    fun `rename throws when the row vanishes before the slug update`() = runTest {
        val existing = testRepo(slug = "old-slug")
        coEvery { repoRepository.findById(repoId) } returns existing
        coEvery { repoRepository.findByOwnerAndSlug(ownerId, "new-slug") } returns null
        coEvery { repoRepository.updateSlug(repoId, "new-slug") } returns null
        assertFailsWith<IllegalArgumentException> {
            service.rename(repoId, "new-slug")
        }
    }

    @Test
    fun `archive delegates to repository`() = runTest {
        val archived = testRepo().copy(archived = true)
        coEvery { repoRepository.archive(repoId) } returns archived
        val result = service.archive(repoId)
        assertEquals(true, result.archived)
    }

    @Test
    fun `delete soft-deletes repository`() = runTest {
        val deleted = testRepo().copy(deleted = true)
        coEvery { repoRepository.softDelete(repoId) } returns deleted
        val result = service.delete(repoId)
        assertEquals(true, result.deleted)
    }

    @Test
    fun `restore clears soft-delete`() = runTest {
        val restored = testRepo()
        coEvery { repoRepository.restore(repoId) } returns restored
        val result = service.restore(repoId)
        assertEquals(false, result.deleted)
    }

    @Test
    fun `transfer updates owner`() = runTest {
        val newOwnerId = UUID.random()
        val transferred = testRepo().copy(ownerId = newOwnerId)
        coEvery { repoRepository.transfer(repoId, newOwnerId) } returns transferred
        val result = service.transfer(repoId, newOwnerId)
        assertEquals(newOwnerId, result.ownerId)
    }

    @Test
    fun `fork copies refs from source to new repository`() = runTest {
        val source = testRepo(slug = "source-repo")
        val forkId = UUID.random()
        val newOwnerId = UUID.random()

        coEvery { repoRepository.findById(source.id) } returns source
        coEvery { repoRepository.create(any()) } answers {
            (firstArg() as Repository).copy(id = forkId)
        }
        coEvery { dfsRefRepository.findAll(source.id) } returns listOf(
            DfsRef(repositoryId = source.id, name = "refs/heads/main", objectId = "abc123")
        )
        coEvery { dfsRefRepository.upsert(any()) } answers { firstArg() }
        coEvery { repoRepository.updateDiskSize(any(), any()) } returns Unit
        coEvery { repoRepository.sumDiskSizeByOwner(newOwnerId) } returns 0L

        val input = ForkRepositoryInput(
            sourceRepositoryId = source.id,
            newOwnerId = newOwnerId,
        )
        val fork = service.fork(input)
        assertEquals(forkId, fork.id)
        assertEquals(source.id, fork.forkedFromId)

        coVerify {
            dfsRefRepository.upsert(match { it.repositoryId == forkId && it.name == "refs/heads/main" })
        }
    }

    @Test
    fun `fork throws when source not found`() = runTest {
        coEvery { repoRepository.findById(any()) } returns null
        assertFailsWith<IllegalArgumentException> {
            service.fork(ForkRepositoryInput(
                sourceRepositoryId = UUID.random(),
                newOwnerId = UUID.random()
            ))
        }
    }

    @Test
    fun `findByOwnerAndSlug delegates to repository`() = runTest {
        val repo = testRepo()
        coEvery { slugService.get("owner") } returns Slug(slug = "owner", profileId = ownerId)
        coEvery { repoRepository.findByOwnerAndSlug(ownerId, "repo") } returns repo
        assertEquals(repo, service.findByOwnerAndSlug("owner", "repo"))
    }

    @Test
    fun `findByOwnerAndSlug returns null when slug not found`() = runTest {
        coEvery { slugService.get("unknown") } returns null
        assertNull(service.findByOwnerAndSlug("unknown", "repo"))
    }

    @Test
    fun `findByOwnerAndSlug returns null when slug has no profile`() = runTest {
        coEvery { slugService.get("content-slug") } returns Slug(slug = "content-slug", metadataId = UUID.random())
        assertNull(service.findByOwnerAndSlug("content-slug", "repo"))
    }

    @Test
    fun `addPermission grants and invalidates cache`() = runTest {
        withCacheContext {
            val groupId = UUID.random()
            coEvery { permissionRepository.grant(any()) } returns RepositoryPermission(repoId, groupId, PermissionAction.VIEW)
            service.addPermission(repoId, groupId, PermissionAction.VIEW)
            coVerify { permissionRepository.grant(match { it.repositoryId == repoId && it.groupId == groupId && it.action == PermissionAction.VIEW }) }
        }
    }

    @Test
    fun `removePermission revokes and invalidates cache`() = runTest {
        withCacheContext {
            val groupId = UUID.random()
            service.removePermission(repoId, groupId, PermissionAction.EDIT)
            coVerify { permissionRepository.revoke(repoId, groupId, PermissionAction.EDIT) }
        }
    }

    @Test
    fun `slug with dots and underscores is valid`() = runTest {
        val input = CreateRepositoryInput(
            slug = "my_repo.v2",
            name = "My Repo",
            ownerId = ownerId,
        )
        coEvery { repoRepository.create(any()) } answers {
            (firstArg() as Repository).copy(id = repoId)
        }
        val result = service.create(input)
        assertEquals("my_repo.v2", result.slug)
    }

    @Test
    fun `slug starting with hyphen is rejected`() = runTest {
        val input = CreateRepositoryInput(
            slug = "-bad",
            name = "Bad",
            ownerId = ownerId,
        )
        assertFailsWith<IllegalArgumentException> {
            service.create(input)
        }
    }

    @Test
    fun `create with README calls initializer`() = runTest {
        coEvery { initializer.initialize(any(), any()) } returns 256L
        coEvery { repoRepository.create(any()) } answers {
            (firstArg() as Repository).copy(id = repoId)
        }
        coEvery { repoRepository.updateDiskSize(any(), any()) } returns Unit

        val input = CreateRepositoryInput(
            slug = "readme-repo",
            name = "Readme Repo",
            description = "A repo with a README",
            ownerId = ownerId,

            initializeWithReadme = true
        )
        service.create(input)

        coVerify { initializer.initialize(repoId, input) }
        coVerify { repoRepository.updateDiskSize(repoId, 256L) }
    }

    @Test
    fun `create without init does not call initializer disk size update`() = runTest {
        coEvery { initializer.initialize(any(), any()) } returns 0L
        coEvery { repoRepository.create(any()) } answers {
            (firstArg() as Repository).copy(id = repoId)
        }

        val input = CreateRepositoryInput(
            slug = "plain-repo",
            name = "Plain Repo",
            ownerId = ownerId,
        )
        service.create(input)

        coVerify { initializer.initialize(repoId, input) }
        coVerify(exactly = 0) { repoRepository.updateDiskSize(any(), any()) }
    }

    @Test
    fun `create enforces storage quota`() = runTest {
        coEvery { repoRepository.sumDiskSizeByOwner(ownerId) } returns 11L * 1024 * 1024 * 1024

        val input = CreateRepositoryInput(
            slug = "over-quota",
            name = "Over Quota",
            ownerId = ownerId,
        )
        assertFailsWith<StorageQuotaExceededException> {
            service.create(input)
        }
    }

    @Test
    fun `fork updates disk size from source`() = runTest {
        val source = testRepo(slug = "source-repo").copy(diskSizeBytes = 4096)
        val forkId = UUID.random()
        val newOwnerId = UUID.random()

        coEvery { repoRepository.findById(source.id) } returns source
        coEvery { repoRepository.create(any()) } answers {
            (firstArg() as Repository).copy(id = forkId)
        }
        coEvery { dfsRefRepository.findAll(source.id) } returns emptyList()
        coEvery { repoRepository.updateDiskSize(any(), any()) } returns Unit
        coEvery { repoRepository.sumDiskSizeByOwner(newOwnerId) } returns 0L

        val input = ForkRepositoryInput(
            sourceRepositoryId = source.id,
            newOwnerId = newOwnerId,
        )
        service.fork(input)

        coVerify { repoRepository.updateDiskSize(forkId, 4096L) }
    }

    @Test
    fun `fork enforces storage quota`() = runTest {
        val source = testRepo(slug = "source-repo")
        val newOwnerId = UUID.random()

        coEvery { repoRepository.findById(source.id) } returns source
        coEvery { repoRepository.sumDiskSizeByOwner(newOwnerId) } returns 11L * 1024 * 1024 * 1024

        val input = ForkRepositoryInput(
            sourceRepositoryId = source.id,
            newOwnerId = newOwnerId,
        )
        assertFailsWith<StorageQuotaExceededException> {
            service.fork(input)
        }
    }

    @Test
    fun `createBranch from existing branch`() = runTest {
        val sha = "a".repeat(40)
        coEvery { dfsRefRepository.findByName(repoId, "refs/heads/feature") } returns null
        coEvery { dfsRefRepository.findByName(repoId, "refs/heads/main") } returns
            DfsRef(repositoryId = repoId, name = "refs/heads/main", objectId = sha)
        coEvery { dfsRefRepository.upsert(any()) } answers { firstArg() }

        val result = service.createBranch(repoId, "feature", "main")
        assertEquals("feature", result.name)
        assertEquals(sha, result.sha)

        coVerify {
            dfsRefRepository.upsert(match {
                it.repositoryId == repoId && it.name == "refs/heads/feature" && it.objectId == sha
            })
        }
        coVerify {
            refUpdateNotifier.notifyRefsUpdated(
                gitRepository,
                repoId,
                match { updates ->
                    updates.single().refName == "refs/heads/feature" &&
                        updates.single().oldId == ObjectId.zeroId() &&
                        updates.single().newId.name() == sha
                },
                null,
            )
        }
    }

    @Test
    fun `createBranch attributes ref activity to the initiating principal`() = runTest {
        val sha = "d".repeat(40)
        val principalId = UUID.random()
        coEvery { dfsRefRepository.findByName(repoId, "refs/heads/attributed") } returns null
        coEvery { dfsRefRepository.upsert(any()) } answers { firstArg() }

        service.createBranch(repoId, "attributed", sha, principalId)

        coVerify {
            refUpdateNotifier.notifyRefsUpdated(
                gitRepository,
                repoId,
                match { it.single().refName == "refs/heads/attributed" },
                principalId,
            )
        }
    }

    @Test
    fun `createBranch from SHA`() = runTest {
        val sha = "b".repeat(40)
        coEvery { dfsRefRepository.findByName(repoId, "refs/heads/from-sha") } returns null
        coEvery { dfsRefRepository.upsert(any()) } answers { firstArg() }

        val result = service.createBranch(repoId, "from-sha", sha)
        assertEquals("from-sha", result.name)
        assertEquals(sha, result.sha)
    }

    @Test
    fun `createBranch from tag`() = runTest {
        val sha = "c".repeat(40)
        coEvery { dfsRefRepository.findByName(repoId, "refs/heads/release") } returns null
        coEvery { dfsRefRepository.findByName(repoId, "refs/heads/v1.0") } returns null
        coEvery { dfsRefRepository.findByName(repoId, "refs/tags/v1.0") } returns
            DfsRef(repositoryId = repoId, name = "refs/tags/v1.0", objectId = sha)
        coEvery { dfsRefRepository.upsert(any()) } answers { firstArg() }

        val result = service.createBranch(repoId, "release", "v1.0")
        assertEquals("release", result.name)
        assertEquals(sha, result.sha)
    }

    @Test
    fun `createBranch rejects invalid branch name`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.createBranch(repoId, "bad branch!", "main")
        }
    }

    @Test
    fun `createBranch rejects duplicate branch`() = runTest {
        coEvery { dfsRefRepository.findByName(repoId, "refs/heads/main") } returns
            DfsRef(repositoryId = repoId, name = "refs/heads/main", objectId = "a".repeat(40))

        assertFailsWith<IllegalArgumentException> {
            service.createBranch(repoId, "main", "main")
        }
    }

    @Test
    fun `createBranch throws when source ref not found`() = runTest {
        coEvery { dfsRefRepository.findByName(repoId, "refs/heads/new-branch") } returns null
        coEvery { dfsRefRepository.findByName(repoId, "refs/heads/nonexistent") } returns null
        coEvery { dfsRefRepository.findByName(repoId, "refs/tags/nonexistent") } returns null

        assertFailsWith<IllegalArgumentException> {
            service.createBranch(repoId, "new-branch", "nonexistent")
        }
    }

    // ── appended coverage: permission resolution with owner-MANAGE fallback ──

    @Test
    fun `getPermissions returns explicit permissions when a MANAGE grant exists`() = runTest {
        val repo = testRepo()
        coEvery { permissionRepository.findByRepository(repoId) } returns listOf(
            bosca.git.model.RepositoryPermission(repoId, UUID.random(), bosca.security.model.PermissionAction.MANAGE),
        )
        val perms = withCacheContext { service.getPermissions(repo) }
        assertEquals(1, perms.size)
        coVerify(exactly = 0) { profileService.getById(any()) }
    }

    @Test
    fun `getPermissions falls back to owner manage and execute when no MANAGE grant exists`() = runTest {
        val repo = testRepo()
        coEvery { permissionRepository.findByRepository(repoId) } returns emptyList()
        coEvery { repoRepository.findById(repoId) } returns repo
        val principalId = UUID.random()
        val groupId = UUID.random()
        val profile = mockk<bosca.profile.model.Profile>(relaxed = true)
        io.mockk.every { profile.principal } returns principalId
        coEvery { profileService.getById(ownerId) } returns profile
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(
            bosca.security.model.Group(id = groupId, name = "owners", description = "", type = bosca.security.model.GroupType.PRINCIPAL),
        )

        val perms = withCacheContext { service.getPermissions(repo) }

        assertEquals(
            listOf(bosca.security.model.PermissionAction.MANAGE, bosca.security.model.PermissionAction.EXECUTE),
            perms.map { it.action },
        )
        assertTrue(perms.all { it.groupId == groupId })
    }

    @Test
    fun `owner fallback is empty when the profile is missing or has no principal`() = runTest {
        val repo = testRepo()
        coEvery { permissionRepository.findByRepository(repoId) } returns emptyList()
        coEvery { repoRepository.findById(repoId) } returns repo

        coEvery { profileService.getById(ownerId) } throws IllegalStateException("gone")
        assertEquals(emptyList(), withCacheContext { service.getPermissions(repo) })

        val profile = mockk<bosca.profile.model.Profile>(relaxed = true)
        io.mockk.every { profile.principal } returns null
        coEvery { profileService.getById(ownerId) } returns profile
        assertEquals(emptyList(), withCacheContext { service.getPermissions(repo) })
    }

    // ── appended coverage: lookups, dispatch arms, mutation guards ───────

    @Test
    fun `update applies every provided field`() = runTest {
        val existing = testRepo()
        coEvery { repoRepository.findById(repoId) } returns existing
        coEvery { repoRepository.update(any()) } answers { firstArg() }
        val updated = withCacheContext {
            service.update(repoId, bosca.git.model.UpdateRepositoryInput(
                name = "New", description = "D", visibility = Visibility.PUBLIC,
                defaultBranch = "trunk", contentType = bosca.git.model.RepositoryContentType.GENERAL,
                configuration = bosca.git.model.RepositoryConfiguration(squashByDefault = true),
            ))
        }
        assertEquals("New", updated.name)
        assertEquals("D", updated.description)
        assertEquals(Visibility.PUBLIC, updated.visibility)
        assertEquals("trunk", updated.defaultBranch)
        assertTrue(updated.configuration.squashByDefault)
    }

    @Test
    fun `archive, delete, restore, transfer throw when the repository is missing`() = runTest {
        coEvery { repoRepository.archive(repoId) } returns null
        coEvery { repoRepository.softDelete(repoId) } returns null
        coEvery { repoRepository.restore(repoId) } returns null
        coEvery { repoRepository.transfer(repoId, any()) } returns null
        val ops: List<suspend () -> Any?> = listOf(
            { service.archive(repoId) },
            { service.delete(repoId) },
            { service.restore(repoId) },
            { service.transfer(repoId, UUID.random()) },
        )
        for ((i, op) in ops.withIndex()) {
            try { withCacheContext { op() }; kotlin.test.fail("op #$i should throw") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
    }

    @Test
    fun `finders dispatch to the repository with both archived arms`() = runTest {
        coEvery { repoRepository.findById(repoId) } returns testRepo()
        assertEquals(repoId, service.findById(repoId)?.id)

        coEvery { repoRepository.findByIdIncludingDeleted(repoId) } returns testRepo()
        assertEquals(repoId, service.findByIdIncludingDeleted(repoId)?.id)

        coEvery { repoRepository.findAllIncludingArchived() } returns emptyList()
        coEvery { repoRepository.findAll() } returns emptyList()
        coEvery { repoRepository.findByOwnerIncludingArchived(ownerId) } returns emptyList()
        coEvery { repoRepository.findByOwner(ownerId) } returns emptyList()
        coEvery { repoRepository.findByContentType(any()) } returns emptyList()

        service.findAll(true)
        coVerify { repoRepository.findAllIncludingArchived() }
        service.findAll(false)
        coVerify { repoRepository.findAll() }

        service.findByOwner(ownerId, true)
        coVerify { repoRepository.findByOwnerIncludingArchived(ownerId) }
        service.findByOwner(ownerId, false)
        coVerify { repoRepository.findByOwner(ownerId) }

        service.findByContentType(bosca.git.model.RepositoryContentType.GENERAL)
        coVerify { repoRepository.findByContentType(bosca.git.model.RepositoryContentType.GENERAL) }
    }

    @Test
    fun `addPermissionsToBatch delegates to the permission cache`() = runTest {
        val secondId = UUID.random()
        val secondRepo = testRepo(secondId, "second")
        val batch = bosca.graphql.Batch<UUID, List<bosca.security.model.EntityPermission>>(
            listOf(repoId, secondId),
        )
        val view = RepositoryPermission(repoId, UUID.random(), PermissionAction.VIEW)
        val manage = RepositoryPermission(secondId, UUID.random(), PermissionAction.MANAGE)
        coEvery { permissionRepository.findByRepositories(listOf(repoId, secondId)) } returns
            listOf(view, manage)
        coEvery { repoRepository.findByIds(listOf(repoId, secondId)) } returns
            listOf(testRepo(), secondRepo)
        val principalId = UUID.random()
        val ownerGroupId = UUID.random()
        val profile = mockk<bosca.profile.model.Profile>(relaxed = true)
        every { profile.principal } returns principalId
        coEvery { profileService.getById(ownerId) } returns profile
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(
            bosca.security.model.Group(
                id = ownerGroupId,
                name = "owners",
                description = "",
                type = bosca.security.model.GroupType.PRINCIPAL,
            ),
        )
        val remoteCache = mockk<Cache<UUID>>(relaxed = true)
        every { remoteCache.keySerializer } returns UUIDKeySerializer
        coEvery { cacheManager.getCache<UUID>("git:repository:permissions") } returns remoteCache
        coEvery { remoteCache.getBatch(any()) } answers {
            firstArg<List<CacheKey<UUID>>>().map {
                mockk<CacheValue> {
                    every { exists } returns false
                    every { value } returns null
                }
            }
        }

        withCacheContext { service.addPermissionsToBatch(batch) }

        assertEquals(
            listOf(PermissionAction.VIEW, PermissionAction.MANAGE, PermissionAction.EXECUTE),
            batch.getData(repoId)?.map { it.action },
        )
        assertEquals(PermissionAction.MANAGE, batch.getData(secondId)?.single()?.action)
    }

    @Test
    fun `createBranch validates names, uniqueness, and source resolution`() = runTest {
        // Invalid name.
        try { service.createBranch(repoId, "bad name!", "main"); kotlin.test.fail() }
        catch (_: IllegalArgumentException) {}

        // Already exists.
        coEvery { dfsRefRepository.findByName(repoId, "refs/heads/dup") } returns
            bosca.git.model.DfsRef(repositoryId = repoId, name = "refs/heads/dup", objectId = "a".repeat(40))
        try { service.createBranch(repoId, "dup", "main"); kotlin.test.fail() }
        catch (_: IllegalArgumentException) {}

        // Unresolvable source.
        coEvery { dfsRefRepository.findByName(repoId, any()) } returns null
        try { service.createBranch(repoId, "feat", "nope"); kotlin.test.fail() }
        catch (_: IllegalArgumentException) {}

        // Raw sha source resolves directly.
        val sha = "b".repeat(40)
        coEvery { dfsRefRepository.upsert(any()) } answers { firstArg() }
        val info = service.createBranch(repoId, "feat", sha)
        assertEquals(sha, info.sha)

        // Tag ref source resolves through refs/tags.
        coEvery { dfsRefRepository.findByName(repoId, "refs/heads/v1") } returns null
        coEvery { dfsRefRepository.findByName(repoId, "refs/tags/v1") } returns
            bosca.git.model.DfsRef(repositoryId = repoId, name = "refs/tags/v1", objectId = "c".repeat(40))
        coEvery { dfsRefRepository.findByName(repoId, "refs/heads/feat2") } returns null
        val fromTag = service.createBranch(repoId, "feat2", "v1")
        assertEquals("c".repeat(40), fromTag.sha)
    }
}
