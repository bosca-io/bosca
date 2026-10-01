package bosca.git.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.di.provide
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitWorkDispatcher
import bosca.git.jobs.RepositoryIndexJob
import bosca.git.model.BranchInfo
import bosca.git.model.CreateRepositoryInput
import bosca.git.model.DfsRef
import bosca.git.model.ForkRepositoryInput
import bosca.git.model.Repository
import bosca.git.model.RepositoryContentType
import bosca.git.model.RepositoryPermission
import bosca.git.model.StorageQuotaExceededException
import bosca.git.model.UpdateRepositoryInput
import bosca.git.repository.DfsRefRepository
import bosca.git.repository.GitRepositoryRepository
import bosca.git.repository.RepositoryPermissionRepository
import bosca.graphql.Batch
import bosca.profile.profile.service.ProfileService
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.slug.service.SlugService
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.eclipse.jgit.lib.ObjectId
import org.slf4j.LoggerFactory

/**
 * Coordinates repository lifecycle operations between the PostgreSQL metadata layer
 * and the DFS object store. Permission lookups are cached per-request via [ServiceCache]
 * for DataLoader-style batch resolution in GraphQL controllers, and invalidated on
 * grant/revoke.
 */
@ServiceImplementation
class RepositoryServiceImpl(
    private val repositoryRepository: GitRepositoryRepository,
    private val permissionRepository: RepositoryPermissionRepository,
    private val dfsRefRepository: DfsRefRepository,
    private val initializer: RepositoryInitializer,
    private val slugService: SlugService,
    private val profileService: ProfileService,
    private val securityService: SecurityService,
    private val json: Json,
    private val dfsManager: BoscaDfsRepositoryManager,
    private val refUpdateNotifier: RefUpdateNotifier,
) : RepositoryService {

    private val log = LoggerFactory.getLogger(RepositoryServiceImpl::class.java)

    private suspend fun ownerManagePermissions(repositoryId: UUID, ownerId: UUID): List<EntityPermission> {
        val profile = try {
            profileService.getById(ownerId)
        } catch (_: Exception) {
            return emptyList()
        }
        val principalId = profile.principal ?: return emptyList()
        val groups = securityService.getPrincipalGroups(principalId)
        return groups.flatMap {
            listOf(
                RepositoryPermission(repositoryId, it.id, PermissionAction.MANAGE),
                RepositoryPermission(repositoryId, it.id, PermissionAction.EXECUTE),
            )
        }
    }

    private val permissionCache = ServiceCache<UUID, List<EntityPermission>>(
        "git:repository:permissions",
        UUIDKeySerializer,
        batchResolver = { keys, batch ->
            val allPermissions = permissionRepository.findByRepositories(keys)
            val grouped = allPermissions.groupBy { it.repositoryId }.toMutableMap()
            val repos = repositoryRepository.findByIds(keys)
            val reposByKey = repos.associateBy { it.id }
            for (key in keys) {
                val perms = (grouped[key]?.map { it as EntityPermission } ?: emptyList()).toMutableList()
                if (perms.none { it.action == PermissionAction.MANAGE }) {
                    val repo = reposByKey[key]
                    if (repo != null) {
                        perms.addAll(ownerManagePermissions(key, repo.ownerId))
                    }
                }
                batch.setData(key, perms)
            }
        }
    ) {
        val perms = permissionRepository.findByRepository(it).map { it as EntityPermission }.toMutableList()
        if (perms.none { it.action == PermissionAction.MANAGE }) {
            val repo = repositoryRepository.findById(it)
            if (repo != null) {
                perms.addAll(ownerManagePermissions(it, repo.ownerId))
            }
        }
        perms
    }

    override suspend fun create(input: CreateRepositoryInput): Repository {
        require(input.slug.matches(SLUG_PATTERN)) {
            "Slug must be lowercase alphanumeric with hyphens, 1-100 characters"
        }
        enforceStorageQuota(input.ownerId)
        val repo = repositoryRepository.create(
            Repository(
                slug = input.slug,
                name = input.name,
                description = input.description,
                ownerId = input.ownerId,
                visibility = input.visibility,
                defaultBranch = input.defaultBranch,
                contentType = input.contentType,
                configuration = input.configuration
            )
        )
        val sizeBytes = initializer.initialize(repo.id, input)
        if (sizeBytes > 0) {
            repositoryRepository.updateDiskSize(repo.id, sizeBytes)
        }
        log.info("Created repository {}/{} (id={})", input.ownerId, input.slug, repo.id)
        enqueueSearchIndex(repo.id)
        return repo
    }

    override suspend fun update(id: UUID, input: UpdateRepositoryInput): Repository {
        val existing = repositoryRepository.findById(id)
            ?: throw IllegalArgumentException("Repository not found: $id")
        val updated = existing.copy(
            name = input.name ?: existing.name,
            description = input.description ?: existing.description,
            visibility = input.visibility ?: existing.visibility,
            defaultBranch = input.defaultBranch ?: existing.defaultBranch,
            contentType = input.contentType ?: existing.contentType,
            configuration = input.configuration ?: existing.configuration
        )
        val result = repositoryRepository.update(updated)
        enqueueSearchIndex(id)
        return result
    }

    override suspend fun rename(id: UUID, newSlug: String): Repository {
        require(newSlug.matches(SLUG_PATTERN)) {
            "Slug must be lowercase alphanumeric with hyphens, 1-100 characters"
        }
        val existing = repositoryRepository.findById(id)
            ?: throw IllegalArgumentException("Repository not found: $id")
        if (newSlug == existing.slug) return existing
        val conflict = repositoryRepository.findByOwnerAndSlug(existing.ownerId, newSlug)
        require(conflict == null) {
            "A repository with slug '$newSlug' already exists in this owner's namespace"
        }
        val result = repositoryRepository.updateSlug(id, newSlug)
            ?: throw IllegalArgumentException("Repository not found: $id")
        log.info("Renamed repository {} slug {} → {}", id, existing.slug, newSlug)
        enqueueSearchIndex(id)
        return result
    }

    override suspend fun archive(id: UUID): Repository {
        return repositoryRepository.archive(id)
            ?: throw IllegalArgumentException("Repository not found: $id")
    }

    override suspend fun delete(id: UUID): Repository {
        val result = repositoryRepository.softDelete(id)
            ?: throw IllegalArgumentException("Repository not found: $id")
        enqueueSearchIndex(id, deleteOnly = true)
        return result
    }

    override suspend fun restore(id: UUID): Repository {
        val result = repositoryRepository.restore(id)
            ?: throw IllegalArgumentException("Repository not found: $id")
        enqueueSearchIndex(id)
        return result
    }

    override suspend fun transfer(id: UUID, newOwnerId: UUID): Repository {
        return repositoryRepository.transfer(id, newOwnerId)
            ?: throw IllegalArgumentException("Repository not found: $id")
    }

    override suspend fun fork(input: ForkRepositoryInput): Repository {
        val source = repositoryRepository.findById(input.sourceRepositoryId)
            ?: throw IllegalArgumentException("Source repository not found: ${input.sourceRepositoryId}")

        enforceStorageQuota(input.newOwnerId)

        val forkSlug = input.slug ?: source.slug
        val forkName = input.name ?: source.name

        val fork = repositoryRepository.create(
            Repository(
                slug = forkSlug,
                name = forkName,
                description = source.description,
                ownerId = input.newOwnerId,
                visibility = source.visibility,
                defaultBranch = source.defaultBranch,
                contentType = source.contentType,
                configuration = source.configuration,
                forkedFromId = source.id
            )
        )

        val sourceRefs = dfsRefRepository.findAll(source.id)
        for (ref in sourceRefs) {
            dfsRefRepository.upsert(ref.copy(repositoryId = fork.id))
        }

        repositoryRepository.updateDiskSize(fork.id, source.diskSizeBytes)

        log.info("Forked repository {} → {} (id={})", source.id, fork.id, fork.id)
        enqueueSearchIndex(fork.id)
        return fork
    }

    override suspend fun findByOwnerAndSlug(ownerSlug: String, repoSlug: String): Repository? {
        val slug = slugService.get(ownerSlug) ?: return null
        val ownerId = slug.profileId ?: return null
        return repositoryRepository.findByOwnerAndSlug(ownerId, repoSlug)
    }

    override suspend fun findById(id: UUID): Repository? {
        return repositoryRepository.findById(id)
    }

    override suspend fun findByIdIncludingDeleted(id: UUID): Repository? {
        return repositoryRepository.findByIdIncludingDeleted(id)
    }

    override suspend fun findAll(includeArchived: Boolean): List<Repository> {
        return if (includeArchived) {
            repositoryRepository.findAllIncludingArchived()
        } else {
            repositoryRepository.findAll()
        }
    }

    override suspend fun findByOwner(ownerId: UUID, includeArchived: Boolean): List<Repository> {
        return if (includeArchived) {
            repositoryRepository.findByOwnerIncludingArchived(ownerId)
        } else {
            repositoryRepository.findByOwner(ownerId)
        }
    }

    override suspend fun findByContentType(contentType: RepositoryContentType): List<Repository> {
        return repositoryRepository.findByContentType(contentType)
    }

    override suspend fun addPermission(repositoryId: UUID, groupId: UUID, action: PermissionAction) {
        permissionRepository.grant(RepositoryPermission(repositoryId, groupId, action))
        permissionCache.remove(repositoryId)
    }

    override suspend fun removePermission(repositoryId: UUID, groupId: UUID, action: PermissionAction) {
        permissionRepository.revoke(repositoryId, groupId, action)
        permissionCache.remove(repositoryId)
    }

    override suspend fun getPermissions(entity: Repository): List<EntityPermission> {
        return permissionCache.get(entity.id) ?: emptyList()
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        permissionCache.addToBatch(batch)
    }

    override suspend fun createBranch(repositoryId: UUID, branchName: String, sourceRef: String): BranchInfo =
        createBranchInternal(repositoryId, branchName, sourceRef, initiatingPrincipalId = null)

    override suspend fun createBranch(
        repositoryId: UUID,
        branchName: String,
        sourceRef: String,
        initiatingPrincipalId: UUID,
    ): BranchInfo = createBranchInternal(repositoryId, branchName, sourceRef, initiatingPrincipalId)

    private suspend fun createBranchInternal(
        repositoryId: UUID,
        branchName: String,
        sourceRef: String,
        initiatingPrincipalId: UUID?,
    ): BranchInfo {
        require(branchName.matches(BRANCH_NAME_PATTERN)) {
            "Branch name must be alphanumeric with hyphens, underscores, dots, or slashes"
        }

        val targetRefName = "refs/heads/$branchName"
        val existing = dfsRefRepository.findByName(repositoryId, targetRefName)
        require(existing == null) {
            "Branch already exists: $branchName"
        }

        val objectId = resolveRef(repositoryId, sourceRef)
            ?: throw IllegalArgumentException("Cannot resolve source ref: $sourceRef")

        dfsRefRepository.upsert(
            DfsRef(
                repositoryId = repositoryId,
                name = targetRefName,
                objectId = objectId
            )
        )

        withContext(GitWorkDispatcher) {
            dfsManager.open(repositoryId).use { repository ->
                refUpdateNotifier.notifyRefsUpdated(
                    repository = repository,
                    repositoryId = repositoryId,
                    updates = listOf(RefChange(targetRefName, ObjectId.zeroId(), ObjectId.fromString(objectId))),
                    pusherPrincipalId = initiatingPrincipalId,
                )
            }
        }

        log.info("Created branch {} from {} in repository {}", branchName, sourceRef, repositoryId)
        return BranchInfo(name = branchName, sha = objectId)
    }

    private suspend fun resolveRef(repositoryId: UUID, ref: String): String? {
        if (ref.matches(SHA_PATTERN)) return ref

        val branchRef = dfsRefRepository.findByName(repositoryId, "refs/heads/$ref")
        if (branchRef != null) return branchRef.objectId

        val tagRef = dfsRefRepository.findByName(repositoryId, "refs/tags/$ref")
        if (tagRef != null) return tagRef.objectId

        return null
    }

    private suspend fun enforceStorageQuota(ownerId: UUID) {
        val currentUsage = repositoryRepository.sumDiskSizeByOwner(ownerId) ?: 0L
        if (currentUsage >= DEFAULT_STORAGE_QUOTA_BYTES) {
            throw StorageQuotaExceededException(
                "Storage quota exceeded for owner $ownerId: ${currentUsage / (1024 * 1024)}MB used of ${DEFAULT_STORAGE_QUOTA_BYTES / (1024 * 1024)}MB allowed"
            )
        }
    }

    private suspend fun enqueueSearchIndex(repositoryId: UUID, deleteOnly: Boolean = false) {
        try {
            val enqueuer = provide<JobConfigurationEnqueuer>("repository-index")
            val job = RepositoryIndexJob(repositoryId = repositoryId, deleteOnly = deleteOnly)
            enqueuer.enqueue(json.encodeToJsonElement(job))
        } catch (e: Exception) {
            log.warn("Failed to enqueue search index for repository {}", repositoryId, e)
        }
    }

    companion object {
        private val SLUG_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,99}$")
        private val BRANCH_NAME_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._/-]{0,254}$")
        private val SHA_PATTERN = Regex("^[0-9a-f]{40}$")
        private const val DEFAULT_STORAGE_QUOTA_BYTES = 10L * 1024 * 1024 * 1024 // 10 GB
    }
}
