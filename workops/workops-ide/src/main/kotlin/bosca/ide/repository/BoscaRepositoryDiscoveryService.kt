package bosca.ide.repository

import bosca.ide.project.BoscaProjectSettings
import bosca.ide.project.BoscaRepositoryMapping
import bosca.ide.server.BoscaConnectionManager
import bosca.ide.server.BoscaServerProfile
import bosca.ide.server.BoscaServerRegistry
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.CompletableFuture

data class BoscaRemoteRepository(
    val serverProfileId: String,
    val id: String,
    val name: String,
    val slug: String,
    val cloneUrl: String,
    val defaultBranch: String,
)

data class BoscaLocalGitRoot(
    val rootUrl: String,
    val name: String,
    val remoteUrls: List<String>,
)

data class BoscaServerDiscovery(
    val profile: BoscaServerProfile,
    val repositories: List<BoscaRemoteRepository>,
    val error: String? = null,
)

enum class BoscaMappingStatus {
    MAPPED,
    AUTO_MAPPED,
    AMBIGUOUS,
    UNMAPPED,
    SERVER_DISABLED,
    SERVER_UNAVAILABLE,
    REPOSITORY_NOT_FOUND,
}

data class BoscaRootDiscovery(
    val root: BoscaLocalGitRoot,
    val mapping: BoscaRepositoryMapping?,
    val repository: BoscaRemoteRepository?,
    val candidates: List<BoscaRemoteRepository>,
    val status: BoscaMappingStatus,
)

data class BoscaDiscoverySnapshot(
    val servers: List<BoscaServerDiscovery>,
    val roots: List<BoscaRootDiscovery>,
)

/** Discovers visible repositories on every enabled server and reconciles local Git-root mappings. */
@Service(Service.Level.PROJECT)
class BoscaRepositoryDiscoveryService(private val project: Project) {
    private val registry = BoscaServerRegistry.getInstance()
    private val settings = project.getService(BoscaProjectSettings::class.java)
    private val connections = project.getService(BoscaConnectionManager::class.java)

    fun refresh(): CompletableFuture<BoscaDiscoverySnapshot> {
        val profiles = registry.profiles()
        val enabledIds = settings.effectiveProfileIds(profiles.map { it.id })
        val enabledProfiles = profiles.filter { it.id in enabledIds }
        val localRoots = localRoots()
        val futures = enabledProfiles.map { profile -> discoverServer(profile) }

        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
            val servers = futures.map { it.join() }
            val repositories = servers.flatMap { it.repositories }
            val unavailable = servers.filter { it.error != null }.mapTo(hashSetOf()) { it.profile.id }
            val roots = localRoots.map { resolveRoot(it, repositories, enabledIds, unavailable) }
            BoscaDiscoverySnapshot(servers, roots)
        }
    }

    fun localRoots(): List<BoscaLocalGitRoot> =
        GitRepositoryManager.getInstance(project).repositories.map { repository ->
            BoscaLocalGitRoot(
                rootUrl = repository.root.url,
                name = repository.root.name,
                remoteUrls = repository.remotes.flatMap { it.urls }.distinct(),
            )
        }

    private fun discoverServer(profile: BoscaServerProfile): CompletableFuture<BoscaServerDiscovery> =
        connections.execute(profile.id, REPOSITORIES_QUERY)
            .handle { data, error ->
                if (error != null) {
                    BoscaServerDiscovery(profile, emptyList(), rootMessage(error))
                } else {
                    val repositories = data.getAsJsonObject("git")
                        ?.getAsJsonArray("repositories")
                        ?.map { element ->
                            val repository = element.asJsonObject
                            BoscaRemoteRepository(
                                serverProfileId = profile.id,
                                id = repository.get("id").asString,
                                name = repository.get("name").asString,
                                slug = repository.get("slug").asString,
                                cloneUrl = repository.get("cloneUrl").asString,
                                defaultBranch = repository.get("defaultBranch").asString,
                            )
                        }.orEmpty()
                    BoscaServerDiscovery(profile, repositories)
                }
            }

    private fun resolveRoot(
        root: BoscaLocalGitRoot,
        repositories: List<BoscaRemoteRepository>,
        enabledProfileIds: Set<String>,
        unavailableProfileIds: Set<String>,
    ): BoscaRootDiscovery {
        val saved = settings.mapping(root.rootUrl)
        if (saved != null) {
            val repository = repositories.firstOrNull {
                it.serverProfileId == saved.serverProfileId && it.id == saved.repositoryId
            }
            if (repository != null) {
                return BoscaRootDiscovery(root, saved, repository, listOf(repository), BoscaMappingStatus.MAPPED)
            }
            if (saved.serverProfileId !in enabledProfileIds) {
                return BoscaRootDiscovery(root, saved, null, emptyList(), BoscaMappingStatus.SERVER_DISABLED)
            }
            if (saved.serverProfileId in unavailableProfileIds) {
                return BoscaRootDiscovery(root, saved, null, emptyList(), BoscaMappingStatus.SERVER_UNAVAILABLE)
            }
            return BoscaRootDiscovery(root, saved, null, emptyList(), BoscaMappingStatus.REPOSITORY_NOT_FOUND)
        }

        val candidates = BoscaRepositoryMatcher.match(root.remoteUrls, repositories)
        return when (candidates.size) {
            1 -> {
                val repository = candidates.single()
                val mapping = BoscaRepositoryMapping(root.rootUrl, repository.serverProfileId, repository.id)
                settings.putMapping(mapping)
                BoscaRootDiscovery(root, mapping, repository, candidates, BoscaMappingStatus.AUTO_MAPPED)
            }
            0 -> BoscaRootDiscovery(root, null, null, emptyList(), BoscaMappingStatus.UNMAPPED)
            else -> BoscaRootDiscovery(root, null, null, candidates, BoscaMappingStatus.AMBIGUOUS)
        }
    }

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null) current = current.cause ?: break
        return current.message ?: current.javaClass.simpleName
    }

    companion object {
        private const val REPOSITORIES_QUERY = """
            query BoscaIdeRepositories {
              git {
                repositories(includeArchived: false) {
                  id
                  name
                  slug
                  cloneUrl
                  defaultBranch
                }
              }
            }
        """
    }
}

object BoscaRepositoryMatcher {
    fun match(
        localRemoteUrls: Collection<String>,
        repositories: Collection<BoscaRemoteRepository>,
    ): List<BoscaRemoteRepository> {
        val localRemotes = localRemoteUrls.mapNotNull(BoscaGitRemoteNormalizer::normalize).toSet()
        return repositories.filter {
            BoscaGitRemoteNormalizer.normalize(it.cloneUrl) in localRemotes
        }
    }
}
