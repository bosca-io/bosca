package bosca.ide.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

/** Application-level registry of named Bosca servers and their non-secret CLI profile bindings. */
@Service(Service.Level.APP)
@State(name = "BoscaServerProfiles", storages = [Storage("bosca.xml")])
class BoscaServerRegistry : PersistentStateComponent<BoscaServerRegistry.RegistryState> {
    data class RegistryState(var profiles: MutableList<BoscaServerProfile> = mutableListOf())

    fun interface Listener {
        fun profilesChanged()
    }

    private var currentState = RegistryState()

    override fun getState(): RegistryState = currentState

    override fun loadState(state: RegistryState) {
        this.currentState = RegistryState(
            state.profiles
                .mapNotNull { runCatching { it.normalized() }.getOrNull() }
                .distinctBy { it.id }
                .toMutableList()
        )
    }

    @Synchronized
    fun profiles(): List<BoscaServerProfile> = currentState.profiles.map { it.copy() }

    @Synchronized
    fun profile(id: String): BoscaServerProfile? = currentState.profiles.firstOrNull { it.id == id }?.copy()

    @Synchronized
    fun replaceProfiles(profiles: List<BoscaServerProfile>) {
        val normalized = profiles.map { it.normalized() }
        require(normalized.map { it.id }.distinct().size == normalized.size) { "Server profile ids must be unique" }
        require(normalized.map { it.name.lowercase() }.distinct().size == normalized.size) {
            "Server profile names must be unique"
        }

        currentState = RegistryState(normalized.map { it.copy() }.toMutableList())
        publishChanged()
    }

    private fun publishChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(PROFILES_CHANGED).profilesChanged()
    }

    companion object {
        val PROFILES_CHANGED: Topic<Listener> =
            Topic.create("Bosca server profiles changed", Listener::class.java)

        fun getInstance(): BoscaServerRegistry =
            ApplicationManager.getApplication().getService(BoscaServerRegistry::class.java)
    }
}
