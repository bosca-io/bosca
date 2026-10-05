package bosca.ide.ui

import bosca.ide.project.BoscaRepositoryMapping
import bosca.ide.repository.BoscaDiscoverySnapshot
import bosca.ide.repository.BoscaLocalGitRoot
import bosca.ide.repository.BoscaRemoteRepository
import bosca.ide.server.BoscaServerProfile
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

internal class BoscaRepositoryMappingDialog(
    project: Project,
    private val root: BoscaLocalGitRoot,
    private val snapshot: BoscaDiscoverySnapshot,
    existing: BoscaRepositoryMapping?,
) : DialogWrapper(project) {
    private val availableServers = snapshot.servers.filter { it.error == null && it.repositories.isNotEmpty() }
    private val serverCombo = JComboBox(availableServers.map { ServerItem(it.profile) }.toTypedArray())
    private val repositoryCombo = JComboBox<BoscaRemoteRepository>()

    init {
        title = "Map ${root.name} to Bosca"
        serverCombo.addActionListener { updateRepositories(null) }
        val initialServerId = existing?.serverProfileId
            ?: snapshot.roots.firstOrNull { it.root.rootUrl == root.rootUrl }?.candidates?.firstOrNull()?.serverProfileId
        availableServers.indexOfFirst { it.profile.id == initialServerId }
            .takeIf { it >= 0 }
            ?.let { serverCombo.selectedIndex = it }
        updateRepositories(existing?.repositoryId)
        init()
    }

    override fun createCenterPanel(): JComponent = if (availableServers.isEmpty()) {
        JBLabel("No enabled Bosca server returned a visible Git repository.")
    } else {
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Local root:", JBLabel(root.rootUrl))
            .addLabeledComponent("Bosca server:", serverCombo)
            .addLabeledComponent("Bosca repository:", repositoryCombo)
            .panel
    }

    override fun doValidate(): ValidationInfo? = when {
        availableServers.isEmpty() -> ValidationInfo("No Bosca repositories are available")
        serverCombo.selectedItem == null -> ValidationInfo("Select a Bosca server", serverCombo)
        repositoryCombo.selectedItem == null -> ValidationInfo("Select a Bosca repository", repositoryCombo)
        else -> null
    }

    fun mapping(): BoscaRepositoryMapping {
        val server = (serverCombo.selectedItem as ServerItem).profile
        val repository = repositoryCombo.selectedItem as BoscaRemoteRepository
        return BoscaRepositoryMapping(root.rootUrl, server.id, repository.id)
    }

    private fun updateRepositories(preferredRepositoryId: String?) {
        val serverId = (serverCombo.selectedItem as? ServerItem)?.profile?.id
        val repositories = snapshot.servers.firstOrNull { it.profile.id == serverId }?.repositories.orEmpty()
        repositoryCombo.model = DefaultComboBoxModel(repositories.toTypedArray())
        repositoryCombo.renderer = BoscaRepositoryRenderer()
        repositories.indexOfFirst { it.id == preferredRepositoryId }
            .takeIf { it >= 0 }
            ?.let { repositoryCombo.selectedIndex = it }
    }

    private data class ServerItem(val profile: BoscaServerProfile) {
        override fun toString(): String = profile.name
    }
}
