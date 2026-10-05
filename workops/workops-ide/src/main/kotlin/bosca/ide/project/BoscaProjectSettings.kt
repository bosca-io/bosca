package bosca.ide.project

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/** A local Git root mapped to a repository on one named Bosca server. */
data class BoscaRepositoryMapping(
    var rootUrl: String = "",
    var serverProfileId: String = "",
    var repositoryId: String = "",
)

data class BoscaNotificationState(
    var key: String = "",
    var value: String = "",
)

/** Project-local routing state. It contains identifiers only and is safe for workspace persistence. */
@Service(Service.Level.PROJECT)
@State(name = "BoscaProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class BoscaProjectSettings(@Suppress("UNUSED_PARAMETER") project: Project) :
    PersistentStateComponent<BoscaProjectSettings.ProjectState> {

    data class ProjectState(
        var serverSelectionConfigured: Boolean = false,
        var enabledProfileIds: MutableList<String> = mutableListOf(),
        var repositoryMappings: MutableList<BoscaRepositoryMapping> = mutableListOf(),
        var notificationStates: MutableList<BoscaNotificationState> = mutableListOf(),
    )

    private var currentState = ProjectState()

    override fun getState(): ProjectState = currentState

    override fun loadState(state: ProjectState) {
        this.currentState = ProjectState(
            serverSelectionConfigured = state.serverSelectionConfigured,
            enabledProfileIds = state.enabledProfileIds.distinct().toMutableList(),
            repositoryMappings = state.repositoryMappings
                .filter { it.rootUrl.isNotBlank() && it.serverProfileId.isNotBlank() && it.repositoryId.isNotBlank() }
                .distinctBy { it.rootUrl }
                .map { it.copy() }
                .toMutableList(),
            notificationStates = state.notificationStates
                .filter { it.key.isNotBlank() }
                .distinctBy { it.key }
                .takeLast(2_000)
                .map { it.copy() }
                .toMutableList(),
        )
    }

    @Synchronized
    fun effectiveProfileIds(allProfileIds: Collection<String>): Set<String> =
        if (currentState.serverSelectionConfigured) {
            currentState.enabledProfileIds.filterTo(linkedSetOf()) { it in allProfileIds }
        } else {
            allProfileIds.toCollection(linkedSetOf())
        }

    @Synchronized
    fun setEnabledProfiles(profileIds: Collection<String>) {
        currentState.serverSelectionConfigured = true
        currentState.enabledProfileIds = profileIds.distinct().toMutableList()
    }

    @Synchronized
    fun mapping(rootUrl: String): BoscaRepositoryMapping? =
        currentState.repositoryMappings.firstOrNull { it.rootUrl == rootUrl }?.copy()

    @Synchronized
    fun mappings(): List<BoscaRepositoryMapping> = currentState.repositoryMappings.map { it.copy() }

    @Synchronized
    fun putMapping(mapping: BoscaRepositoryMapping) {
        require(mapping.rootUrl.isNotBlank()) { "Git root is required" }
        require(mapping.serverProfileId.isNotBlank()) { "Bosca server is required" }
        require(mapping.repositoryId.isNotBlank()) { "Bosca repository is required" }
        currentState.repositoryMappings.removeIf { it.rootUrl == mapping.rootUrl }
        currentState.repositoryMappings.add(mapping.copy())
    }

    @Synchronized
    fun removeMapping(rootUrl: String) {
        currentState.repositoryMappings.removeIf { it.rootUrl == rootUrl }
    }

    @Synchronized
    fun notificationState(key: String): String? = currentState.notificationStates.firstOrNull { it.key == key }?.value

    @Synchronized
    fun putNotificationState(key: String, value: String) {
        currentState.notificationStates.removeIf { it.key == key }
        currentState.notificationStates.add(BoscaNotificationState(key, value))
        if (currentState.notificationStates.size > 2_000) {
            currentState.notificationStates = currentState.notificationStates.takeLast(2_000).toMutableList()
        }
    }
}
